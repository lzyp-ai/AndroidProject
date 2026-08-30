package com.hjq.demo.ui.activity;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hjq.bar.TitleBar;
import com.hjq.demo.R;
import com.hjq.demo.app.AppActivity;
import com.hjq.demo.app.AppAdapter;
import com.hjq.demo.aop.SingleClick;
import com.hjq.demo.http.glide.GlideApp;
import com.hjq.demo.permission.PermissionDescription;
import com.hjq.demo.permission.PermissionInterceptor;
import com.hjq.demo.ui.activity.common.AudioPlayerActivity;
import com.hjq.demo.ui.activity.common.ImageViewerActivity;
import com.hjq.demo.ui.activity.common.TextViewerActivity;
import com.hjq.demo.ui.activity.common.VideoPlayActivity;
import com.hjq.demo.ui.dialog.AudioPlayerDialog;
import com.hjq.demo.ui.dialog.common.FileDialog;
import com.hjq.demo.ui.dialog.common.InputDialog;
import com.hjq.demo.ui.dialog.common.MenuDialog;
import com.hjq.demo.ui.dialog.common.MessageDialog;
import com.hjq.permissions.XXPermissions;
import com.hjq.permissions.permission.PermissionLists;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.scwang.smart.refresh.layout.listener.OnRefreshListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * author : Android 轮子哥
 * github : https://github.com/getActivity/AndroidProject
 * time   : 2026/03/02
 * desc   : 文件管理页面
 */
public final class FileManagerActivity extends AppActivity {

    private static final String TAG = "FileManagerActivity";

    // -----------------------------------------------------------------------
    // 剪切板操作类型
    // -----------------------------------------------------------------------
    private static final int CLIPBOARD_NONE = 0;
    private static final int CLIPBOARD_COPY = 1;
    private static final int CLIPBOARD_CUT  = 2;

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------
    private TitleBar mTitleBar;

    private LinearLayout mDeviceLayout;
    private SmartRefreshLayout mDeviceRefreshLayout;
    private RecyclerView mDeviceRecyclerView;
    private View mStorageInfoLayout;
    private View mStorageDivider;
    private TextView mStorageInfoView;
    private android.widget.ProgressBar mStorageProgressBar;

    private RecyclerView mContentRecyclerView;
    private SmartRefreshLayout mContentRefreshLayout;
    private LinearLayout mPathLayout;
    private TextView mPathView;
    private FloatingActionButton mFab;

    // -----------------------------------------------------------------------
    // Adapters / Data
    // -----------------------------------------------------------------------
    private DeviceAdapter mDeviceAdapter;
    private ContentAdapter mContentAdapter;

    private List<FileDialog.StorageDevice> mStorageDevices = new ArrayList<>();
    private FileDialog.StorageDevice mCurrentDevice;
    private File mCurrentPath;

    private int mFilterType = FileDialog.Builder.FILTER_TYPE_ALL;

    // 剪切板
    private File mClipboardFile = null;
    private int mClipboardMode = CLIPBOARD_NONE;

    // -----------------------------------------------------------------------
    // Receivers
    // -----------------------------------------------------------------------
    private final StorageBroadcastReceiver mStorageReceiver = new StorageBroadcastReceiver();
    private final UsbBroadcastReceiver mUsbReceiver = new UsbBroadcastReceiver();

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected int getLayoutId() {
        return R.layout.activity_file_manager;
    }

    @Override
    protected void initView() {
        mTitleBar = findViewById(R.id.tb_file_manager_title);
        mTitleBar.setTitle("文件管理");

        mDeviceLayout = findViewById(R.id.ll_file_manager_device);
        mDeviceRefreshLayout = findViewById(R.id.srl_file_manager_device_refresh);
        mDeviceRecyclerView = findViewById(R.id.rv_file_manager_device_list);
        mStorageInfoLayout = findViewById(R.id.ll_file_manager_storage_info);
        mStorageDivider = findViewById(R.id.v_file_manager_storage_divider);
        mStorageInfoView = findViewById(R.id.tv_file_manager_storage_info);
        mStorageProgressBar = findViewById(R.id.pb_file_manager_storage_usage);

        mContentRecyclerView = findViewById(R.id.rv_file_manager_content_list);
        mContentRefreshLayout = findViewById(R.id.srl_file_manager_content_refresh);
        mPathLayout = findViewById(R.id.ll_file_manager_path);
        mPathView = findViewById(R.id.tv_file_manager_path);
        mFab = findViewById(R.id.fab_file_manager_new);

        // 设备列表
        mDeviceAdapter = new DeviceAdapter(this);
        mDeviceAdapter.setOnItemClickListener(this::onDeviceItemClick);
        mDeviceRecyclerView.setAdapter(mDeviceAdapter);

        // 文件列表
        mContentAdapter = new ContentAdapter(this);
        mContentAdapter.setOnItemClickListener(this::onContentItemClick);
        mContentRecyclerView.setAdapter(mContentAdapter);
        mContentRecyclerView.addItemDecoration(new com.hjq.demo.widget.FileListDivider(this));

        // 下拉刷新 - 设备
        mDeviceRefreshLayout.setEnableRefresh(true);
        mDeviceRefreshLayout.setEnableLoadMore(false);
        mDeviceRefreshLayout.setOnRefreshListener(refreshLayout -> {
            loadStorageDevices();
            mDeviceRefreshLayout.finishRefresh();
        });

        // 下拉刷新 - 文件
        mContentRefreshLayout.setEnableRefresh(true);
        mContentRefreshLayout.setEnableLoadMore(false);
        mContentRefreshLayout.setOnRefreshListener(refreshLayout -> {
            if (mCurrentPath != null) {
                loadContentList(mCurrentPath);
            }
            mContentRefreshLayout.finishRefresh();
        });

        // FAB 新建
        mFab.setOnClickListener(v -> showNewItemMenu());
    }

    @Override
    protected void initData() {
        checkStoragePermissionAndLoad();
        registerStorageReceiver();
        registerUsbReceiver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterStorageReceiver();
        unregisterUsbReceiver();
    }

    @SingleClick
    @Override
    public void onClick(View view) {
        // 其余点击由各子 View 自行处理
    }

    @Override
    public void onRightClick(@NonNull com.hjq.bar.TitleBar titleBar) {
        // 右侧按钮：打开 FTP 服务管理页
        com.hjq.demo.ui.activity.common.FtpServerActivity.start(this);
    }

    // -----------------------------------------------------------------------
    // 权限与存储设备加载
    // -----------------------------------------------------------------------

    private void checkStoragePermissionAndLoad() {
        if (XXPermissions.isGrantedPermission(this, PermissionLists.getManageExternalStoragePermission())) {
            loadStorageDevices();
        } else {
            XXPermissions.with(this)
                    .permission(PermissionLists.getManageExternalStoragePermission())
                    .interceptor(new PermissionInterceptor())
                    .description(new PermissionDescription())
                    .request((grantedList, deniedList) -> {
                        if (deniedList.isEmpty()) {
                            loadStorageDevices();
                        } else {
                            toast("未授予存储权限，无法读取文件");
                        }
                    });
        }
    }

    private void loadStorageDevices() {
        mStorageDevices.clear();
        addInternalStorage();
        addOtherStorageDevices();
        if (!mStorageDevices.isEmpty()) {
            mDeviceAdapter.setData(mStorageDevices);
            selectDevice(mStorageDevices.get(0));
        }
    }

    private void addInternalStorage() {
        File internalStorage = new File("/storage/emulated/0");
        if (internalStorage.exists() && internalStorage.isDirectory()) {
            FileDialog.StorageDevice device = new FileDialog.StorageDevice();
            device.path = "/storage/emulated/0";
            device.name = "内部存储";
            device.totalSpace = getStorageTotalSpace(device.path);
            device.freeSpace = getStorageFreeSpace(device.path);
            mStorageDevices.add(device);
        }
    }

    @SuppressLint({"NewApi", "DiscouragedPrivateApi"})
    private void addOtherStorageDevices() {
        try {
            StorageManager storageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (storageManager == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                for (StorageVolume volume : storageManager.getStorageVolumes()) {
                    File file = volume.getDirectory();
                    if (file == null) continue;
                    if (volume.isRemovable()) {
                        addStorageDevice(file, volume.getDescription(this));
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                java.lang.reflect.Method getVolumeList = storageManager.getClass().getMethod("getVolumeList");
                Object[] volumes = (Object[]) getVolumeList.invoke(storageManager);
                if (volumes != null) {
                    for (Object volume : volumes) {
                        java.lang.reflect.Method getPath = volume.getClass().getMethod("getPath");
                        java.lang.reflect.Method isRemovable = volume.getClass().getMethod("isRemovable");
                        java.lang.reflect.Method getDesc = volume.getClass().getMethod("getDescription", Context.class);
                        String path = (String) getPath.invoke(volume);
                        boolean removable = (boolean) isRemovable.invoke(volume);
                        String desc = (String) getDesc.invoke(volume, this);
                        if (removable && path != null) {
                            File file = new File(path);
                            if (file.exists()) addStorageDevice(file, desc);
                        }
                    }
                }
            } else {
                scanLegacyStoragePaths();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取设备列表失败", e);
            scanLegacyStoragePaths();
        }
    }

    private void addStorageDevice(File file, String name) {
        FileDialog.StorageDevice device = new FileDialog.StorageDevice();
        device.path = file.getAbsolutePath();
        device.name = name != null ? name : "外部存储";
        device.totalSpace = file.getTotalSpace();
        device.freeSpace = file.getUsableSpace();
        mStorageDevices.add(device);
    }

    private void scanLegacyStoragePaths() {
        String[] paths = {"/storage/sdcard1", "/storage/ext_sd", "/storage/extsdcard",
                "/storage/external_SD", "/storage/microsd", "/storage/removable/sdcard1",
                "/mnt/sdcard/external_sd", "/mnt/external_sd", "/mnt/sdcard/ext_sd",
                "/mnt/sdcard2", "/mnt/extSdCard"};
        for (String path : paths) {
            File file = new File(path);
            if (file.exists() && file.isDirectory() && file.canRead()) {
                addStorageDevice(file, "SD卡");
            }
        }
    }

    private long getStorageTotalSpace(String path) {
        try {
            return new StatFs(path).getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    private long getStorageFreeSpace(String path) {
        try {
            return new StatFs(path).getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // 设备选择 & 文件列表加载
    // -----------------------------------------------------------------------

    private void selectDevice(FileDialog.StorageDevice device) {
        mCurrentDevice = device;
        mCurrentPath = new File(device.path);
        updateDeviceSelection();
        updateStorageInfo(device);
        loadContentList(mCurrentPath);
    }

    private void updateStorageInfo(FileDialog.StorageDevice device) {
        mStorageInfoLayout.setVisibility(View.VISIBLE);
        mStorageDivider.setVisibility(View.VISIBLE);
        long usedSpace = device.totalSpace - device.freeSpace;
        int pct = device.totalSpace > 0 ? (int) ((usedSpace * 100) / device.totalSpace) : 0;
        mStorageInfoView.setText(String.format("已用 %s / 总计 %s",
                formatFileSize(usedSpace), formatFileSize(device.totalSpace)));
        mStorageProgressBar.setProgress(pct);
    }

    private void updateDeviceSelection() {
        int idx = -1;
        for (int i = 0; i < mStorageDevices.size(); i++) {
            if (mStorageDevices.get(i) == mCurrentDevice) {
                idx = i;
                break;
            }
        }
        mDeviceAdapter.setSelectedPosition(idx);
    }

    private void loadContentList(File path) {
        if (!path.exists() || !path.isDirectory()) {
            mContentRecyclerView.setVisibility(View.GONE);
            return;
        }
        mCurrentPath = path;
        updatePathDisplay();
        updateContentList(path);
    }

    private void updatePathDisplay() {
        if (mCurrentPath == null) {
            mPathLayout.setVisibility(View.GONE);
            return;
        }
        mPathView.setText(mCurrentPath.getAbsolutePath());
        mPathLayout.setVisibility(View.VISIBLE);
    }

    @SuppressLint("NewApi")
    private void updateContentList(File path) {
        List<FileDialog.ContentItem> items = new ArrayList<>();

        boolean isAtDeviceRoot = mCurrentDevice != null &&
                mCurrentPath.getAbsolutePath().equals(mCurrentDevice.path);

        if (!isAtDeviceRoot && path.getParent() != null) {
            FileDialog.ContentItem parentItem = new FileDialog.ContentItem();
            parentItem.name = "...";
            parentItem.isDirectory = true;
            parentItem.file = path.getParentFile();
            parentItem.isParentFolder = true;
            items.add(parentItem);
        }

        File[] files = path.listFiles();
        if (files != null) {
            for (File file : files) {
                FileDialog.ContentItem item = new FileDialog.ContentItem();
                item.file = file;
                item.name = file.getName();
                item.isDirectory = file.isDirectory();
                item.size = file.isDirectory() ? 0 : file.length();
                item.lastModified = file.lastModified();
                if (!file.isDirectory()) {
                    item.extension = getFileExtension(file.getName());
                }
                items.add(item);
            }
        }

        items.sort((a, b) -> {
            if (a.isParentFolder) return -1;
            if (b.isParentFolder) return 1;
            if (a.isDirectory && !b.isDirectory) return -1;
            if (!a.isDirectory && b.isDirectory) return 1;
            return a.name.compareToIgnoreCase(b.name);
        });

        mContentRecyclerView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mContentAdapter.setData(items);
        // 切换目录后滚回顶部
        mContentRecyclerView.scrollToPosition(0);
    }

    // -----------------------------------------------------------------------
    // 点击事件
    // -----------------------------------------------------------------------

    private void onDeviceItemClick(RecyclerView recyclerView, View itemView, int position) {
        FileDialog.StorageDevice device = mDeviceAdapter.getItem(position);
        if (device != null) selectDevice(device);
    }

    private void onContentItemClick(RecyclerView recyclerView, View itemView, int position) {
        FileDialog.ContentItem item = mContentAdapter.getItem(position);
        if (item == null || item.file == null) return;
        if (item.isParentFolder || item.isDirectory) {
            loadContentList(item.file);
        } else {
            openFile(item.file);
        }
    }

    // -----------------------------------------------------------------------
    // FAB：新建文件夹 / 新建文件
    // -----------------------------------------------------------------------

    private void showNewItemMenu() {
        if (mCurrentPath == null) {
            toast("请先选择一个目录");
            return;
        }
        // 动态构建菜单列表，避免传入 null 导致 MenuDialog NPE
        List<String> menuItems = new ArrayList<>();
        menuItems.add("新建文件夹");
        menuItems.add("新建文件");
        if (mClipboardFile != null) {
            menuItems.add("粘贴");
        }
        new MenuDialog.Builder(this)
                .setList(menuItems.toArray(new String[0]))
                .setListener(new MenuDialog.OnListener<String>() {
                    @Override
                    public void onSelected(@NonNull com.hjq.base.BaseDialog dialog, int position, String data) {
                        if ("新建文件夹".equals(data)) {
                            showCreateFolderDialog();
                        } else if ("新建文件".equals(data)) {
                            showCreateFileDialog();
                        } else if ("粘贴".equals(data)) {
                            pasteFile();
                        }
                    }
                })
                .show();
    }

    private void showCreateFolderDialog() {
        new InputDialog.Builder(this)
                .setTitle("新建文件夹")
                .setHint("请输入文件夹名称")
                .setListener(new InputDialog.OnListener() {
                    @Override
                    public void onConfirm(@NonNull com.hjq.base.BaseDialog dialog, String content) {
                        if (TextUtils.isEmpty(content.trim())) {
                            toast("文件夹名称不能为空");
                            return;
                        }
                        File newFolder = new File(mCurrentPath, content.trim());
                        if (newFolder.exists()) {
                            toast("已存在同名文件夹");
                            return;
                        }
                        if (newFolder.mkdirs()) {
                            toast("文件夹已创建");
                            loadContentList(mCurrentPath);
                        } else {
                            toast("创建失败，请检查权限");
                        }
                    }
                })
                .show();
    }

    private void showCreateFileDialog() {
        new InputDialog.Builder(this)
                .setTitle("新建文件")
                .setHint("请输入文件名（含扩展名）")
                .setListener(new InputDialog.OnListener() {
                    @Override
                    public void onConfirm(@NonNull com.hjq.base.BaseDialog dialog, String content) {
                        if (TextUtils.isEmpty(content.trim())) {
                            toast("文件名不能为空");
                            return;
                        }
                        File newFile = new File(mCurrentPath, content.trim());
                        if (newFile.exists()) {
                            toast("已存在同名文件");
                            return;
                        }
                        try {
                            if (newFile.createNewFile()) {
                                toast("文件已创建");
                                loadContentList(mCurrentPath);
                            } else {
                                toast("创建失败");
                            }
                        } catch (IOException e) {
                            toast("创建失败：" + e.getMessage());
                        }
                    }
                })
                .show();
    }

    // -----------------------------------------------------------------------
    // 三点菜单：操作单个文件/文件夹
    // -----------------------------------------------------------------------

    private void showItemOperationMenu(FileDialog.ContentItem item) {
        if (item == null || item.file == null) return;

        List<String> menuItems = new ArrayList<>();
        menuItems.add("重命名");
        menuItems.add("删除");
        menuItems.add("复制");
        menuItems.add("剪切");
        if (!item.isDirectory) {
            if ("apk".equalsIgnoreCase(item.extension)) {
                menuItems.add("安装 APK");
            }
        }
        // 如果有剪切板内容且当前是文件夹，显示粘贴
        if (mClipboardFile != null) {
            menuItems.add("粘贴到此目录");
        }

        new MenuDialog.Builder(this)
                .setList(menuItems.toArray(new String[0]))
                .setListener(new MenuDialog.OnListener<String>() {
                    @Override
                    public void onSelected(@NonNull com.hjq.base.BaseDialog dialog, int position, String data) {
                        switch (data) {
                            case "重命名":
                                showRenameDialog(item);
                                break;
                            case "删除":
                                showDeleteConfirmDialog(item);
                                break;
                            case "复制":
                                mClipboardFile = item.file;
                                mClipboardMode = CLIPBOARD_COPY;
                                toast("已复制：" + item.name);
                                break;
                            case "剪切":
                                mClipboardFile = item.file;
                                mClipboardMode = CLIPBOARD_CUT;
                                toast("已剪切：" + item.name);
                                break;
                            case "安装 APK":
                                installApk(item.file);
                                break;
                            case "分享":
                                shareFile(item.file);
                                break;
                            case "粘贴到此目录":
                                pasteFile();
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    // -----------------------------------------------------------------------
    // 重命名
    // -----------------------------------------------------------------------

    private void showRenameDialog(FileDialog.ContentItem item) {
        new InputDialog.Builder(this)
                .setTitle("重命名")
                .setHint("请输入新名称")
                .setContent(item.name)
                .setListener(new InputDialog.OnListener() {
                    @Override
                    public void onConfirm(@NonNull com.hjq.base.BaseDialog dialog, String content) {
                        if (TextUtils.isEmpty(content.trim())) {
                            toast("名称不能为空");
                            return;
                        }
                        String newName = content.trim();
                        if (newName.equals(item.name)) return;
                        File dest = new File(item.file.getParentFile(), newName);
                        if (dest.exists()) {
                            toast("已存在同名文件/文件夹");
                            return;
                        }
                        if (item.file.renameTo(dest)) {
                            toast("重命名成功");
                            loadContentList(mCurrentPath);
                        } else {
                            toast("重命名失败，请检查权限");
                        }
                    }
                })
                .show();
    }

    // -----------------------------------------------------------------------
    // 删除
    // -----------------------------------------------------------------------

    private void showDeleteConfirmDialog(FileDialog.ContentItem item) {
        String msg = item.isDirectory
                ? "确定要删除文件夹「" + item.name + "」及其所有内容吗？"
                : "确定要删除文件「" + item.name + "」吗？";

        new MessageDialog.Builder(this)
                .setTitle("删除确认")
                .setMessage(msg)
                .setConfirm("删除")
                .setCancel("取消")
                .setListener(new MessageDialog.OnListener() {
                    @Override
                    public void onConfirm(@NonNull com.hjq.base.BaseDialog dialog) {
                        if (deleteFileOrDirectory(item.file)) {
                            toast("删除成功");
                            loadContentList(mCurrentPath);
                        } else {
                            toast("删除失败，请检查权限");
                        }
                    }
                })
                .show();
    }

    /**
     * 递归删除文件或文件夹
     */
    private boolean deleteFileOrDirectory(File file) {
        if (file == null || !file.exists()) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteFileOrDirectory(child)) return false;
                }
            }
        }
        return file.delete();
    }

    // -----------------------------------------------------------------------
    // 复制 / 剪切 / 粘贴
    // -----------------------------------------------------------------------

    private void pasteFile() {
        if (mClipboardFile == null || mCurrentPath == null) return;
        if (!mClipboardFile.exists()) {
            toast("源文件不存在");
            mClipboardFile = null;
            mClipboardMode = CLIPBOARD_NONE;
            return;
        }

        File dest = resolveDestFile(mCurrentPath, mClipboardFile.getName());
        boolean success;
        if (mClipboardFile.isDirectory()) {
            success = copyDirectory(mClipboardFile, dest);
        } else {
            success = copyFile(mClipboardFile, dest);
        }

        if (success) {
            if (mClipboardMode == CLIPBOARD_CUT) {
                deleteFileOrDirectory(mClipboardFile);
            }
            mClipboardFile = null;
            mClipboardMode = CLIPBOARD_NONE;
            toast("粘贴成功");
            loadContentList(mCurrentPath);
        } else {
            toast("粘贴失败，请检查权限");
        }
    }

    /**
     * 如果目标文件已存在，自动追加序号，例如 "abc (1).txt"
     */
    private File resolveDestFile(File destDir, String name) {
        File dest = new File(destDir, name);
        if (!dest.exists()) return dest;
        int dotIdx = name.lastIndexOf('.');
        String baseName = dotIdx >= 0 ? name.substring(0, dotIdx) : name;
        String ext = dotIdx >= 0 ? name.substring(dotIdx) : "";
        int i = 1;
        while (dest.exists()) {
            dest = new File(destDir, baseName + " (" + i + ")" + ext);
            i++;
        }
        return dest;
    }

    private boolean copyFile(File src, File dest) {
        try {
            if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
            try (FileChannel in = new FileInputStream(src).getChannel();
                 FileChannel out = new FileOutputStream(dest).getChannel()) {
                out.transferFrom(in, 0, in.size());
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "复制文件失败", e);
            return false;
        }
    }

    private boolean copyDirectory(File src, File dest) {
        if (!dest.exists() && !dest.mkdirs()) return false;
        File[] children = src.listFiles();
        if (children == null) return true;
        for (File child : children) {
            File destChild = new File(dest, child.getName());
            if (child.isDirectory()) {
                if (!copyDirectory(child, destChild)) return false;
            } else {
                if (!copyFile(child, destChild)) return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // 打开文件
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // 打开文件（按类型路由到内置 Activity）
    // -----------------------------------------------------------------------

    private void openFile(File file) {
        String ext = getFileExtension(file.getName());

        // APK → 安装
        if ("apk".equalsIgnoreCase(ext)) {
            installApk(file);
            return;
        }

        // 图片 → 内置图片查看器
        if (containsIgnoreCase(FileDialog.IMAGE_FILTER, ext)) {
            ImageViewerActivity.start(this, file);
            return;
        }

        // 视频 → 内置视频播放器
        if (containsIgnoreCase(FileDialog.VIDEO_FILTER, ext)) {
            new VideoPlayActivity.Builder()
                    .setVideoSource(file)
                    .start(this);
            return;
        }

        // 音频 → 底部播放控制弹框
        if (containsIgnoreCase(FileDialog.AUDIO_FILTER, ext)) {
            new AudioPlayerDialog.Builder(this)
                    .setFile(file)
                    .show();
            return;
        }

        // 文本 → 内置文本查看器
        if (containsIgnoreCase(FileDialog.TXT_FILTER, ext)) {
            TextViewerActivity.start(this, file);
            return;
        }

        // 其他类型 → 弹出提示，无法在应用内打开
        toast("暂不支持预览该类型文件：." + ext);
    }

    /**
     * 安装 APK
     */
    private void installApk(File file) {
        try {
            Uri uri;
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".provider", file);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(file);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("安装失败：" + e.getMessage());
        }
    }

    /**
     * 分享文件
     */
    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(getMimeType(file));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "分享文件"));
        } catch (Exception e) {
            toast("分享失败：" + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    private String getMimeType(File file) {
        String ext = getFileExtension(file.getName());
        if (TextUtils.isEmpty(ext)) return "*/*";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.getDefault()));
        return mime != null ? mime : "*/*";
    }

    private String getFileExtension(String fileName) {
        if (TextUtils.isEmpty(fileName)) return "";
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex == -1 ? "" : fileName.substring(dotIndex + 1).toLowerCase(Locale.getDefault());
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0));
        return String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private boolean containsIgnoreCase(String[] array, String value) {
        for (String item : array) {
            if (item.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // 广播接收器
    // -----------------------------------------------------------------------

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStorageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_SHARED);
        filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        filter.addAction(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addDataScheme("file");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mStorageReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(mStorageReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(TAG, "注册广播接收器失败", e);
        }
    }

    private void unregisterStorageReceiver() {
        try { unregisterReceiver(mStorageReceiver); } catch (Exception ignored) {}
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mUsbReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(mUsbReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(TAG, "注册USB广播接收器失败", e);
        }
    }

    private void unregisterUsbReceiver() {
        try { unregisterReceiver(mUsbReceiver); } catch (Exception ignored) {}
    }

    private void handleStorageChange(String action) {
        String currentPath = (mCurrentDevice != null) ? mCurrentDevice.path : null;
        loadStorageDevices();
        if (currentPath != null) {
            boolean stillExists = false;
            for (FileDialog.StorageDevice d : mStorageDevices) {
                if (currentPath.equals(d.path)) { stillExists = true; break; }
            }
            if (!stillExists) {
                if (!mStorageDevices.isEmpty()) {
                    selectDevice(mStorageDevices.get(0));
                } else {
                    mCurrentDevice = null;
                    mCurrentPath = null;
                    mContentAdapter.clearData();
                    updatePathDisplay();
                    mContentRecyclerView.setVisibility(View.GONE);
                }
            } else if (mCurrentPath != null && !mCurrentPath.exists()) {
                if (mCurrentDevice != null) {
                    mCurrentPath = new File(mCurrentDevice.path);
                    loadContentList(mCurrentPath);
                }
            }
        }
        updateDeviceSelection();
    }

    private final class StorageBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_MOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_REMOVED.equals(action)
                    || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)
                    || Intent.ACTION_MEDIA_EJECT.equals(action)
                    || Intent.ACTION_MEDIA_SHARED.equals(action)
                    || Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)
                    || Intent.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(action)) {
                handleStorageChange(action);
            }
        }
    }

    private final class UsbBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> loadStorageDevices(), 5000);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                handleStorageChange(action);
            }
        }
    }

    // -----------------------------------------------------------------------
    // DeviceAdapter
    // -----------------------------------------------------------------------

    private static final class DeviceAdapter extends AppAdapter<FileDialog.StorageDevice> {

        private int mSelectedPosition = -1;

        private DeviceAdapter(Context context) {
            super(context);
        }

        void setSelectedPosition(int position) {
            mSelectedPosition = position;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new DeviceViewHolder();
        }

        private final class DeviceViewHolder extends AppViewHolder {
            private final AppCompatImageView mIconView;
            private final TextView mNameView;

            DeviceViewHolder() {
                super(R.layout.file_device_item);
                mIconView = findViewById(R.id.iv_file_device_icon);
                mNameView = findViewById(R.id.tv_file_device_name);
            }

            @Override
            public void onBindView(int position) {
                FileDialog.StorageDevice device = getItem(position);
                if (device == null) return;
                mNameView.setText(device.name);
                boolean selected = (position == mSelectedPosition);
                int color = selected ? R.color.common_confirm_text_color : R.color.common_text_hint_color;
                mNameView.setTextColor(ContextCompat.getColor(getContext(), color));
                int iconRes = device.name.contains("内部存储") ? R.drawable.file_interior : R.drawable.file_sd;
                mIconView.setImageResource(iconRes);
                if (selected) {
                    itemView.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.common_confirm_text_color));
                    itemView.getBackground().setAlpha(20);
                } else {
                    itemView.setBackground(null);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // ContentAdapter
    // -----------------------------------------------------------------------

    private final class ContentAdapter extends AppAdapter<FileDialog.ContentItem> {

        private ContentAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ContentViewHolder();
        }

        private final class ContentViewHolder extends AppViewHolder {

            private final AppCompatImageView mIconView;
            private final TextView mNameView;
            private final TextView mInfoView;
            private final AppCompatImageView mMoreView;

            ContentViewHolder() {
                super(R.layout.file_content_item);
                mIconView = findViewById(R.id.iv_file_content_icon);
                mNameView = findViewById(R.id.tv_file_content_name);
                mInfoView = findViewById(R.id.tv_file_content_info);
                mMoreView = findViewById(R.id.iv_file_content_more);
            }

            @Override
            public void onBindView(int position) {
                FileDialog.ContentItem item = getItem(position);
                if (item == null) return;

                mNameView.setText(item.name);

                // 图片文件：加载缩略图，失败则用保底图标
                if (!item.isDirectory && !item.isParentFolder
                        && containsIgnoreCase(FileDialog.IMAGE_FILTER, item.extension)) {
                     GlideApp.with(getContext())
                            .load(item.file)
                            .placeholder(R.drawable.file_image)
                            .error(R.drawable.file_image)
                            .centerCrop()
                            .override(120, 120)
                            .into(mIconView);
                } else {
                    mIconView.setImageResource(getFileIcon(item));
                }

                if (item.isParentFolder) {
                    mInfoView.setText("");
                    mMoreView.setVisibility(View.GONE);
                } else if (item.isDirectory) {
                    int count = getFolderItemCount(item.file);
                    mInfoView.setText(formatDate(item.lastModified) + " | " + count + " 项");
                    mMoreView.setVisibility(View.VISIBLE);
                } else {
                    mInfoView.setText(formatDate(item.lastModified) + " | " + formatFileSize(item.size));
                    mMoreView.setVisibility(View.VISIBLE);
                }

                // 三点按钮点击触发操作菜单
                mMoreView.setOnClickListener(v -> showItemOperationMenu(item));
            }

            private int getFolderItemCount(File folder) {
                if (folder == null || !folder.isDirectory()) return 0;
                File[] files = folder.listFiles();
                return files != null ? files.length : 0;
            }

            private String formatDate(long timestamp) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new java.util.Date(timestamp));
            }

            private String formatFileSize(long size) {
                return FileManagerActivity.this.formatFileSize(size);
            }

            private int getFileIcon(FileDialog.ContentItem item) {
                if (item.isDirectory) return R.drawable.file_folder;
                String ext = item.extension;
                if (TextUtils.isEmpty(ext)) return R.drawable.file_unknown;
                if (containsIgnoreCase(FileDialog.IMAGE_FILTER, ext)) return R.drawable.file_image;
                if (containsIgnoreCase(FileDialog.TXT_FILTER, ext)) return R.drawable.file_text;
                if (containsIgnoreCase(FileDialog.AUDIO_FILTER, ext)) return R.drawable.file_audio;
                if (containsIgnoreCase(FileDialog.VIDEO_FILTER, ext)) return R.drawable.file_video;
                if (containsIgnoreCase(FileDialog.APP_FILTER, ext)) return R.drawable.file_app;
                return R.drawable.file_unknown;
            }
        }
    }
}
