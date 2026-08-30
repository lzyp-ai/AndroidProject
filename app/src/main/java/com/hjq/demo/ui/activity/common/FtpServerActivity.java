package com.hjq.demo.ui.activity.common;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.hjq.bar.TitleBar;
import com.hjq.demo.R;
import com.hjq.demo.aop.SingleClick;
import com.hjq.demo.app.AppActivity;
import com.hjq.demo.ftp.FtpServerService;
import com.hjq.demo.ui.dialog.common.InputDialog;
import com.hjq.demo.ui.dialog.common.MessageDialog;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * author : Android 轮子哥
 * desc   : FTP 服务端管理界面
 *
 * 修复清单：
 *  - [fix] startFtpServer() 补上缺失的 anonymousWrite 参数，消除编译错误
 *  - [fix] 增加 anonymousWrite 配置项（独立 Switch）及持久化
 *  - [fix] 注册 OnStateListener，由 Service 状态回调驱动 UI 刷新，不再盲目 postDelayed
 *  - [fix] startFtpServer() 去掉延迟 bindService 逻辑，BIND_AUTO_CREATE 已保证连接
 *  - [fix] initView 里的匿名开关联动与 refreshUi 统一，不再重复设置 enabled
 *  - [fix] appendLog 调用路径已在 Service 侧 post 到主线程，Activity 侧不重复 post
 */
public final class FtpServerActivity extends AppActivity {

    // -----------------------------------------------------------------------
    // 配置 Key（MMKV）
    // -----------------------------------------------------------------------
    private static final String KEY_PORT           = "ftp_port";
    private static final String KEY_USERNAME       = "ftp_username";
    private static final String KEY_PASSWORD       = "ftp_password";
    private static final String KEY_ROOT_DIR       = "ftp_root_dir";
    private static final String KEY_ANONYMOUS      = "ftp_anonymous";
    private static final String KEY_ANONYMOUS_WRITE = "ftp_anonymous_write";

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------
    private View     mStatusDot;
    private TextView mTvStatus;
    private TextView mTvAddress;
    private TextView mTvPort;
    private TextView mTvUsername;
    private TextView mTvPassword;
    private TextView mTvRootDir;
    private TextView             mTvLog;
    private android.widget.ScrollView mLogScrollView;
    private Switch   mSwAnonymous;
    private Switch   mSwAnonymousWrite;
    private View     mBtnToggle;
    private View     mBtnCopyAddress;
    private View     mBtnChangePort;
    private View     mBtnChangeUsername;
    private View     mBtnChangePassword;
    private View     mBtnChangeRoot;
    private View     mBtnClearLog;

    // -----------------------------------------------------------------------
    // 当前配置（内存缓存）
    // -----------------------------------------------------------------------
    private int     mPort           = FtpServerService.DEFAULT_PORT;
    private String  mUsername       = FtpServerService.DEFAULT_USERNAME;
    private String  mPassword       = "";
    private String  mRootDir        = FtpServerService.DEFAULT_ROOT_DIR;
    private boolean mAnonymous      = true;
    private boolean mAnonymousWrite = true; // 默认匿名用户可写

    // -----------------------------------------------------------------------
    // Service 绑定
    // -----------------------------------------------------------------------
    private FtpServerService mService;
    private boolean          mBound = false;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((FtpServerService.LocalBinder) binder).getService();
            mBound   = true;
            // fix: 同时注册日志和状态回调，状态变化由 Service 主动通知
            mService.setOnLogListener(line -> appendLog(line));
            mService.setOnStateListener((state, detail) -> refreshUi());
            refreshUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound   = false;
            mService = null;
            refreshUi();
        }
    };

    // -----------------------------------------------------------------------
    // 日志内容（内存维护）
    // -----------------------------------------------------------------------
    private final StringBuilder mLogBuffer   = new StringBuilder();
    private static final int    MAX_LOG_LINES = 200;
    private int                 mLogLineCount = 0;

    // -----------------------------------------------------------------------
    // 静态启动方法
    // -----------------------------------------------------------------------
    public static void start(@NonNull Context context) {
        context.startActivity(new Intent(context, FtpServerActivity.class));
    }

    // -----------------------------------------------------------------------
    // Activity 生命周期
    // -----------------------------------------------------------------------

    @Override
    protected int getLayoutId() {
        return R.layout.ftp_server_activity;
    }

    @Override
    protected void initView() {
        TitleBar titleBar = findViewById(R.id.tb_ftp_title);
        titleBar.setTitle(R.string.ftp_title);
        titleBar.setOnTitleBarListener(this);

        mStatusDot        = findViewById(R.id.v_ftp_status_dot);
        mTvStatus         = findViewById(R.id.tv_ftp_status);
        mTvAddress        = findViewById(R.id.tv_ftp_address);
        mTvPort           = findViewById(R.id.tv_ftp_port);
        mTvUsername       = findViewById(R.id.tv_ftp_username);
        mTvPassword       = findViewById(R.id.tv_ftp_password);
        mTvRootDir        = findViewById(R.id.tv_ftp_root_dir);
        mTvLog            = findViewById(R.id.tv_ftp_log);
        mLogScrollView    = (android.widget.ScrollView) mTvLog.getParent();
        mSwAnonymous      = findViewById(R.id.sw_ftp_anonymous);
        mSwAnonymousWrite = findViewById(R.id.sw_ftp_anonymous_write);
        mBtnToggle        = findViewById(R.id.btn_ftp_toggle);
        mBtnCopyAddress   = findViewById(R.id.btn_ftp_copy_address);
        mBtnChangePort    = findViewById(R.id.btn_ftp_change_port);
        mBtnChangeUsername = findViewById(R.id.btn_ftp_change_username);
        mBtnChangePassword = findViewById(R.id.btn_ftp_change_password);
        mBtnChangeRoot    = findViewById(R.id.btn_ftp_change_root);
        mBtnClearLog      = findViewById(R.id.btn_ftp_clear_log);

        mBtnToggle.setOnClickListener(this);
        mBtnCopyAddress.setOnClickListener(this);
        mBtnChangePort.setOnClickListener(this);
        mBtnChangeUsername.setOnClickListener(this);
        mBtnChangePassword.setOnClickListener(this);
        mBtnChangeRoot.setOnClickListener(this);
        mBtnClearLog.setOnClickListener(this);

        // fix: 匿名开关联动只更新配置变量，enabled 状态统一由 refreshUi() 管理
        mSwAnonymous.setOnCheckedChangeListener((btn, checked) -> {
            mAnonymous = checked;
            refreshConfigDisplay();
        });

        mSwAnonymousWrite.setOnCheckedChangeListener((btn, checked) -> {
            mAnonymousWrite = checked;
        });
    }

    @Override
    protected void initData() {
        loadConfig();
        refreshConfigDisplay();

        // BIND_AUTO_CREATE：Service 已启动就绑，未启动不创建（不影响 start 逻辑）
        Intent intent = new Intent(this, FtpServerService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBound) {
            if (mService != null) {
                mService.setOnLogListener(null);
                mService.setOnStateListener(null); // fix: 同时清除状态监听器
            }
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    public void onLeftClick(@NonNull TitleBar titleBar) {
        finish();
    }

    // -----------------------------------------------------------------------
    // 点击事件
    // -----------------------------------------------------------------------

    @SingleClick
    @Override
    public void onClick(View view) {
        int id = view.getId();
        if      (id == R.id.btn_ftp_toggle)          onToggleClick();
        else if (id == R.id.btn_ftp_copy_address)    onCopyAddressClick();
        else if (id == R.id.btn_ftp_change_port)     onChangePortClick();
        else if (id == R.id.btn_ftp_change_username) onChangeUsernameClick();
        else if (id == R.id.btn_ftp_change_password) onChangePasswordClick();
        else if (id == R.id.btn_ftp_change_root)     onChangeRootClick();
        else if (id == R.id.btn_ftp_clear_log)       clearLog();
    }

    // -----------------------------------------------------------------------
    // 启动 / 停止 FTP 服务
    // -----------------------------------------------------------------------

    private void onToggleClick() {
        if (isServiceRunning()) {
            new MessageDialog.Builder(this)
                    .setTitle(R.string.ftp_stop_confirm_title)
                    .setMessage(R.string.ftp_stop_confirm_msg)
                    .setConfirm(R.string.ftp_btn_stop)
                    .setCancel(R.string.common_cancel)
                    .setListener(dialog -> stopFtpServer())
                    .show();
        } else {
            startFtpServer();
        }
    }

    private void startFtpServer() {
        if (mPort < 1024 || mPort > 65535) {
            toast(R.string.ftp_port_invalid);
            return;
        }
        java.io.File rootFile = new java.io.File(mRootDir);
        if (!rootFile.exists() || !rootFile.isDirectory()) {
            toast(R.string.ftp_root_dir_invalid);
            return;
        }

        // fix: 传入全部 7 个参数，包含 anonymousWrite
        FtpServerService.start(this, mPort, mUsername, mPassword, mRootDir,
                mAnonymous, mAnonymousWrite);

        // fix: 不再延迟重新 bindService。
        // Service 已在 initData 里绑定（BIND_AUTO_CREATE），启动后 onServiceConnected
        // 会触发，若已绑定则 OnStateListener 回调会驱动 refreshUi()。
        toast(R.string.ftp_starting);
    }

    private void stopFtpServer() {
        FtpServerService.stop(this);
        // fix: 不再 postDelayed 刷新 UI；OnStateListener("STOPPED") 回调会触发 refreshUi()
    }

    // -----------------------------------------------------------------------
    // 配置修改
    // -----------------------------------------------------------------------

    private void onChangePortClick() {
        if (isServiceRunning()) { toast(R.string.ftp_config_change_while_running); return; }
        new InputDialog.Builder(this)
                .setTitle(R.string.ftp_config_port)
                .setHint(getString(R.string.ftp_port_hint))
                .setContent(String.valueOf(mPort))
                .setListener((dialog, input) -> {
                    try {
                        int port = Integer.parseInt(input.trim());
                        if (port < 1024 || port > 65535) { toast(R.string.ftp_port_invalid); return; }
                        mPort = port;
                        saveConfig();
                        refreshConfigDisplay();
                    } catch (NumberFormatException e) {
                        toast(R.string.ftp_port_invalid);
                    }
                }).show();
    }

    private void onChangeUsernameClick() {
        if (isServiceRunning()) { toast(R.string.ftp_config_change_while_running); return; }
        new InputDialog.Builder(this)
                .setTitle(R.string.ftp_config_username)
                .setHint(getString(R.string.ftp_username_hint))
                .setContent(mUsername)
                .setListener((dialog, input) -> {
                    String name = input.trim();
                    if (TextUtils.isEmpty(name)) { toast(R.string.ftp_username_empty); return; }
                    mUsername = name;
                    saveConfig();
                    refreshConfigDisplay();
                }).show();
    }

    private void onChangePasswordClick() {
        if (isServiceRunning()) { toast(R.string.ftp_config_change_while_running); return; }
        new InputDialog.Builder(this)
                .setTitle(R.string.ftp_config_password)
                .setHint(getString(R.string.ftp_password_hint))
                .setContent(mPassword)
                .setListener((dialog, input) -> {
                    mPassword = input.trim();
                    saveConfig();
                    refreshConfigDisplay();
                }).show();
    }

    private void onChangeRootClick() {
        if (isServiceRunning()) { toast(R.string.ftp_config_change_while_running); return; }
        new InputDialog.Builder(this)
                .setTitle(R.string.ftp_config_root_dir)
                .setHint(getString(R.string.ftp_root_dir_hint))
                .setContent(mRootDir)
                .setListener((dialog, input) -> {
                    String path = input.trim();
                    if (TextUtils.isEmpty(path)) { toast(R.string.ftp_root_dir_empty); return; }
                    java.io.File dir = new java.io.File(path);
                    if (!dir.exists() || !dir.isDirectory()) { toast(R.string.ftp_root_dir_invalid); return; }
                    mRootDir = path;
                    saveConfig();
                    refreshConfigDisplay();
                }).show();
    }

    private void onCopyAddressClick() {
        String address = buildFtpAddress();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.ftp_address_placeholder))) {
            toast(R.string.ftp_copy_no_address); return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ftp_address", address));
            toast(R.string.ftp_copy_success);
        }
    }

    // -----------------------------------------------------------------------
    // UI 刷新
    // -----------------------------------------------------------------------

    private void refreshUi() {
        boolean running = isServiceRunning();

        // 状态点颜色
        mStatusDot.setBackground(getResources().getDrawable(
                running ? R.drawable.ftp_status_dot_running
                        : R.drawable.ftp_status_dot_stopped, null));

        // 状态文字
        mTvStatus.setText(running ? R.string.ftp_status_running : R.string.ftp_status_stopped);

        // 启动/停止按钮
        TextView btnToggle = (TextView) mBtnToggle;
        if (running) {
            btnToggle.setText(R.string.ftp_btn_stop);
            btnToggle.setBackgroundResource(R.drawable.ftp_btn_stop_bg);
        } else {
            btnToggle.setText(R.string.ftp_btn_start);
            btnToggle.setBackgroundResource(R.drawable.ftp_btn_start_bg);
        }

        // FTP 访问地址
        mTvAddress.setText(running ? buildFtpAddress() : getString(R.string.ftp_address_placeholder));

        // fix: 所有 enabled 状态统一在此管理，不在 Switch 回调里重复设置
        boolean editable    = !running;
        boolean authEditable = editable && !mAnonymous;
        mBtnChangePort.setEnabled(editable);
        mBtnChangeRoot.setEnabled(editable);
        mBtnChangeUsername.setEnabled(authEditable);
        mBtnChangePassword.setEnabled(authEditable);
        mSwAnonymous.setEnabled(editable);
        mSwAnonymousWrite.setEnabled(editable && mAnonymous); // 只有匿名开启时才有意义
        mTvUsername.setAlpha(authEditable ? 1.0f : 0.4f);
        mTvPassword.setAlpha(authEditable ? 1.0f : 0.4f);
    }

    /**
     * 更新配置项显示文字和开关状态，不影响 enabled，enabled 由 refreshUi 统一处理。
     */
    private void refreshConfigDisplay() {
        mTvPort.setText(String.valueOf(mPort));
        mTvUsername.setText(mAnonymous ? getString(R.string.ftp_config_anonymous) : mUsername);
        mTvPassword.setText(TextUtils.isEmpty(mPassword)
                ? getString(R.string.ftp_password_none)
                : "******");
        mTvRootDir.setText(mRootDir);

        // 设置开关状态时先摘掉监听器，避免触发递归刷新
        mSwAnonymous.setOnCheckedChangeListener(null);
        mSwAnonymousWrite.setOnCheckedChangeListener(null);
        mSwAnonymous.setChecked(mAnonymous);
        mSwAnonymousWrite.setChecked(mAnonymousWrite);
        mSwAnonymous.setOnCheckedChangeListener((btn, checked) -> {
            mAnonymous = checked;
            refreshConfigDisplay();
            refreshUi();
        });
        mSwAnonymousWrite.setOnCheckedChangeListener((btn, checked) -> {
            mAnonymousWrite = checked;
            saveConfig();
        });

        // 刷新 UI 中各控件的 enabled 与 alpha
        refreshUi();
    }

    // -----------------------------------------------------------------------
    // 日志操作
    // -----------------------------------------------------------------------

    private void appendLog(@NonNull String line) {
        // OnLogListener 已在 Service 侧 post 到主线程，此处直接操作 UI
        if (mLogLineCount >= MAX_LOG_LINES) {
            String full = mLogBuffer.toString();
            String[] lines = full.split("\n");
            mLogBuffer.setLength(0);
            int start = lines.length / 2;
            for (int i = start; i < lines.length; i++) mLogBuffer.append(lines[i]).append("\n");
            mLogLineCount = lines.length - start;
        }
        mLogBuffer.append(line).append("\n");
        mLogLineCount++;
        mTvLog.setText(mLogBuffer.toString());
        // 新内容追加后滚动到底部：让外层 ScrollView 滚到最大值
        mLogScrollView.post(() -> mLogScrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN));
    }

    private void clearLog() {
        mLogBuffer.setLength(0);
        mLogLineCount = 0;
        mTvLog.setText(R.string.ftp_log_empty);
    }

    // -----------------------------------------------------------------------
    // 配置持久化（MMKV）
    // -----------------------------------------------------------------------

    private void loadConfig() {
        com.tencent.mmkv.MMKV kv = com.tencent.mmkv.MMKV.defaultMMKV();
        mPort           = kv.decodeInt(KEY_PORT, FtpServerService.DEFAULT_PORT);
        mUsername       = kv.decodeString(KEY_USERNAME, FtpServerService.DEFAULT_USERNAME);
        mPassword       = kv.decodeString(KEY_PASSWORD, "");
        mRootDir        = kv.decodeString(KEY_ROOT_DIR, FtpServerService.DEFAULT_ROOT_DIR);
        mAnonymous      = kv.decodeBool(KEY_ANONYMOUS, true);
        mAnonymousWrite = kv.decodeBool(KEY_ANONYMOUS_WRITE, true); // 默认匿名可写
    }

    private void saveConfig() {
        com.tencent.mmkv.MMKV kv = com.tencent.mmkv.MMKV.defaultMMKV();
        kv.encode(KEY_PORT, mPort);
        kv.encode(KEY_USERNAME, mUsername);
        kv.encode(KEY_PASSWORD, mPassword);
        kv.encode(KEY_ROOT_DIR, mRootDir);
        kv.encode(KEY_ANONYMOUS, mAnonymous);
        kv.encode(KEY_ANONYMOUS_WRITE, mAnonymousWrite); // fix: 保存新字段
    }

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    private boolean isServiceRunning() {
        return mBound && mService != null && mService.isRunning();
    }

    @NonNull
    private String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                int ip = info.getIpAddress();
                if (ip != 0) return String.format(java.util.Locale.US, "%d.%d.%d.%d",
                        ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        } catch (Exception ignored) {}
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address)
                        return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    @NonNull
    private String buildFtpAddress() {
        return "ftp://" + getLocalIpAddress() + ":" + mPort;
    }
}
