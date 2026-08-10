package com.hjq.demo.ui.activity;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.hjq.bar.TitleBar;
import com.hjq.demo.R;
import com.hjq.demo.aop.SingleClick;
import com.hjq.demo.app.AppActivity;
import com.hjq.demo.app.AppAdapter;
import com.hjq.demo.permission.PermissionDescription;
import com.hjq.demo.permission.PermissionInterceptor;
import com.hjq.demo.ui.dialog.common.FileDialog;
import com.hjq.permissions.XXPermissions;
import com.hjq.permissions.permission.PermissionLists;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.scwang.smart.refresh.layout.listener.OnRefreshListener;

import java.io.File;
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
    
    private TitleBar mTitleBar;
    
    // 左侧设备列表
    private LinearLayout mDeviceLayout;
    private SmartRefreshLayout mDeviceRefreshLayout;
    private RecyclerView mDeviceRecyclerView;
    private View mStorageInfoLayout;
    private View mStorageDivider;
    private TextView mStorageInfoView;
    private android.widget.ProgressBar mStorageProgressBar;
    
    // 右侧文件列表
    private RecyclerView mContentRecyclerView;
    private SmartRefreshLayout mContentRefreshLayout;
    private LinearLayout mPathLayout;
    private TextView mBackView;
    private TextView mPathView;
    
    private DeviceAdapter mDeviceAdapter;
    private ContentAdapter mContentAdapter;
    
    private List<FileDialog.StorageDevice> mStorageDevices = new ArrayList<>();
    private FileDialog.StorageDevice mCurrentDevice;
    private File mCurrentPath;
    
    private int mFilterType = FileDialog.Builder.FILTER_TYPE_ALL;
    
    private final StorageBroadcastReceiver mStorageReceiver = new StorageBroadcastReceiver();
    private final UsbBroadcastReceiver mUsbReceiver = new UsbBroadcastReceiver();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_file_manager;
    }

    @Override
    protected void initView() {
        mTitleBar = findViewById(R.id.tb_file_manager_title);
        // 设置标题栏
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
        mBackView = findViewById(R.id.tv_file_manager_back);
        mPathView = findViewById(R.id.tv_file_manager_path);
        
        setOnClickListener(mBackView);
        
        // 初始化设备列表
        mDeviceAdapter = new DeviceAdapter(this);
        mDeviceAdapter.setOnItemClickListener(this::onDeviceItemClick);
        mDeviceRecyclerView.setAdapter(mDeviceAdapter);
        
        // 初始化文件列表
        mContentAdapter = new ContentAdapter(this);
        mContentAdapter.setOnItemClickListener(this::onContentItemClick);
        mContentRecyclerView.setAdapter(mContentAdapter);
        
        // 设置下拉刷新
        mDeviceRefreshLayout.setEnableRefresh(true);
        mDeviceRefreshLayout.setEnableLoadMore(false);
        mDeviceRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(RefreshLayout refreshLayout) {
                loadStorageDevices();
                mDeviceRefreshLayout.finishRefresh();
            }
        });
        
        mContentRefreshLayout.setEnableRefresh(true);
        mContentRefreshLayout.setEnableLoadMore(false);
        mContentRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(RefreshLayout refreshLayout) {
                if (mCurrentPath != null) {
                    loadContentList(mCurrentPath);
                }
                mContentRefreshLayout.finishRefresh();
            }
        });

    }

    @Override
    protected void initData() {
        checkStoragePermissionAndLoad();
        registerStorageReceiver();
        registerUsbReceiver();
    }

    private void checkStoragePermissionAndLoad() {
        if (XXPermissions.isGrantedPermission(this, PermissionLists.getManageExternalStoragePermission())) {
            // 已有权限，直接加载
            loadStorageDevices();
        } else {
            // 没有权限，申请完再加载
            XXPermissions.with(this)
                    .permission(PermissionLists.getManageExternalStoragePermission())
                    .interceptor(new PermissionInterceptor())
                    .description(new PermissionDescription())
                    .request((grantedList, deniedList) -> {
                        if (deniedList.isEmpty()) {
                            // 权限授予成功后再加载，确保存储卷对当前进程可见
                            loadStorageDevices();
                        } else {
                            toast("未授予存储权限，无法读取文件");
                        }
                    });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterStorageReceiver();
        unregisterUsbReceiver();
    }



    private void loadStorageDevices() {
        Log.d(TAG, "loadStorageDevices: ===================");
        mStorageDevices.clear();

        // 添加内部存储
        addInternalStorage();

        // 添加其他存储设备（SD卡等）
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
            if (storageManager == null) {
                Log.e(TAG, "StorageManager为null");
                return;
            }

            // Android 9 (API 28) 兼容方案
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ 使用 getStorageVolumes()
                List<StorageVolume> volumes = storageManager.getStorageVolumes();
                for (StorageVolume volume : volumes) {
                    File file = volume.getDirectory();
                    if (file == null) continue;

                    if (volume.isRemovable()) {
                        String label = volume.getDescription(this);
                        addStorageDevice(file, label);
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0-10 使用反射获取存储卷
                java.lang.reflect.Method getVolumeList = storageManager.getClass().getMethod("getVolumeList");
                Object[] volumes = (Object[]) getVolumeList.invoke(storageManager);

                if (volumes != null) {
                    for (Object volume : volumes) {
                        java.lang.reflect.Method getPath = volume.getClass().getMethod("getPath");
                        java.lang.reflect.Method isRemovable = volume.getClass().getMethod("isRemovable");
                        java.lang.reflect.Method getDescription = volume.getClass().getMethod("getDescription", Context.class);

                        String path = (String) getPath.invoke(volume);
                        boolean removable = (boolean) isRemovable.invoke(volume);
                        String description = (String) getDescription.invoke(volume, this);

                        if (removable && path != null) {
                            File file = new File(path);
                            if (file.exists()) {
                                addStorageDevice(file, description);
                            }
                        }
                    }
                }
            } else {
                // Android 6.0 以下，扫描常见的外部存储路径
                scanLegacyStoragePaths();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取设备列表失败", e);
            // 如果反射失败，回退到扫描路径
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
        // 扫描常见的外部存储路径（兼容旧版本）
        String[] legacyPaths = {
                "/storage/sdcard1",
                "/storage/ext_sd",
                "/storage/extsdcard",
                "/storage/external_SD",
                "/storage/microsd",
                "/storage/removable/sdcard1",
                "/mnt/sdcard/external_sd",
                "/mnt/external_sd",
                "/mnt/sdcard/ext_sd",
                "/mnt/sdcard2",
                "/mnt/extSdCard"
        };

        for (String path : legacyPaths) {
            File file = new File(path);
            if (file.exists() && file.isDirectory() && file.canRead()) {
                addStorageDevice(file, "SD卡");
            }
        }
    }

    private long getStorageTotalSpace(String path) {
        StatFs statFs = new StatFs(path);
        try {
            return statFs.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    private long getStorageFreeSpace(String path) {
        StatFs statFs = new StatFs(path);
        try {
            return statFs.getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

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
        int usagePercentage = device.totalSpace > 0 ? (int) ((usedSpace * 100) / device.totalSpace) : 0;
        
        String storageInfo = String.format("已用 %s / 总计 %s",
                formatFileSize(usedSpace), formatFileSize(device.totalSpace));
        mStorageInfoView.setText(storageInfo);
        
        mStorageProgressBar.setProgress(usagePercentage);
    }

    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private void updateDeviceSelection() {
        int selectedIndex = -1;
        for (int i = 0; i < mStorageDevices.size(); i++) {
            if (mStorageDevices.get(i) == mCurrentDevice) {
                selectedIndex = i;
                break;
            }
        }
        mDeviceAdapter.setSelectedPosition(selectedIndex);
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
        
        String path = mCurrentPath.getAbsolutePath();
        mPathView.setText(path);
        
        boolean canBack = mCurrentPath.getParent() != null;
        mPathLayout.setVisibility(canBack ? View.VISIBLE : View.GONE);
    }

    @SuppressLint("NewApi")
    private void updateContentList(File path) {
        List<FileDialog.ContentItem> items = new ArrayList<>();
        
        File[] files = path.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    FileDialog.ContentItem item = new FileDialog.ContentItem();
                    item.file = file;
                    item.name = file.getName();
                    item.isDirectory = true;
                    item.size = 0;
                    item.lastModified = file.lastModified();
                    items.add(item);
                } else {
                    String extension = getFileExtension(file.getName());
                    boolean isMatch = (mFilterType == FileDialog.Builder.FILTER_TYPE_ALL || 
                            isFileMatchFilter(extension, mFilterType));
                    if (isMatch) {
                        FileDialog.ContentItem item = new FileDialog.ContentItem();
                        item.file = file;
                        item.name = file.getName();
                        item.isDirectory = false;
                        item.size = file.length();
                        item.lastModified = file.lastModified();
                        item.extension = extension;
                        items.add(item);
                    }
                }
            }
        }
        
        // 排序：先文件夹后文件，各自按名称排序
        items.sort((item1, item2) -> {
            if (item1.isDirectory && !item2.isDirectory) {
                return -1;
            }
            if (!item1.isDirectory && item2.isDirectory) {
                return 1;
            }
            return item1.name.compareToIgnoreCase(item2.name);
        });
        
        if (items.isEmpty()) {
            mContentRecyclerView.setVisibility(View.GONE);
        } else {
            mContentRecyclerView.setVisibility(View.VISIBLE);
        }
        
        mContentAdapter.setData(items);
    }

    private String getFileExtension(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1).toLowerCase(Locale.getDefault());
    }

    private boolean isFileMatchFilter(String extension, int filterType) {
        if (TextUtils.isEmpty(extension)) {
            return false;
        }
        
        switch (filterType) {
            case FileDialog.Builder.FILTER_TYPE_ALL:
                return true;
            case FileDialog.Builder.FILTER_TYPE_IMAGE:
                return containsIgnoreCase(FileDialog.IMAGE_FILTER, extension);
            case FileDialog.Builder.FILTER_TYPE_TXT:
                return containsIgnoreCase(FileDialog.TXT_FILTER, extension);
            case FileDialog.Builder.FILTER_TYPE_AUDIO:
                return containsIgnoreCase(FileDialog.AUDIO_FILTER, extension);
            case FileDialog.Builder.FILTER_TYPE_VIDEO:
                return containsIgnoreCase(FileDialog.VIDEO_FILTER, extension);
            case FileDialog.Builder.FILTER_TYPE_APP:
                return containsIgnoreCase(FileDialog.APP_FILTER, extension);
            default:
                return false;
        }
    }

    private boolean containsIgnoreCase(String[] array, String value) {
        for (String item : array) {
            if (item.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private void onDeviceItemClick(RecyclerView recyclerView, View itemView, int position) {
        FileDialog.StorageDevice device = mDeviceAdapter.getItem(position);
        if (device != null) {
            selectDevice(device);
        }
    }

    private void onContentItemClick(RecyclerView recyclerView, View itemView, int position) {
        FileDialog.ContentItem item = mContentAdapter.getItem(position);
        if (item != null && item.file != null) {
            if (item.isDirectory) {
                // 点击文件夹进入
                loadContentList(item.file);
            } else {
                // 点击文件打开
                openFile(item.file);
            }
        }
    }

    private void openFile(File file) {
        // 这里可以实现文件打开逻辑
        toast("打开文件: " + file.getAbsolutePath());
    }

    @SingleClick
    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.tv_file_manager_back && mCurrentPath != null) {
            File parent = mCurrentPath.getParentFile();
            if (parent != null) {
                loadContentList(parent);
            }
        }
    }

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
        filter.addAction("com.hjq.demo.USB_STORAGE_CHANGED");
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
        try {
            unregisterReceiver(mStorageReceiver);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
        try {
            unregisterReceiver(mUsbReceiver);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void refreshDelayed(Runnable action, long delayMillis) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, delayMillis);
    }

    private void handleStorageChange(String action) {
        Log.d(TAG, "存储设备变化: " + action);
        
        String currentPathBeforeUpdate = (mCurrentDevice != null) ? mCurrentDevice.path : null;
        
        loadStorageDevices();
        
        if (currentPathBeforeUpdate != null) {
            boolean currentDeviceStillExists = false;
            for (FileDialog.StorageDevice device : mStorageDevices) {
                if (currentPathBeforeUpdate.equals(device.path)) {
                    currentDeviceStillExists = true;
                    break;
                }
            }
            
            if (!currentDeviceStillExists) {
                if (!mStorageDevices.isEmpty()) {
                    FileDialog.StorageDevice newDevice = mStorageDevices.get(0);
                    selectDevice(newDevice);
                } else {
                    mCurrentDevice = null;
                    mCurrentPath = null;
                    mContentAdapter.clearData();
                    updatePathDisplay();
                    mContentRecyclerView.setVisibility(View.GONE);
                }
            } else {
                if (mCurrentPath != null && !mCurrentPath.exists()) {
                    if (mCurrentDevice != null) {
                        mCurrentPath = new File(mCurrentDevice.path);
                        loadContentList(mCurrentPath);
                    }
                }
            }
        }
        
        updateDeviceSelection();
    }

    private final class StorageBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            
            boolean isStorageBroadcast =
                    Intent.ACTION_MEDIA_MOUNTED.equals(action) ||
                    Intent.ACTION_MEDIA_UNMOUNTED.equals(action) ||
                    Intent.ACTION_MEDIA_REMOVED.equals(action) ||
                    Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action) ||
                    Intent.ACTION_MEDIA_EJECT.equals(action) ||
                    Intent.ACTION_MEDIA_SHARED.equals(action) ||
                    Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action) ||
                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(action);
            
            if (isStorageBroadcast) {
                handleStorageChange(action);
            }
        }
    }

    private final class UsbBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                refreshDelayed(() -> loadStorageDevices(), 1000 * 5);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                handleStorageChange(action);
            }
        }
    }

    private static final class DeviceAdapter extends AppAdapter<FileDialog.StorageDevice> {
        
        private int mSelectedPosition = -1;
        
        private DeviceAdapter(Context context) {
            super(context);
        }
        
        private void setSelectedPosition(int position) {
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
                if (device == null) {
                    return;
                }
                
                mNameView.setText(device.name);
                
                boolean isSelected = (position == mSelectedPosition);
                int bgColor = isSelected ?
                        R.color.common_confirm_text_color :
                        R.color.common_text_hint_color;
                mNameView.setTextColor(ContextCompat.getColor(getContext(), bgColor));
                
                int iconRes = device.name.contains("内部存储") ? R.drawable.file_interior : R.drawable.file_sd;
                mIconView.setImageResource(iconRes);
                
                if (isSelected) {
                    itemView.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.common_confirm_text_color));
                    itemView.getBackground().setAlpha(20);
                } else {
                    itemView.setBackground(null);
                }
            }
        }
    }

    private static final class ContentAdapter extends AppAdapter<FileDialog.ContentItem> {
        
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
            private final TextView mSizeView;
            private final LinearLayout mRootView;
            
            ContentViewHolder() {
                super(R.layout.file_content_item);
                mIconView = findViewById(R.id.iv_file_content_icon);
                mNameView = findViewById(R.id.tv_file_content_name);
                mSizeView = findViewById(R.id.tv_file_content_size);
                mRootView = findViewById(R.id.ll_file_content_root);
            }
            
            @Override
            public void onBindView(int position) {
                FileDialog.ContentItem item = getItem(position);
                if (item == null) {
                    return;
                }
                
                mNameView.setText(item.name);
                
                int iconRes = getFileIcon(item);
                mIconView.setImageResource(iconRes);
                
                if (!item.isDirectory) {
                    mSizeView.setVisibility(View.VISIBLE);
                    mSizeView.setText(formatFileSize(item.size));
                } else {
                    mSizeView.setVisibility(View.GONE);
                }
                
                mRootView.setBackground(null);
            }
            
            private int getFileIcon(FileDialog.ContentItem item) {
                if (item.isDirectory) {
                    return R.drawable.file_folder;
                }
                String extension = item.extension;
                if (TextUtils.isEmpty(extension)) {
                    return R.drawable.file_unknown;
                }
                if (containsIgnoreCase(FileDialog.IMAGE_FILTER, extension)) {
                    return R.drawable.file_image;
                } else if (containsIgnoreCase(FileDialog.TXT_FILTER, extension)) {
                    return R.drawable.file_text;
                } else if (containsIgnoreCase(FileDialog.AUDIO_FILTER, extension)) {
                    return R.drawable.file_audio;
                } else if (containsIgnoreCase(FileDialog.VIDEO_FILTER, extension)) {
                    return R.drawable.file_video;
                } else if (containsIgnoreCase(FileDialog.APP_FILTER, extension)) {
                    return R.drawable.file_app;
                } else {
                    return R.drawable.file_unknown;
                }
            }
            
            private boolean containsIgnoreCase(String[] array, String value) {
                for (String item : array) {
                    if (item.equalsIgnoreCase(value)) {
                        return true;
                    }
                }
                return false;
            }
            
            private String formatFileSize(long size) {
                if (size < 1024) {
                    return size + " B";
                } else if (size < 1024 * 1024) {
                    return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
                } else if (size < 1024 * 1024 * 1024) {
                    return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0));
                } else {
                    return String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
                }
            }
        }
    }
}