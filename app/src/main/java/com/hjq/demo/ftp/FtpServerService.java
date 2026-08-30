package com.hjq.demo.ftp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hjq.demo.R;
import com.hjq.demo.ui.activity.common.FtpServerActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FTP 服务端（前台 Service）
 *
 * 修复清单 v2：
 *  - [fix] startFtp 重启逻辑全部移到工作线程，主线程零阻塞，消除 ANR 风险
 *  - [fix] mRunning 改为 AtomicBoolean，CAS 操作保证可见性与原子性
 *  - [fix] forceClose + finally 双减竞态：用 AtomicBoolean mFinallyRan 保证 finally 只执行一次减法
 *  - [fix] 匿名模式双 230：USER 只发 331/230-anon，统一由 PASS 完成登录状态设置
 *  - [fix] handlePasv/handleEpsv 时清空 mPortHost/mPortPort，handlePort 时关闭旧 PasvServer
 *  - [fix] sFileLocks 改为实例级 ConcurrentHashMap + DELE/STOR 完成后移除锁对象，防内存泄漏
 *  - [fix] transfer 循环加入 mStopping/mClosed 检查，大文件可及时中断
 *  - ServerConfig 快照，Session 创建时固定配置
 *  - STOP 主动关闭所有 ClientSession
 *  - ThreadPoolExecutor 有界限制（最大 20 并发）
 *  - 数据连接 idle timeout（60s）
 *  - sendRaw() 失败标记 session 断开
 *  - RETR 用 RandomAccessFile.seek()
 *  - RMD 非递归（只能删空目录）
 *  - Anonymous 默认只读
 *  - RNTO 目录移动自身检测
 *  - STOR/APPE 文件级锁防并发覆盖
 *  - 登录失败限速（同 IP 5 次失败封禁 5 分钟）
 *  - STAT 不泄露真实 root 路径
 *  - FTP 命令最大长度 4096 字节
 *  - backup/part 残留文件服务启动时自动清理
 *  - service 重新 START 时若已运行先停再重启
 *  - stopFtp() 幂等保护
 */
public class FtpServerService extends Service {

    private static final String TAG = "FtpServerService";

    public static final String NOTIFICATION_CHANNEL_ID = "ftp_server";
    private static final int   NOTIFICATION_ID         = 10086;

    public static final String ACTION_START = "ftp_server_start";
    public static final String ACTION_STOP  = "ftp_server_stop";

    public static final String EXTRA_PORT            = "port";
    public static final String EXTRA_USERNAME        = "username";
    public static final String EXTRA_PASSWORD        = "password";
    public static final String EXTRA_ROOT_DIR        = "root_dir";
    public static final String EXTRA_ANONYMOUS       = "anonymous";
    public static final String EXTRA_ANONYMOUS_WRITE = "anonymous_write";

    public static final int    DEFAULT_PORT     = 2121;
    public static final String DEFAULT_USERNAME = "admin";
    public static final String DEFAULT_ROOT_DIR = "/storage/emulated/0";

    // -----------------------------------------------------------------------
    // 登录失败限速（进程级，Service 重启也保留封禁记录，属于设计意图）
    // -----------------------------------------------------------------------
    private static final ConcurrentHashMap<String, Integer> sFailCount = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long>    sBanExpiry = new ConcurrentHashMap<>();
    private static final int  MAX_FAIL     = 5;
    private static final long BAN_DURATION = 5 * 60 * 1000L;

    // -----------------------------------------------------------------------
    // 接口
    // -----------------------------------------------------------------------
    public interface OnLogListener {
        void onLog(@NonNull String message);
    }

    public interface OnStateListener {
        void onState(@NonNull String state, @Nullable String detail);
    }

    // -----------------------------------------------------------------------
    // 服务器配置快照
    // -----------------------------------------------------------------------
    static final class ServerConfig {
        final int     port;
        final String  username;
        final String  password;
        final String  rootDir;
        final boolean anonymous;
        final boolean anonymousWrite;

        ServerConfig(int port, String username, String password,
                     String rootDir, boolean anonymous, boolean anonymousWrite) {
            this.port           = port;
            this.username       = username;
            this.password       = password;
            this.rootDir        = rootDir;
            this.anonymous      = anonymous;
            this.anonymousWrite = anonymousWrite;
        }
    }

    // -----------------------------------------------------------------------
    // Binder
    // -----------------------------------------------------------------------
    public class LocalBinder extends Binder {
        @NonNull public FtpServerService getService() { return FtpServerService.this; }
    }
    private final LocalBinder mBinder = new LocalBinder();

    // -----------------------------------------------------------------------
    // Service 状态
    // fix: mRunning 改用 AtomicBoolean，CAS 保证可见性
    // -----------------------------------------------------------------------
    private final AtomicBoolean mRunning   = new AtomicBoolean(false);
    private final AtomicBoolean mStopping  = new AtomicBoolean(false);
    private final AtomicBoolean mStarting  = new AtomicBoolean(false);
    private final AtomicInteger mConnCount = new AtomicInteger(0);
    /** 生命周期版本：使已经排队的旧 START 在 STOP/新 START 后失效。 */
    private final AtomicLong mLifecycleVersion = new AtomicLong(0);

    private ServerConfig   mConfig;
    private ServerSocket   mServerSocket;
    private ThreadPoolExecutor mExecutor;

    /** 所有活跃 Session，用于 STOP 时主动关闭 */
    private final Set<ClientSession> mSessions =
            Collections.synchronizedSet(new HashSet<>());

    /**
     * fix: 实例级文件锁 Map（替代 static），Service 销毁后自动 GC，无内存泄漏。
     * DELE / STOR 完成后主动 remove，防止长期运行中键无限累积。
     */
    private final ConcurrentHashMap<String, Object> mFileLocks = new ConcurrentHashMap<>();

    private Object getFileLock(File f) {
        String key = f.getAbsolutePath();
        mFileLocks.putIfAbsent(key, new Object());
        return mFileLocks.get(key);
    }

    private void releaseFileLock(File f) {
        // synchronized(obj) 锁定的是对象引用本身，而非 Map 中的 entry。
        // 持有该引用的 synchronized 块在 Map.remove() 后依然正常执行完毕，
        // 因此移除 entry 不会破坏正在运行的临界区，安全地清理 Map 防止内存泄漏。
        mFileLocks.remove(f.getAbsolutePath());
    }

    private OnLogListener   mLogListener;
    private OnStateListener mStateListener;
    private final Handler   mMainHandler = new Handler(Looper.getMainLooper());

    private static final int MAX_CONNECTIONS = 20;
    private static final int MAX_CMD_LEN     = 4096;

    // =======================================================================
    // Lifecycle
    // =======================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        mExecutor = new ThreadPoolExecutor(
                2, MAX_CONNECTIONS, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> { Thread t = new Thread(r, "ftp-worker"); t.setDaemon(true); return t; },
                (r, executor) -> Log.w(TAG, "FTP 连接数已达上限，拒绝新连接"));
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            int    port  = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT);
            String u     = intent.getStringExtra(EXTRA_USERNAME);
            String p     = intent.getStringExtra(EXTRA_PASSWORD);
            String r     = intent.getStringExtra(EXTRA_ROOT_DIR);
            boolean anon = intent.getBooleanExtra(EXTRA_ANONYMOUS, false);
            boolean aW   = intent.getBooleanExtra(EXTRA_ANONYMOUS_WRITE, false);
            ServerConfig cfg = new ServerConfig(
                    port,
                    u != null ? u : DEFAULT_USERNAME,
                    p != null ? p : "",
                    r != null ? r : DEFAULT_ROOT_DIR,
                    anon, aW);
            startFtp(cfg);
        } else if (ACTION_STOP.equals(action)) {
            stopFtp();
        }
        return START_NOT_STICKY;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopFtp();
        if (mExecutor != null) mExecutor.shutdownNow();
    }

    // =======================================================================
    // 公开 API
    // =======================================================================

    public boolean isRunning() { return mRunning.get(); }
    public int     getPort()   { return mConfig != null ? mConfig.port : DEFAULT_PORT; }
    public void setOnLogListener(@Nullable OnLogListener l)     { mLogListener   = l; }
    public void setOnStateListener(@Nullable OnStateListener l) { mStateListener = l; }

    // =======================================================================
    // 启动 / 停止
    // fix: startFtp 的"停旧服再等待"逻辑全部移入工作线程，主线程不阻塞
    // =======================================================================

    private void startFtp(@NonNull ServerConfig cfg) {
        if (!mStarting.compareAndSet(false, true)) {
            // 已在启动中，忽略重复请求
            return;
        }
        final long version = mLifecycleVersion.incrementAndGet();
        mStopping.set(false);
        mConfig = cfg;

        try {
            mExecutor.execute(() -> {
                try {
                    // 如果旧实例还在运行，先在工作线程等它退出（最多 4 秒）
                    if (mRunning.get()) {
                        stopFtpInternal(false);
                        long deadline = System.currentTimeMillis() + 4_000;
                        while (mRunning.get() && System.currentTimeMillis() < deadline) {
                            try { Thread.sleep(100); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        }
                    }
                    // STOP 或新的 START 已经使本次启动失效。
                    if (version != mLifecycleVersion.get()) return;
                    if (mStopping.get()) return;

                    File root = new File(cfg.rootDir);
                    if (!root.isDirectory()) {
                        String msg = "启动 FTP 失败：根目录不存在或不是目录：" + cfg.rootDir;
                        appendLog(msg);
                        notifyState("ERROR", msg);
                        return;
                    }
                    cleanupTempFiles(root);
                    if (version != mLifecycleVersion.get() || mStopping.get()) return;
                    runAcceptLoop(cfg);
                } finally {
                    mStarting.set(false);
                }
            });
        } catch (RuntimeException e) {
            mStarting.set(false);
            appendLog("FTP 启动任务提交失败：" + e.getMessage());
            notifyState("ERROR", "FTP 启动任务提交失败：" + e.getMessage());
        }
    }

    /**
     * 公开停止入口，幂等。
     */
    public void stopFtp() {
        // 幂等保护：既没运行也没在启动，且 socket 已关，则直接返回
        if (!mRunning.get() && !mStarting.get()
                && (mServerSocket == null || mServerSocket.isClosed())) {
            return;
        }
        stopFtpInternal();
    }

    /**
     * 内部真正执行停止的方法（可在任意线程调用）。
     */
    private void stopFtpInternal() {
        stopFtpInternal(true);
    }

    private void stopFtpInternal(boolean invalidatePendingStarts) {
        if (invalidatePendingStarts) mLifecycleVersion.incrementAndGet();
        mStopping.set(true);
        // 主动关闭所有 ClientSession
        synchronized (mSessions) {
            for (ClientSession s : mSessions) s.forceClose();
            mSessions.clear();
        }
        try {
            if (mServerSocket != null && !mServerSocket.isClosed()) mServerSocket.close();
        } catch (IOException ignored) {}
        mRunning.set(false);
        appendLog("FTP 服务已停止");
        notifyState("STOPPED", null);
        // 仅在真正对外停止时才 stopSelf；内部重启（invalidatePendingStarts=false）
        // 不调用 stopSelf，避免 Service 在准备重新监听时被系统销毁。
        if (invalidatePendingStarts) {
            stopForeground(true);
            stopSelf();
        }
    }

    private void runAcceptLoop(@NonNull ServerConfig cfg) {
        try {
            mServerSocket = new ServerSocket(cfg.port);
            mServerSocket.setReuseAddress(true);
            if (mStopping.get()) {
                try { mServerSocket.close(); } catch (IOException ignored) {}
                return;
            }
            mRunning.set(true);
            mConnCount.set(0);
            appendLog("FTP 服务启动，端口：" + cfg.port + "，根目录：" + cfg.rootDir);
            notifyState("RUNNING", null);
            showNotification(cfg.port);

            while (!mStopping.get()) {
                try {
                    Socket client = mServerSocket.accept();
                    if (mStopping.get()) { try { client.close(); } catch (IOException ig) {} break; }

                    if (mConnCount.get() >= MAX_CONNECTIONS) {
                        try {
                            client.getOutputStream().write("421 Too many connections\r\n".getBytes("UTF-8"));
                            client.getOutputStream().flush();
                        } catch (IOException ig) {}
                        client.close();
                        appendLog("连接数超限，拒绝：" + client.getInetAddress().getHostAddress());
                        continue;
                    }

                    client.setSoTimeout(0);
                    client.setTcpNoDelay(true);
                    String ip = client.getInetAddress().getHostAddress();
                    appendLog("客户端连接：" + ip);
                    ClientSession session = new ClientSession(client, ip, cfg);
                    mSessions.add(session);
                    mConnCount.incrementAndGet();
                    try {
                        mExecutor.execute(session);
                    } catch (RuntimeException rejected) {
                        // execute 被拒绝时任务根本不会进入 run/finally，因此必须在这里回收计数。
                        mSessions.remove(session);
                        session.forceClose();
                        try { client.close(); } catch (IOException ignored) {}
                        appendLog("连接任务提交失败：" + rejected.getMessage());
                    }
                } catch (IOException e) {
                    if (!mStopping.get()) appendLog("接受连接出错：" + e.getMessage());
                }
            }
        } catch (IOException e) {
            String msg = "启动 FTP 失败：" + e.getMessage();
            appendLog(msg);
            notifyState("ERROR", msg);
            Log.e(TAG, "runAcceptLoop", e);
        } finally {
            mRunning.set(false);
        }
    }

    /** 递归清理残留临时文件 */
    private void cleanupTempFiles(@NonNull File dir) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".ftp-backup-") || name.endsWith(".ftp-upload.part")
                    || (name.startsWith(".") && name.contains(".ftp-") && name.endsWith(".part"))) {
                if (f.isFile() && f.delete()) Log.d(TAG, "清理残留临时文件：" + f.getAbsolutePath());
            } else if (f.isDirectory()) {
                cleanupTempFiles(f);
            }
        }
    }

    // =======================================================================
    // 通知
    // =======================================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.ftp_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.ftp_notification_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void showNotification(int port) {
        Intent intent = new Intent(this, FtpServerActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piFlags);
        Notification n = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.ftp_notification_title))
                .setContentText(getString(R.string.ftp_notification_text, port))
                .setSmallIcon(R.mipmap.launcher_ic)
                .setContentIntent(pi).setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE).build();
        startForeground(NOTIFICATION_ID, n);
    }

    // =======================================================================
    // 日志 / 状态
    // =======================================================================

    private void appendLog(@NonNull String msg) {
        Log.d(TAG, msg);
        String ts   = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + ts + "] " + msg;
        if (mLogListener != null) {
            mMainHandler.post(() -> { if (mLogListener != null) mLogListener.onLog(line); });
        }
    }

    private void notifyState(@NonNull String state, @Nullable String detail) {
        if (mStateListener != null) {
            mMainHandler.post(() -> { if (mStateListener != null) mStateListener.onState(state, detail); });
        }
    }

    // =======================================================================
    // 静态工厂
    // =======================================================================

    public static void start(@NonNull Context ctx, int port, @NonNull String user,
                             @NonNull String pass, @NonNull String root,
                             boolean anonymous, boolean anonymousWrite) {
        Intent i = new Intent(ctx, FtpServerService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_PORT, port);
        i.putExtra(EXTRA_USERNAME, user);
        i.putExtra(EXTRA_PASSWORD, pass);
        i.putExtra(EXTRA_ROOT_DIR, root);
        i.putExtra(EXTRA_ANONYMOUS, anonymous);
        i.putExtra(EXTRA_ANONYMOUS_WRITE, anonymousWrite);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(@NonNull Context ctx) {
        Intent i = new Intent(ctx, FtpServerService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    // =======================================================================
    // 获取局域网 IP
    // =======================================================================

    @NonNull
    private String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) return String.format(Locale.US, "%d.%d.%d.%d",
                        ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        } catch (Exception ignored) {}
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && (a instanceof Inet4Address) && a.isSiteLocalAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    // =======================================================================
    // ClientSession
    // =======================================================================

    final class ClientSession implements Runnable {

        private final Socket       mCtrlSocket;
        private final String       mClientIp;
        private final ServerConfig mCfg;

        // 会话状态
        private boolean mLoggedIn      = false;
        private boolean mAnonymousUser = false;
        private String  mPendingUser   = null;
        private String  mCurrentDir    = "/";
        private String  mRenameFrom    = null;
        private String  mTransferType  = "I";
        private long    mRestartPos    = 0L;
        private volatile boolean mClosed = false;

        /**
         * fix: 用 AtomicBoolean 保证 mConnCount.decrementAndGet() 只执行一次。
         * forceClose() 和 run() 的 finally 都会尝试清理，但只有第一次 CAS 成功的那个执行减法。
         */
        private final AtomicBoolean mFinallyRan = new AtomicBoolean(false);

        // 数据连接
        private ServerSocket mPasvServer = null;
        private String       mPortHost   = null;
        private int          mPortPort   = -1;
        private boolean      mPassive    = true;

        ClientSession(@NonNull Socket s, @NonNull String ip, @NonNull ServerConfig cfg) {
            mCtrlSocket = s;
            mClientIp   = ip;
            mCfg        = cfg;
        }

        /** STOP 时外部主动强制关闭 */
        void forceClose() {
            mClosed = true;
            closePasv();
            try { mCtrlSocket.close(); } catch (IOException ignored) {}
            // fix: 只有第一次调用才减计数，防止 finally 再次减导致负数
            if (mFinallyRan.compareAndSet(false, true)) {
                mConnCount.decrementAndGet();
            }
        }

        @Override
        public void run() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(mCtrlSocket.getInputStream(), "ISO-8859-1"),
                        MAX_CMD_LEN * 2);
                sendRaw("220 FTP Server Ready\r\n");

                String rawLine;
                while (!mStopping.get() && !mClosed && (rawLine = reader.readLine()) != null) {
                    String line = new String(rawLine.getBytes("ISO-8859-1"), "UTF-8").trim();
                    if (line.getBytes("UTF-8").length > MAX_CMD_LEN) {
                        if (!sendRaw("500 Line too long\r\n")) break;
                        continue;
                    }
                    if (line.isEmpty()) continue;
                    int    sp  = line.indexOf(' ');
                    String cmd = (sp >= 0 ? line.substring(0, sp) : line).toUpperCase(Locale.US);
                    String args = sp >= 0 ? line.substring(sp + 1).trim() : "";
                    Log.d(TAG, "← [" + mClientIp + "] " + cmd + (args.isEmpty() ? "" : " " + args));
                    if (!dispatch(cmd, args)) break;
                }
            } catch (Exception e) {
                if (!mStopping.get() && !mClosed)
                    appendLog("会话异常 [" + mClientIp + "]: " + e.getMessage());
            } finally {
                mClosed = true;
                closePasv();
                try { mCtrlSocket.close(); } catch (IOException ignored) {}
                mSessions.remove(this);
                // fix: 只有 forceClose() 没有先执行时，这里才减计数
                if (mFinallyRan.compareAndSet(false, true)) {
                    mConnCount.decrementAndGet();
                }
                appendLog("断开连接：" + mClientIp);
            }
        }

        // -------------------------------------------------------------------
        // 命令派发
        // -------------------------------------------------------------------

        private boolean dispatch(String cmd, String args) {
            switch (cmd) {
                case "USER": handleUser(args);    return true;
                case "PASS": handlePass(args);    return true;
                case "QUIT": sendRaw("221 Goodbye\r\n"); return false;
                case "SYST": sendRaw("215 UNIX Type: L8\r\n"); return true;
                case "FEAT": handleFeat();        return true;
                case "OPTS": handleOpts(args);    return true;
                case "NOOP": sendRaw("200 NOOP ok\r\n"); return true;
            }
            if (!requireLogin()) return true;
            // REST 只作用于紧随其后的 RETR/STOR/APPE，避免偏移量泄漏到后续操作。
            if (mRestartPos > 0 && !isRestConsumer(cmd)) mRestartPos = 0L;
            switch (cmd) {
                case "TYPE": handleType(args);    break;
                case "PWD": case "XPWD":
                    sendRaw("257 \"" + mCurrentDir + "\" is current directory\r\n"); break;
                case "CWD": case "XCWD": handleCwd(args);   break;
                case "CDUP":case "XCUP": handleCdup();      break;
                case "PASV": handlePasv();                  break;
                case "EPSV": handleEpsv();                  break;
                case "PORT": handlePort(args);              break;
                case "LIST": handleList(args);              break;
                case "NLST": handleNlst(args);              break;
                case "STAT": handleStat(args);              break;
                case "RETR": handleRetr(args);              break;
                case "SIZE": handleSize(args);              break;
                case "MDTM": handleMdtm(args);              break;
                case "REST": handleRest(args);              break;
                case "AVBL": handleAvbl();                  break;
                case "ABOR": sendRaw("226 ABOR ok\r\n");   break;
                case "STOR": handleStor(args, false);       break;
                case "APPE": handleStor(args, true);        break;
                case "STOU": handleStou();                  break;
                case "DELE": handleDele(args);              break;
                case "MKD": case "XMKD": handleMkd(args);  break;
                case "RMD": case "XRMD": handleRmd(args);  break;
                case "RNFR": handleRnfr(args);              break;
                case "RNTO": handleRnto(args);              break;
                default: sendRaw("502 Command not implemented: " + cmd + "\r\n"); break;
            }
            return true;
        }

        // -------------------------------------------------------------------
        // 发送工具
        // -------------------------------------------------------------------

        private boolean sendRaw(@NonNull String text) {
            try {
                byte[] bytes = text.getBytes("UTF-8");
                OutputStream os = mCtrlSocket.getOutputStream();
                os.write(bytes);
                os.flush();
                Log.d(TAG, "→ [" + mClientIp + "] " + text.trim());
                return true;
            } catch (IOException e) {
                Log.w(TAG, "sendRaw failed [" + mClientIp + "]: " + e.getMessage());
                mClosed = true;
                return false;
            }
        }

        private boolean reply(@NonNull String msg) { return sendRaw(msg + "\r\n"); }

        private boolean requireLogin() {
            if (!mLoggedIn) { reply("530 Not logged in"); return false; }
            return true;
        }

        private boolean isRestConsumer(@NonNull String cmd) {
            return "RETR".equals(cmd) || "STOR".equals(cmd) || "APPE".equals(cmd);
        }

        private boolean requireWrite() {
            if (mAnonymousUser && !mCfg.anonymousWrite) {
                reply("550 Permission denied: anonymous read-only");
                return false;
            }
            return true;
        }

        // -------------------------------------------------------------------
        // 认证
        // fix: 匿名模式下 USER 只发 331，不直接登录；PASS 统一完成登录状态设置，消除双 230
        // -------------------------------------------------------------------

        private void handleUser(String user) {
            Long banExp = sBanExpiry.get(mClientIp);
            if (banExp != null && System.currentTimeMillis() < banExp) {
                reply("421 Too many failed attempts, try later");
                return;
            }
            // 无论是否匿名，先重置登录态，等待 PASS
            mPendingUser   = user;
            mLoggedIn      = false;
            mAnonymousUser = false;
            reply("331 Password required for " + user);
        }

        private void handlePass(String pass) {
            if (mPendingUser == null) {
                reply("503 USER required before PASS");
                return;
            }
            if (mCfg.anonymous) {
                // 匿名模式：任意 PASS 均接受，但必须先 USER。
                mLoggedIn      = true;
                mAnonymousUser = true;
                appendLog("匿名用户登录：" + mClientIp);
                mPendingUser   = null;
                reply("230 User logged in");
                return;
            }
            if (mPendingUser.equals(mCfg.username)
                    && pass.equals(mCfg.password)) {
                mLoggedIn      = true;
                mAnonymousUser = false;
                sFailCount.remove(mClientIp);
                sBanExpiry.remove(mClientIp);
                appendLog("登录成功：" + mPendingUser + " [" + mClientIp + "]");
                mPendingUser = null;
                reply("230 User logged in");
            } else {
                int count = sFailCount.merge(mClientIp, 1, Integer::sum);
                if (count >= MAX_FAIL) {
                    sBanExpiry.put(mClientIp, System.currentTimeMillis() + BAN_DURATION);
                    sFailCount.remove(mClientIp);
                    appendLog("登录失败次数过多，封禁 IP：" + mClientIp);
                    reply("421 Too many failed attempts, banned for 5 minutes");
                } else {
                    appendLog("登录失败（" + count + "/" + MAX_FAIL + "）：" + mPendingUser + " [" + mClientIp + "]");
                    reply("430 Invalid username or password (" + count + "/" + MAX_FAIL + " attempts)");
                }
                mPendingUser = null;
                mLoggedIn = false;
                mAnonymousUser = false;
            }
        }

        // -------------------------------------------------------------------
        // FEAT / OPTS / TYPE
        // -------------------------------------------------------------------

        private void handleFeat() {
            sendRaw("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n PASV\r\n EPSV\r\n TVFS\r\n211 End\r\n");
        }

        private void handleOpts(String args) {
            String u = args.toUpperCase(Locale.US).trim();
            if      (u.equals("UTF8 ON"))      reply("200 UTF8 mode enabled");
            else if (u.equals("UTF8 OFF"))     reply("504 UTF8 OFF not supported");
            else if (u.startsWith("MLST"))     reply("200 OK");
            else                               reply("501 Unknown OPTS: " + args);
        }

        private void handleType(String args) {
            String t = args.toUpperCase(Locale.US).trim();
            if      (t.startsWith("A")) { mTransferType = "A"; reply("200 Type set to A (transfers use binary mode)"); }
            else if (t.startsWith("I") || t.startsWith("L")) { mTransferType = "I"; reply("200 Type set to I"); }
            else    reply("504 Unknown type: " + args);
        }

        // -------------------------------------------------------------------
        // 目录导航
        // -------------------------------------------------------------------

        private void handleCwd(String path) {
            if (path.isEmpty() || ".".equals(path)) {
                reply("250 Directory unchanged: \"" + mCurrentDir + "\""); return;
            }
            File target = resolvePath(path);
            if (target != null && target.isDirectory()) {
                mCurrentDir = toVirtualPath(target);
                reply("250 Directory changed to \"" + mCurrentDir + "\"");
            } else {
                reply("550 No such directory: " + path);
            }
        }

        private void handleCdup() {
            if ("/".equals(mCurrentDir)) { reply("250 Already at root"); return; }
            int last = mCurrentDir.lastIndexOf('/');
            mCurrentDir = (last <= 0) ? "/" : mCurrentDir.substring(0, last);
            if (mCurrentDir.isEmpty()) mCurrentDir = "/";
            File f = resolvePath(mCurrentDir);
            if (f == null || !f.isDirectory()) mCurrentDir = "/";
            reply("250 Directory changed to \"" + mCurrentDir + "\"");
        }

        // -------------------------------------------------------------------
        // PASV / EPSV / PORT
        // fix: PASV/EPSV 清空 PORT 状态；PORT 关闭旧 PasvServer，保持状态对称
        // -------------------------------------------------------------------

        private void handlePasv() {
            closePasv();
            // fix: 切换到 PASV 模式时清空 PORT 残留状态
            mPortHost = null;
            mPortPort = -1;
            try {
                mPasvServer = new ServerSocket(0);
                mPasvServer.setReuseAddress(true);
                mPassive = true;
                String ip   = getLocalIpAddress();
                int    port = mPasvServer.getLocalPort();
                reply("227 Entering Passive Mode (" + ip.replace('.', ',') + ","
                        + (port / 256) + "," + (port % 256) + ")");
            } catch (IOException e) { reply("425 Can't open data connection"); }
        }

        private void handleEpsv() {
            closePasv();
            // fix: 切换到 EPSV 模式时清空 PORT 残留状态
            mPortHost = null;
            mPortPort = -1;
            try {
                mPasvServer = new ServerSocket(0);
                mPasvServer.setReuseAddress(true);
                mPassive = true;
                reply("229 Entering Extended Passive Mode (|||" + mPasvServer.getLocalPort() + "|)");
            } catch (IOException e) { reply("425 Can't open data connection"); }
        }

        private void handlePort(String args) {
            // fix: PORT 命令时关闭旧的 PASV ServerSocket，保持状态对称
            closePasv();
            String[] p = args.split(",");
            if (p.length != 6) { reply("500 Illegal PORT command"); return; }
            try {
                int h1=Integer.parseInt(p[0].trim()), h2=Integer.parseInt(p[1].trim()),
                    h3=Integer.parseInt(p[2].trim()), h4=Integer.parseInt(p[3].trim()),
                    p1=Integer.parseInt(p[4].trim()), p2=Integer.parseInt(p[5].trim());
                if (!vb(h1)||!vb(h2)||!vb(h3)||!vb(h4)||!vb(p1)||!vb(p2)) {
                    reply("500 Illegal PORT command"); return;
                }
                String host = h1+"."+h2+"."+h3+"."+h4;
                int    port = p1*256+p2;
                if (!mClientIp.equals(host)) {
                    reply("501 PORT host must match control connection"); return;
                }
                if (port < 1024 || port > 65535) { reply("501 Invalid PORT value"); return; }
                mPortHost = host; mPortPort = port; mPassive = false;
                reply("200 PORT command successful");
            } catch (NumberFormatException e) { reply("500 Illegal PORT command"); }
        }

        private boolean vb(int v) { return v >= 0 && v <= 255; }

        private void closePasv() {
            if (mPasvServer != null && !mPasvServer.isClosed()) {
                try { mPasvServer.close(); } catch (IOException ignored) {}
            }
            mPasvServer = null;
        }

        @Nullable
        private Socket openDataConn() {
            try {
                if (mPassive) {
                    if (mPasvServer == null || mPasvServer.isClosed()) {
                        appendLog("PASV socket 已关闭"); return null;
                    }
                    mPasvServer.setSoTimeout(15_000);
                    Socket s = mPasvServer.accept();
                    // 一个 PASV/EPSV 监听端只服务一次数据连接。
                    try { mPasvServer.close(); } catch (IOException ignored) {}
                    mPasvServer = null;
                    String peer = s.getInetAddress().getHostAddress();
                    if (!mClientIp.equals(peer)) {
                        try { s.close(); } catch (IOException ig) {}
                        appendLog("拒绝非法数据连接来源：" + peer); return null;
                    }
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(60_000);
                    return s;
                } else {
                    if (mPortHost == null || mPortPort <= 0) { appendLog("PORT 未设置"); return null; }
                    Socket s = new Socket(mPortHost, mPortPort);
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(60_000);
                    return s;
                }
            } catch (IOException e) { appendLog("数据连接失败：" + e.getMessage()); return null; }
        }

        // -------------------------------------------------------------------
        // LIST / NLST
        // -------------------------------------------------------------------

        private void handleList(String args) {
            String param  = stripOpts(args);
            File   target = param.isEmpty() ? resolvePath(mCurrentDir) : resolvePath(param);
            if (target == null || !target.exists()) { reply("550 No such file or directory"); return; }

            Socket dataSock = openWithPre("150 Here comes the directory listing\r\n");
            if (dataSock == null) return;
            boolean ok = false;
            try {
                OutputStream out = dataSock.getOutputStream();
                if (target.isDirectory()) {
                    File[] files = target.listFiles();
                    if (files != null && files.length > 0) {
                        List<File> sorted = new ArrayList<>(Arrays.asList(files));
                        sorted.sort((a, b) -> {
                            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                            return a.getName().compareToIgnoreCase(b.getName());
                        });
                        for (File f : sorted) out.write((buildListLine(f) + "\r\n").getBytes("UTF-8"));
                    }
                } else {
                    out.write((buildListLine(target) + "\r\n").getBytes("UTF-8"));
                }
                out.flush(); ok = true;
                appendLog("LIST " + (param.isEmpty() ? mCurrentDir : param));
            } catch (IOException e) { appendLog("LIST 失败：" + e.getMessage()); }
            finally { closeData(dataSock); }
            reply(ok ? "226 Directory send OK" : "426 Connection closed; transfer aborted");
        }

        private void handleNlst(String args) {
            String param  = stripOpts(args);
            File   target = param.isEmpty() ? resolvePath(mCurrentDir) : resolvePath(param);
            if (target == null || !target.exists()) { reply("550 No such file or directory"); return; }

            Socket dataSock = openWithPre("150 Here comes the name list\r\n");
            if (dataSock == null) return;
            boolean ok = false;
            try {
                OutputStream out = dataSock.getOutputStream();
                if (target.isDirectory()) {
                    File[] files = target.listFiles();
                    if (files != null) for (File f : files) out.write((f.getName() + "\r\n").getBytes("UTF-8"));
                } else {
                    out.write((target.getName() + "\r\n").getBytes("UTF-8"));
                }
                out.flush(); ok = true;
            } catch (IOException e) { appendLog("NLST 失败：" + e.getMessage()); }
            finally { closeData(dataSock); }
            reply(ok ? "226 Directory send OK" : "426 Connection closed; transfer aborted");
        }

        @Nullable
        private Socket openWithPre(String pre150) {
            if (mPassive) {
                if (!sendRaw(pre150)) return null;
                Socket s = openDataConn();
                if (s == null) { reply("425 Can't open data connection"); return null; }
                return s;
            } else {
                Socket s = openDataConn();
                if (s == null) { reply("425 Can't open data connection"); return null; }
                if (!sendRaw(pre150)) { closeData(s); return null; }
                return s;
            }
        }

        private void closeData(Socket s) {
            if (s != null && !s.isClosed()) try { s.close(); } catch (IOException ig) {}
            closePasv();
        }

        @NonNull
        private String buildListLine(@NonNull File f) {
            String perm  = f.isDirectory() ? "drwxrwxrwx" : "-rw-rw-rw-";
            long   size  = f.isDirectory() ? 4096L : f.length();
            Date   fd    = new Date(f.lastModified());
            Calendar fc  = Calendar.getInstance(); fc.setTime(fd);
            Calendar nc  = Calendar.getInstance();
            Locale   en  = Locale.US;
            String month = new SimpleDateFormat("MMM", en).format(fd);
            String day   = String.format(Locale.US, "%2d", fc.get(Calendar.DAY_OF_MONTH));
            String tor   = (fc.get(Calendar.YEAR) == nc.get(Calendar.YEAR))
                    ? new SimpleDateFormat("HH:mm", en).format(fd)
                    : String.format(Locale.US, "%5s", new SimpleDateFormat("yyyy", en).format(fd));
            return perm + " 1 ftp ftp " + String.format(Locale.US, "%8d", size)
                    + " " + month + " " + day + " " + tor + " " + f.getName();
        }

        @NonNull
        private String stripOpts(@Nullable String args) {
            if (args == null || args.trim().isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String t : args.trim().split("\\s+")) {
                if (!t.startsWith("-")) { if (sb.length() > 0) sb.append(' '); sb.append(t); }
            }
            return sb.toString().trim();
        }

        // -------------------------------------------------------------------
        // RETR
        // -------------------------------------------------------------------

        private void handleRetr(String args) {
            if (args.isEmpty()) { reply("501 No filename"); return; }
            File file = resolvePath(args);
            if (file == null || !file.exists() || file.isDirectory()) { reply("550 No such file: " + args); return; }

            long skip    = mRestartPos; mRestartPos = 0L;
            long fileLen = file.length();
            if (skip > fileLen) { reply("551 Restart position exceeds file size"); return; }
            long sendSize = fileLen - skip;

            Socket dataSock = openWithPre("150 Opening " + mTransferType + " mode data connection ("
                    + sendSize + " bytes)\r\n");
            if (dataSock == null) return;

            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 OutputStream     out = dataSock.getOutputStream()) {
                raf.seek(skip);
                byte[] buf = new byte[65536]; int n; long sent = 0;
                while ((n = raf.read(buf)) != -1) {
                    // fix: 传输循环检查中断标志，stop 后可及时退出
                    if (mStopping.get() || mClosed) throw new IOException("Transfer interrupted by server stop");
                    out.write(buf, 0, n);
                    sent += n;
                }
                out.flush();
                appendLog("RETR 完成：" + file.getName()
                        + (skip > 0 ? "（REST+" + skip + "B）" : "") + "，" + sent + " 字节");
                reply("226 Transfer complete");
            } catch (IOException e) {
                appendLog("RETR 失败：" + e.getMessage()); reply("426 Transfer aborted");
            } finally { closeData(dataSock); }
        }

        // -------------------------------------------------------------------
        // STOR / APPE
        // fix: transfer() 方法内加中断检查；DELE 后调用 releaseFileLock() 清理锁 Map
        // -------------------------------------------------------------------

        private void handleStor(String args, boolean append) {
            if (!requireWrite()) return;
            if (args.isEmpty()) { reply("501 No filename"); return; }
            File file = resolvePath(args);
            if (file == null) { reply("550 Invalid path"); return; }
            if (file.isDirectory()) { reply("550 Is a directory"); return; }
            File parent = file.getParentFile();
            if (parent == null || !parent.isDirectory()) { reply("550 Parent directory not found"); return; }

            final long restPos = mRestartPos; mRestartPos = 0L;
            if (!append && restPos > 0) {
                if (!file.exists() || !file.isFile()) { reply("550 No existing file for REST STOR"); return; }
                if (restPos > file.length()) { reply("551 Restart position exceeds file size"); return; }
            }

            Socket dataSock = openWithPre("150 Ok to send data\r\n");
            if (dataSock == null) return;

            synchronized (getFileLock(file)) {
                File workFile = null;
                try (InputStream in = dataSock.getInputStream()) {
                    if (!append && restPos > 0) {
                        workFile = createPartFile(file);
                        copyFile(file, workFile);
                        try (RandomAccessFile raf = new RandomAccessFile(workFile, "rw")) {
                            raf.seek(restPos); transfer(in, raf); raf.getFD().sync();
                        }
                    } else if (!append) {
                        workFile = createPartFile(file);
                        try (FileOutputStream fos = new FileOutputStream(workFile, false)) {
                            transfer(in, fos); fos.getFD().sync();
                        }
                    } else {
                        workFile = createPartFile(file);
                        if (file.exists()) copyFile(file, workFile);
                        try (FileOutputStream fos = new FileOutputStream(workFile, true)) {
                            transfer(in, fos); fos.getFD().sync();
                        }
                    }
                    if (!atomicReplace(file, workFile)) throw new IOException("Cannot replace target file");
                    workFile = null;
                    appendLog((append ? "APPE" : "STOR") + " 完成：" + file.getName());
                    reply("226 Transfer complete");
                } catch (IOException e) {
                    appendLog((append ? "APPE" : "STOR") + " 失败：" + e.getMessage());
                    if (workFile != null && workFile.exists()) workFile.delete();
                    reply("426 Connection closed; transfer aborted");
                } finally {
                    closeData(dataSock);
                    // fix: 传输完成后移除锁对象，防止 Map 无限增长
                    releaseFileLock(file);
                }
            }
        }

        // -------------------------------------------------------------------
        // STOU
        // -------------------------------------------------------------------

        private void handleStou() {
            if (!requireWrite()) return;
            File parentDir = resolvePath(mCurrentDir);
            if (parentDir == null || !parentDir.isDirectory()) { reply("550 Current directory unavailable"); return; }

            File   uploadFile = null; String uniqueName = null;
            for (int i = 0; i < 20; i++) {
                String candidate = "upload_" + UUID.randomUUID();
                File   cf = new File(parentDir, candidate);
                try { if (cf.createNewFile()) { uploadFile = cf; uniqueName = candidate; break; } }
                catch (IOException ig) {}
            }
            if (uploadFile == null) { reply("550 Cannot create unique file"); return; }
            final File uf = uploadFile;

            Socket dataSock = openWithPre("150 FILE: " + uniqueName + "\r\n");
            if (dataSock == null) { uf.delete(); return; }

            try (InputStream in = dataSock.getInputStream();
                 FileOutputStream fos = new FileOutputStream(uf, false)) {
                transfer(in, fos); fos.getFD().sync();
                appendLog("STOU 完成：" + uniqueName);
                reply("250 Transfer complete. Unique file: " + uniqueName);
            } catch (IOException e) {
                uf.delete(); appendLog("STOU 失败：" + e.getMessage()); reply("426 Transfer aborted");
            } finally { closeData(dataSock); }
        }

        // -------------------------------------------------------------------
        // DELE / MKD / RMD / RNFR / RNTO
        // -------------------------------------------------------------------

        private void handleDele(String args) {
            if (!requireWrite()) return;
            File f = resolvePath(args);
            if (f == null || !f.exists()) { reply("550 No such file"); return; }
            if (f.isDirectory()) { reply("550 Is a directory, use RMD"); return; }
            if (f.delete()) {
                // fix: 删除文件后清理对应的锁对象
                releaseFileLock(f);
                appendLog("DELE：" + f.getName()); reply("250 DELE command successful");
            } else {
                reply("550 Delete failed");
            }
        }

        private void handleMkd(String args) {
            if (!requireWrite()) return;
            if (args.isEmpty()) { reply("501 No directory name"); return; }
            File dir = resolvePath(args);
            if (dir == null) { reply("550 Invalid path"); return; }
            if (dir.exists()) {
                reply(dir.isDirectory()
                        ? "257 \"" + toVirtualPath(dir) + "\" already exists"
                        : "550 A file with that name already exists");
                return;
            }
            if (dir.mkdirs()) {
                appendLog("MKD：" + toVirtualPath(dir));
                reply("257 \"" + toVirtualPath(dir) + "\" created");
            } else {
                reply("550 Create directory failed");
            }
        }

        private void handleRmd(String args) {
            if (!requireWrite()) return;
            if (args.isEmpty()) { reply("501 No directory name"); return; }
            File dir = resolvePath(args);
            if (dir == null || !dir.exists()) { reply("550 No such directory"); return; }
            if (!dir.isDirectory()) { reply("550 Not a directory, use DELE"); return; }
            if (toVirtualPath(dir).equals("/")) { reply("550 Cannot remove root"); return; }
            File[] children = dir.listFiles();
            if (children != null && children.length > 0) { reply("550 Directory not empty"); return; }
            if (dir.delete()) { appendLog("RMD：" + args); reply("250 RMD command successful"); }
            else reply("550 Remove directory failed");
        }

        private void handleRnfr(String args) {
            if (!requireWrite()) return;
            if (args.isEmpty()) { reply("501 No filename"); return; }
            File f = resolvePath(args);
            if (f != null && f.exists()) {
                mRenameFrom = f.getAbsolutePath();
                reply("350 RNFR accepted, ready for RNTO");
            } else {
                mRenameFrom = null; reply("550 No such file or directory");
            }
        }

        private void handleRnto(String args) {
            if (!requireWrite()) return;
            if (mRenameFrom == null) { reply("503 RNFR required first"); return; }
            if (args.isEmpty()) { reply("501 No target name"); return; }
            File src  = new File(mRenameFrom);
            File dest = resolvePath(args);
            mRenameFrom = null;
            if (!src.exists()) { reply("550 Source no longer exists"); return; }
            if (dest == null) { reply("550 Invalid target path"); return; }
            try {
                if (src.getCanonicalFile().equals(dest.getCanonicalFile())) {
                    reply("250 Rename successful");
                    return;
                }
            } catch (IOException e) {
                reply("550 Path resolution failed");
                return;
            }
            File destParent = dest.getParentFile();
            if (destParent == null || !destParent.isDirectory()) { reply("550 Target parent not found"); return; }

            // 不允许用一个目录覆盖另一个已有目录，否则后面的 backup 删除会递归删除原目录内容。
            if (dest.exists() && dest.isDirectory()) {
                reply("550 Target directory already exists");
                return;
            }

            if (src.isDirectory()) {
                try {
                    String sc = src.getCanonicalPath() + File.separator;
                    String dc = dest.getCanonicalPath();
                    if (dc.startsWith(sc)) { reply("550 Cannot move directory into itself"); return; }
                } catch (IOException e) { reply("550 Path resolution failed"); return; }
            }

            File backup = null;
            if (dest.exists()) {
                backup = new File(destParent, ".ftp-backup-" + UUID.randomUUID());
                if (!dest.renameTo(backup)) { reply("550 Target exists and cannot be replaced"); return; }
            }
            if (src.renameTo(dest)) {
                if (backup != null) deleteRecursive(backup);
                appendLog("RNTO：" + src.getName() + " → " + dest.getName());
                reply("250 Rename successful");
            } else {
                if (backup != null && !backup.renameTo(dest))
                    appendLog("RNTO 回滚失败，残留备份：" + backup.getAbsolutePath());
                reply("550 Rename failed");
            }
        }

        // -------------------------------------------------------------------
        // SIZE / MDTM / REST / AVBL / STAT
        // -------------------------------------------------------------------

        private void handleSize(String args) {
            if (args.isEmpty()) { reply("501 No filename"); return; }
            File f = resolvePath(args);
            if (f != null && f.isFile())      reply("213 " + f.length());
            else if (f != null && f.isDirectory()) reply("550 Is a directory");
            else reply("550 No such file");
        }

        private void handleMdtm(String args) {
            if (args.isEmpty()) { reply("501 No filename"); return; }
            File f = resolvePath(args);
            if (f != null && f.exists()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                reply("213 " + sdf.format(new Date(f.lastModified())));
            } else reply("550 No such file");
        }

        private void handleRest(String args) {
            try {
                long pos = Long.parseLong(args.trim());
                if (pos < 0) { reply("501 Invalid position"); return; }
                mRestartPos = pos;
                reply("350 Restart position accepted (" + pos + ")");
            } catch (NumberFormatException e) { reply("501 Invalid position"); }
        }

        private void handleAvbl() {
            try {
                StatFs sf = new StatFs(new File(mCfg.rootDir).getAbsolutePath());
                long avail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
                        ? sf.getAvailableBytes()
                        : (long) sf.getAvailableBlocks() * sf.getBlockSize();
                reply("213 " + avail);
            } catch (Exception e) {
                reply("213 " + new File(mCfg.rootDir).getUsableSpace());
            }
        }

        private void handleStat(String args) {
            if (args.isEmpty()) {
                sendRaw("211-FTP Server Status:\r\n"
                      + " Connected: " + mClientIp + "\r\n"
                      + " Root: /\r\n"
                      + " Working directory: " + mCurrentDir + "\r\n"
                      + " Transfer type: " + mTransferType + "\r\n"
                      + "211 End of status\r\n");
            } else {
                File target = resolvePath(args);
                if (target == null || !target.exists()) { reply("550 No such file or directory"); return; }
                sendRaw("211-Status of " + args + ":\r\n");
                if (target.isDirectory()) {
                    File[] files = target.listFiles();
                    if (files != null) {
                        List<File> sorted = new ArrayList<>(Arrays.asList(files));
                        sorted.sort((a, b) -> {
                            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                            return a.getName().compareToIgnoreCase(b.getName());
                        });
                        for (File f : sorted) sendRaw(" " + buildListLine(f) + "\r\n");
                    }
                } else {
                    sendRaw(" " + buildListLine(target) + "\r\n");
                }
                reply("211 End of status");
            }
        }

        // -------------------------------------------------------------------
        // 路径工具
        // -------------------------------------------------------------------

        @Nullable
        private File resolvePath(@NonNull String ftpPath) {
            String path = ftpPath.trim();
            if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\""))
                path = path.substring(1, path.length() - 1);
            if (path.isEmpty() || ".".equals(path)) path = mCurrentDir;

            String local = path.startsWith("/")
                    ? mCfg.rootDir + path
                    : mCfg.rootDir + "/" + mCurrentDir + "/" + path;
            while (local.contains("//")) local = local.replace("//", "/");

            try {
                String canonical = new File(local).getCanonicalPath();
                String root      = new File(mCfg.rootDir).getCanonicalPath();
                if (!canonical.equals(root) && !canonical.startsWith(root + File.separator)) {
                    Log.w(TAG, "路径越界拒绝：" + ftpPath);
                    return null;
                }
                return new File(canonical);
            } catch (IOException e) { return null; }
        }

        @NonNull
        private String toVirtualPath(@NonNull File file) {
            try {
                String fp = file.getCanonicalPath();
                String rp = new File(mCfg.rootDir).getCanonicalPath();
                if (fp.equals(rp)) return "/";
                if (fp.startsWith(rp + File.separator)) return fp.substring(rp.length());
            } catch (IOException e) { Log.e(TAG, "toVirtualPath", e); }
            return "/";
        }

        // -------------------------------------------------------------------
        // 文件操作工具
        // -------------------------------------------------------------------

        private File createPartFile(@NonNull File target) throws IOException {
            File parent = target.getParentFile();
            if (parent == null || !parent.isDirectory()) throw new IOException("No parent dir");
            File tmp;
            do { tmp = new File(parent, "." + target.getName() + ".ftp-" + UUID.randomUUID() + ".part"); }
            while (tmp.exists());
            if (!tmp.createNewFile()) throw new IOException("Cannot create part file");
            return tmp;
        }

        /**
         * fix: transfer 循环加入 mStopping/mClosed 检查，大文件传输可及时中断
         */
        private void transfer(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) {
                if (mStopping.get() || mClosed) throw new IOException("Transfer interrupted by server stop");
                out.write(buf, 0, n);
            }
        }

        private void transfer(@NonNull InputStream in, @NonNull RandomAccessFile raf) throws IOException {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) {
                if (mStopping.get() || mClosed) throw new IOException("Transfer interrupted by server stop");
                raf.write(buf, 0, n);
            }
        }

        private void copyFile(@NonNull File src, @NonNull File dst) throws IOException {
            try (InputStream i = new FileInputStream(src); OutputStream o = new FileOutputStream(dst, false)) {
                // copyFile 是内部备份操作，不需要检查 stop，直接传完
                byte[] buf = new byte[65536]; int n;
                while ((n = i.read(buf)) != -1) o.write(buf, 0, n);
            }
        }

        /** 原子替换：备份旧文件 → 替换 → 删备份；失败时回滚 */
        private boolean atomicReplace(@NonNull File target, @NonNull File replacement) {
            if (!replacement.exists()) return false;
            if (!target.exists()) return replacement.renameTo(target);
            File backup = new File(target.getParentFile(), ".ftp-backup-" + UUID.randomUUID());
            if (!target.renameTo(backup)) return false;
            if (replacement.renameTo(target)) { deleteRecursive(backup); return true; }
            if (!backup.renameTo(target)) appendLog("原子替换回滚失败：" + target.getAbsolutePath());
            return false;
        }

        private boolean deleteRecursive(@NonNull File f) {
            if (!f.exists()) return true;
            if (f.isDirectory()) {
                File[] c = f.listFiles();
                if (c != null) for (File child : c) if (!deleteRecursive(child)) return false;
            }
            return f.delete();
        }

    } // end ClientSession

} // end FtpServerService
