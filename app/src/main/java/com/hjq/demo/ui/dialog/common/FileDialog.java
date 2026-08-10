package com.hjq.demo.ui.dialog.common;

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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.hjq.base.BaseDialog;
import com.hjq.custom.widget.layout.SimpleLayout;
import com.hjq.demo.R;
import com.hjq.demo.aop.SingleClick;
import com.hjq.demo.app.AppAdapter;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.scwang.smart.refresh.layout.listener.OnRefreshListener;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * author : Android 轮子哥
 * github : https://github.com/getActivity/AndroidProject
 * time   : 2026/02/28
 * desc   : 文件选择对话框
 */
public final class FileDialog {

    private static String TAG = "FileDialog";
    public static final String[] IMAGE_FILTER = new String[]{
            "png", "jpg", "bmp", "jpeg", "gif", "webp"
    };

    public static final String[] TXT_FILTER = new String[]{
            "txt", "c", "cpp", "java", "xml", "json", "md", "log"
    };

    public static final String[] AUDIO_FILTER = new String[]{
            "mp3", "m4a", "wav", "aac", "ogg", "flac"
    };

    public static final String[] VIDEO_FILTER = new String[]{
            "mp4", "avi", "flv", "mkv", "mov", "wmv", "3gp"
    };

    public static final String[] APP_FILTER = new String[]{
            "apk"
    };

    public static final class Builder
            extends BaseDialog.Builder<Builder> {

        public static final int FILTER_TYPE_ALL = 0;
        public static final int FILTER_TYPE_IMAGE = 1;
        public static final int FILTER_TYPE_TXT = 2;
        public static final int FILTER_TYPE_AUDIO = 3;
        public static final int FILTER_TYPE_VIDEO = 4;
        public static final int FILTER_TYPE_APP = 5;
        public static final int FILTER_TYPE_FOLDER = 6; // 文件夹选择模式

        @NonNull
        private final ImageView mCloseView;
        @NonNull
        private final TextView mTitleView;
        @NonNull
        private final View mDeviceLayout;
        @NonNull
        private final SimpleLayout mContentLayout;
        @NonNull
        private final RecyclerView mDeviceRecyclerView;
        @NonNull
        private final RecyclerView mContentRecyclerView;
        @NonNull
        private final TextView mBackView;
        @NonNull
        private final TextView mPathView;
        @NonNull
        private final View mPathLayout;
        @NonNull
        private final SmartRefreshLayout mDeviceRefreshLayout;
        @NonNull
        private final SmartRefreshLayout mContentRefreshLayout;
        @NonNull
        private final View mStorageInfoLayout;
        @NonNull
        private final View mStorageDivider;
        @NonNull
        private final TextView mStorageInfoView;
        @NonNull
        private final android.widget.ProgressBar mStorageProgressBar;
        @NonNull
        private final LinearLayout mFolderConfirmLayout;
        @NonNull
        private final AppCompatButton mFolderConfirmButton;

        @NonNull
        private final DeviceAdapter mDeviceAdapter;
        @NonNull
        private final ContentAdapter mContentAdapter;

        @Nullable
        private OnListener mListener;

        @NonNull
        private List<StorageDevice> mStorageDevices = new ArrayList<>();
        @Nullable
        private StorageDevice mCurrentDevice;
        @Nullable
        private File mCurrentPath;
        @Nullable
        private File mSelectedFile;

        private int mFilterType = FILTER_TYPE_ALL;

        private final StorageBroadcastReceiver mStorageReceiver = new StorageBroadcastReceiver();
        private final UsbBroadcastReceiver mUsbReceiver = new UsbBroadcastReceiver();

        public Builder(@NonNull Context context) {
            super(context);
            setContentView(R.layout.file_dialog);
            setGravity(Gravity.BOTTOM);
            setAnimStyle(BaseDialog.ANIM_BOTTOM);


            mCloseView = findViewById(R.id.iv_file_close);
            mTitleView = findViewById(R.id.tv_file_title);
            mDeviceLayout = findViewById(R.id.sl_file_device_layout);
            mContentLayout = findViewById(R.id.sl_file_content_layout);
            mDeviceRecyclerView = findViewById(R.id.rv_file_device_list);
            mContentRecyclerView = findViewById(R.id.rv_file_content_list);
            mBackView = findViewById(R.id.tv_file_back);
            mPathView = findViewById(R.id.tv_file_path);
            mPathLayout = findViewById(R.id.ll_file_path);
            mDeviceRefreshLayout = findViewById(R.id.srl_file_device_refresh);
            mContentRefreshLayout = findViewById(R.id.srl_file_content_refresh);
            mStorageInfoLayout = findViewById(R.id.ll_storage_info);
            mStorageDivider = findViewById(R.id.v_storage_divider);
            mStorageInfoView = findViewById(R.id.tv_storage_info);
            mStorageProgressBar = findViewById(R.id.pb_storage_usage);
            mFolderConfirmLayout = findViewById(R.id.ll_folder_confirm_layout);
            mFolderConfirmButton = findViewById(R.id.tv_folder_confirm);

            updateTitle();

            setOnClickListener(mCloseView, mBackView, mFolderConfirmButton);

            mDeviceAdapter = new DeviceAdapter(getContext());
            mDeviceAdapter.setOnItemClickListener(this::onDeviceItemClick);
            mDeviceRecyclerView.setAdapter(mDeviceAdapter);

            mContentAdapter = new ContentAdapter(getContext());
            mContentAdapter.setOnItemClickListener(this::onContentItemClick);
            mContentRecyclerView.setAdapter(mContentAdapter);


            // 设置左侧下拉刷新
            mDeviceRefreshLayout.setEnableRefresh(true);
            mDeviceRefreshLayout.setEnableLoadMore(false);
            mDeviceRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
                @Override
                public void onRefresh(RefreshLayout refreshLayout) {
                    loadStorageDevices();
                    mDeviceRefreshLayout.finishRefresh();
                }
            });

            // 设置右侧下拉刷新
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

            loadStorageDevices();

            registerStorageReceiver();
            registerUsbReceiver();
            Log.d("FileDialog", "广播接收器已注册");

            addOnDismissListener(dialog -> {
                Log.d("FileDialog", "对话框关闭，注销广播接收器");
                unregisterStorageReceiver();
                unregisterUsbReceiver();
            });
        }

        public Builder setFilterType(int filterType) {
            mFilterType = filterType;
            updateTitle();
            // 如果已经选择了设备，重新加载文件列表以应用过滤类型
            if (mCurrentDevice != null && mCurrentPath != null) {
                loadContentList(mCurrentPath);
            }
            return this;
        }

        public Builder setListener(@Nullable OnListener listener) {
            mListener = listener;
            return this;
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
                StorageDevice device = new StorageDevice();
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
                StorageManager storageManager =
                        (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);

                if (storageManager == null) {
                    Log.e("FileDialog", "StorageManager为null");
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
                            String label = volume.getDescription(getContext());
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
                            String description = (String) getDescription.invoke(volume, getContext());

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
                Log.e("FileDialog", "获取设备列表失败", e);
                // 如果反射失败，回退到扫描路径
                scanLegacyStoragePaths();
            }
        }

        private void addStorageDevice(File file, String name) {
            StorageDevice device = new StorageDevice();
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

        private void selectDevice(@NonNull StorageDevice device) {
            mCurrentDevice = device;
            mCurrentPath = new File(device.path);
            mSelectedFile = null;
            updateDeviceSelection();
            updateStorageInfo(device);
            loadContentList(mCurrentPath);
        }

        private void updateStorageInfo(@NonNull StorageDevice device) {
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

        private void loadContentList(@NonNull File path) {
            if (!path.exists() || !path.isDirectory()) {
                return;
            }

            mCurrentPath = path;
            mSelectedFile = null;
            mContentAdapter.clearSelection();
            mContentAdapter.notifyDataSetChanged();

            // 根据过滤器类型决定是否显示确认按钮
            if (mFilterType == FILTER_TYPE_FOLDER) {
                mFolderConfirmLayout.setVisibility(View.VISIBLE);
            } else {
                mFolderConfirmLayout.setVisibility(View.GONE);
            }

            updatePathDisplay();
            updateContentList(path);
        }

        private void updateTitle() {
            String title;
            switch (mFilterType) {
                case FILTER_TYPE_FOLDER:
                    title = "请选择文件夹";
                    break;
                case FILTER_TYPE_IMAGE:
                    title = "请选择图片";
                    break;
                case FILTER_TYPE_TXT:
                    title = "请选择文本文件";
                    break;
                case FILTER_TYPE_AUDIO:
                    title = "请选择音频文件";
                    break;
                case FILTER_TYPE_VIDEO:
                    title = "请选择视频文件";
                    break;
                case FILTER_TYPE_APP:
                    title = "请选择应用文件";
                    break;
                case FILTER_TYPE_ALL:
                default:
                    title = "请选择文件";
                    break;
            }
            mTitleView.setText(title);
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
        private void updateContentList(@NonNull File path) {
            List<ContentItem> items = new ArrayList<>();

            File[] files = path.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        ContentItem item = new ContentItem();
                        item.file = file;
                        item.name = file.getName();
                        item.isDirectory = true;
                        item.size = 0;
                        item.lastModified = file.lastModified();
                        items.add(item);
                    } else if (mFilterType != FILTER_TYPE_FOLDER) {
                        // 如果不是文件夹选择模式，才显示文件
                        String extension = getFileExtension(file.getName());
                        boolean isMatch = (mFilterType == FILTER_TYPE_ALL || isFileMatchFilter(extension, mFilterType));
                        if (isMatch) {
                            ContentItem item = new ContentItem();
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
                case FILTER_TYPE_ALL:
                    return true;
                case FILTER_TYPE_IMAGE:
                    return containsIgnoreCase(IMAGE_FILTER, extension);
                case FILTER_TYPE_TXT:
                    return containsIgnoreCase(TXT_FILTER, extension);
                case FILTER_TYPE_AUDIO:
                    return containsIgnoreCase(AUDIO_FILTER, extension);
                case FILTER_TYPE_VIDEO:
                    return containsIgnoreCase(VIDEO_FILTER, extension);
                case FILTER_TYPE_APP:
                    return containsIgnoreCase(APP_FILTER, extension);
                case FILTER_TYPE_FOLDER:
                    return false; // 文件夹选择模式不显示文件
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

        private void onDeviceItemClick(@NonNull RecyclerView recyclerView, @NonNull View itemView, int position) {
            StorageDevice device = mDeviceAdapter.getItem(position);
            if (device != null) {
                selectDevice(device);
            }
        }

        private void onContentItemClick(@NonNull RecyclerView recyclerView, @NonNull View itemView, int position) {
            ContentItem item = mContentAdapter.getItem(position);
            if (item != null && item.file != null) {
                if (item.isDirectory) {
                    // 点击文件夹直接进入
                    loadContentList(item.file);
                } else {
                    mSelectedFile = item.file;
                    mContentAdapter.setSelectedPosition(position);

                    // 点击文件后自动确认并关闭弹框
                    dismiss();
                    if (mListener != null) {
                        mListener.onConfirm(getDialog(), item.file);
                    }
                }
            }
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        private void registerStorageReceiver() {
            IntentFilter filter = new IntentFilter();
            // 存储设备挂载相关广播
            filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            filter.addAction(Intent.ACTION_MEDIA_REMOVED);
            filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
            filter.addAction(Intent.ACTION_MEDIA_EJECT);
            filter.addAction(Intent.ACTION_MEDIA_SHARED);
            filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
            filter.addAction(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            // USB设备相关广播
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            // 自定义USB存储变化广播
            filter.addAction("com.hjq.demo.USB_STORAGE_CHANGED");
            filter.addDataScheme("file");

            try {
                // 在 Android 8.0+ 中，某些隐式广播需要动态注册
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getContext().registerReceiver(mStorageReceiver, filter, Context.RECEIVER_EXPORTED);
                    Log.d("FileDialog", "使用 RECEIVER_EXPORTED 注册广播接收器 (Android 13+)");
                } else {
                    getContext().registerReceiver(mStorageReceiver, filter);
                    Log.d("FileDialog", "使用默认方式注册广播接收器");
                }
                Log.d("FileDialog", "已注册的广播动作: " + filter.toString());
            } catch (Exception e) {
                Log.e("FileDialog", "注册广播接收器失败", e);
            }
        }

        private void unregisterStorageReceiver() {
            try {
                getContext().unregisterReceiver(mStorageReceiver);
                Log.d("FileDialog", "存储广播接收器已注销");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void registerUsbReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getContext().registerReceiver(mUsbReceiver, filter, Context.RECEIVER_EXPORTED);
                    Log.d("FileDialog", "USB广播接收器已注册 (Android 13+)");
                } else {
                    getContext().registerReceiver(mUsbReceiver, filter);
                    Log.d("FileDialog", "USB广播接收器已注册");
                }
            } catch (Exception e) {
                Log.e("FileDialog", "注册USB广播接收器失败", e);
            }
        }

        private void unregisterUsbReceiver() {
            try {
                getContext().unregisterReceiver(mUsbReceiver);
                Log.d("FileDialog", "USB广播接收器已注销");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void refreshDelayed(Runnable action, long delayMillis) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, delayMillis);
        }

        @SingleClick
        @Override
        public void onClick(@NonNull View view) {
            int viewId = view.getId();
            if (viewId == R.id.iv_file_close) {
                dismiss();
                if (mListener != null) {
                    mListener.onCancel(getDialog());
                }
            } else if (viewId == R.id.tv_file_back && mCurrentPath != null) {
                File parent = mCurrentPath.getParentFile();
                if (parent != null) {
                    loadContentList(parent);
                }
            } else if (viewId == R.id.tv_folder_confirm) {
                // 选择当前文件夹
                if (mCurrentPath != null && mCurrentPath.isDirectory()) {
                    dismiss();
                    if (mListener != null) {
                        mListener.onConfirm(getDialog(), mCurrentPath);
                    }
                }
            }
        }


        private final class StorageBroadcastReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }

                Log.d("FileDialog", "接收到广播: " + action);

                // 存储设备变化广播
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
                    Log.d("FileDialog", "触发设备变化处理");
                    handleStorageChange(action);
                }
            }
        }

        /**
         * USB 设备广播接收器（动态注册）
         */
        private final class UsbBroadcastReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }

                String action = intent.getAction();
                Log.d("FileDialog", "接收到USB广播: " + action);

                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    Log.d("FileDialog", "USB设备已连接");
                    // USB设备插入后，延迟刷新设备列表，等待系统挂载完成
                    refreshDelayed(() -> loadStorageDevices(), 1000 * 5);

                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    Log.d("FileDialog", "USB设备已断开");
                    // 立即刷新设备列表
                    handleStorageChange(action);
                }
            }
        }

        /**
         * 处理存储设备变化
         */
        private void handleStorageChange(String action) {
            Log.d("FileDialog", "存储设备变化: " + action);

            // 记录当前设备路径，用于后续判断
            String currentPathBeforeUpdate = (mCurrentDevice != null) ? mCurrentDevice.path : null;

            // 重新加载设备列表
            loadStorageDevices();

            // 检查当前设备是否还存在
            if (currentPathBeforeUpdate != null) {
                boolean currentDeviceStillExists = false;
                for (StorageDevice device : mStorageDevices) {
                    if (currentPathBeforeUpdate.equals(device.path)) {
                        currentDeviceStillExists = true;
                        break;
                    }
                }

                // 如果当前设备已被移除，切换到其他可用设备
                if (!currentDeviceStillExists) {
                    if (!mStorageDevices.isEmpty()) {
                        StorageDevice newDevice = mStorageDevices.get(0);
                        Log.d("FileDialog", "当前设备已移除，切换到: " + newDevice.name);
                        selectDevice(newDevice);
                    } else {
                        // 没有可用设备
                        Log.d("FileDialog", "没有可用的存储设备");
                        mCurrentDevice = null;
                        mCurrentPath = null;
                        mContentAdapter.clearData();
                        updatePathDisplay();
                    }
                } else {
                    // 当前设备还在，但可能路径已不可用（如U盘被拔出后未卸载）
                    if (mCurrentPath != null && !mCurrentPath.exists()) {
                        Log.d("FileDialog", "当前路径不存在，切换到根目录");
                        if (mCurrentDevice != null) {
                            mCurrentPath = new File(mCurrentDevice.path);
                            loadContentList(mCurrentPath);
                        }
                    }
                }
            }

            // 更新设备选中状态
            updateDeviceSelection();
        }
    }

    private static final class DeviceAdapter extends AppAdapter<StorageDevice> {

        private int mSelectedPosition = -1;

        private DeviceAdapter(@NonNull Context context) {
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

            private final ImageView mIconView;
            private final TextView mNameView;

            DeviceViewHolder() {
                super(R.layout.file_device_item);
                mIconView = findViewById(R.id.iv_file_device_icon);
                mNameView = findViewById(R.id.tv_file_device_name);
            }

            @Override
            public void onBindView(int position) {
                StorageDevice device = getItem(position);
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

    private static final class ContentAdapter extends AppAdapter<ContentItem> {

        private int mSelectedPosition = -1;

        private ContentAdapter(@NonNull Context context) {
            super(context);
        }

        private void clearSelection() {
            mSelectedPosition = -1;
        }

        private void setSelectedPosition(int position) {
            mSelectedPosition = position;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ContentViewHolder();
        }

        private final class ContentViewHolder extends AppViewHolder {

            private final ImageView mIconView;
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
                ContentItem item = getItem(position);
                if (item == null) {
                    return;
                }

                mNameView.setText(item.name);

                boolean isSelected = (position == mSelectedPosition);

                int iconRes = getFileIcon(item);
                mIconView.setImageResource(iconRes);

                if (!item.isDirectory) {
                    mSizeView.setVisibility(View.VISIBLE);
                    mSizeView.setText(formatFileSize(item.size));
                } else {
                    mSizeView.setVisibility(View.GONE);
                }

                if (isSelected) {
                    mRootView.setBackgroundResource(R.drawable.file_item_selected_bg);
                } else {
                    mRootView.setBackground(null);
                }
            }

            private int getFileIcon(ContentItem item) {
                if (item.isDirectory) {
                    return R.drawable.file_folder;
                }
                String extension = item.extension;
                if (TextUtils.isEmpty(extension)) {
                    return R.drawable.file_unknown;
                }
                if (containsIgnoreCase(IMAGE_FILTER, extension)) {
                    return R.drawable.file_image;
                } else if (containsIgnoreCase(TXT_FILTER, extension)) {
                    return R.drawable.file_text;
                } else if (containsIgnoreCase(AUDIO_FILTER, extension)) {
                    return R.drawable.file_audio;
                } else if (containsIgnoreCase(VIDEO_FILTER, extension)) {
                    return R.drawable.file_video;
                } else if (containsIgnoreCase(APP_FILTER, extension)) {
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

    public static class StorageDevice implements Serializable {
        public String path;
        public String name;
        public long totalSpace;
        public long freeSpace;
    }

    public static class ContentItem implements Serializable {
        public File file;
        public String name;
        public boolean isDirectory;
        public long size;
        public long lastModified;
        public String extension;
    }

    public interface OnListener {

        void onConfirm(@NonNull BaseDialog dialog, File file);

        default void onCancel(@NonNull BaseDialog dialog) {
        }
    }
}
