package com.hjq.demo.ui.activity.common;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.hjq.bar.TitleBar;
import com.hjq.demo.R;
import com.hjq.demo.app.AppActivity;
import com.hjq.demo.ui.adapter.common.TabAdapter;
import com.hjq.demo.ui.dialog.common.InputDialog;
import com.hjq.demo.ui.dialog.common.MessageDialog;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * desc   : Chrome 风格下载列表页（下载中 / 已完成）
 */
public final class ChromeDownloadActivity extends AppActivity {

    public static void start(@NonNull Context context) {
        context.startActivity(new Intent(context, ChromeDownloadActivity.class));
    }

    private DownloadManager mDownloadManager;

    /**
     * 轮询刷新下载中列表的 Handler
     */
    private final Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private static final long REFRESH_INTERVAL_MS = 1000L;

    private DownloadListFragment mOngoingFragment;
    private DownloadListFragment mDoneFragment;

    /**
     * 下载完成广播接收器
     */
    private final BroadcastReceiver mDownloadCompleteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 下载完成：刷新两个 Tab
            refreshAllTabs();
        }
    };

    @Override
    protected int getLayoutId() {
        return R.layout.chrome_download_activity;
    }

    @Override
    protected void initView() {
        TitleBar titleBar = findViewById(R.id.tb_download_title);
        titleBar.setOnTitleBarListener(this);

        RecyclerView rvTabs = findViewById(R.id.rv_download_tabs);
        ViewPager viewPager = findViewById(R.id.vp_download_pager);
        mOngoingFragment = DownloadListFragment.newInstance(false);
        mDoneFragment = DownloadListFragment.newInstance(true);

        DownloadPagerAdapter adapter = new DownloadPagerAdapter(
                getSupportFragmentManager(), mOngoingFragment, mDoneFragment,
                getString(R.string.chrome_download_tab_ongoing),
                getString(R.string.chrome_download_tab_done));
        viewPager.setAdapter(adapter);

        // 使用项目通用 TabAdapter
        TabAdapter tabAdapter = new TabAdapter(this);
        tabAdapter.addItem(getString(R.string.chrome_download_tab_ongoing));
        tabAdapter.addItem(getString(R.string.chrome_download_tab_done));
        tabAdapter.setOnTabListener((recyclerView, position) -> {
            viewPager.setCurrentItem(position);
            return true;
        });
        rvTabs.setAdapter(tabAdapter);

        // ViewPager 切换时同步 Tab 选中状态
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                tabAdapter.setSelectedPosition(position);
            }
        });
    }

    @Override
    protected void initData() {
        mDownloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        // 注册下载完成广播
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        registerReceiver(mDownloadCompleteReceiver, filter);

        // 初始加载
        refreshAllTabs();

        // 启动轮询刷新（刷新「下载中」进度）
        startPolling();
    }

    @Override
    public void onLeftClick(@NonNull TitleBar titleBar) {
        finish();
    }

    @Override
    public void onRightClick(@NonNull TitleBar titleBar) {
        // 右侧加号：弹出输入框手动添加下载任务，自动读取剪贴板内容
        showAddDownloadDialog();
    }

    /**
     * 弹出添加下载对话框，自动从剪贴板读取 URL
     */
    private void showAddDownloadDialog() {
        // 读取剪贴板内容作为默认值
        String clipUrl = "";
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            android.content.ClipData.Item clipItem = clipboard.getPrimaryClip().getItemAt(0);
            if (clipItem != null && clipItem.getText() != null) {
                String text = clipItem.getText().toString().trim();
                // 只有是 http/https 链接才自动填入
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    clipUrl = text;
                }
            }
        }
        final String finalClipUrl = clipUrl;

        new InputDialog.Builder(this)
                .setTitle(getString(R.string.chrome_download_add_title))
                .setHint(getString(R.string.chrome_download_add_hint))
                .setContent(finalClipUrl)
                .setConfirm(getString(R.string.common_confirm))
                .setCancel(getString(R.string.common_cancel))
                .setWidth(650)
                .setListener((dialog, input) -> {
                    String url = input.trim();
                    if (android.text.TextUtils.isEmpty(url)) {
                        toast(R.string.chrome_download_url_empty);
                        return;
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    startDownload(url);
                })
                .addOnShowListener(dialog -> {
                    com.hjq.custom.widget.view.RegexEditText etInput =
                            dialog.getWindow().getDecorView().findViewById(R.id.tv_input_message);
                    if (etInput == null) return;
                    // 初始有内容时显示清除图标
                    if (etInput.length() > 0) {
                        etInput.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                                android.R.drawable.ic_menu_close_clear_cancel, 0);
                    }
                    // 点击右侧图标清空内容
                    etInput.setOnTouchListener((v, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            if (etInput.getCompoundDrawables()[2] != null) {
                                int iconLeft = etInput.getRight()
                                        - etInput.getPaddingRight()
                                        - etInput.getCompoundDrawables()[2].getBounds().width();
                                if (event.getRawX() >= iconLeft) {
                                    etInput.setText("");
                                    return true;
                                }
                            }
                        }
                        return false;
                    });
                    // 有内容显示清除图标，无内容隐藏
                    etInput.addTextChangedListener(new android.text.TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                        }

                        @Override
                        public void onTextChanged(CharSequence s, int start, int before, int count) {
                        }

                        @Override
                        public void afterTextChanged(android.text.Editable s) {
                            etInput.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                                    s.length() > 0 ? android.R.drawable.ic_menu_close_clear_cancel : 0, 0);
                        }
                    });
                })
                .show();
    }

    /**
     * 使用系统 DownloadManager 下载指定 URL
     */
    private void startDownload(@NonNull String url) {
        try {
            String filename = android.webkit.URLUtil.guessFileName(url, null, null);
            // 清理文件名中的非法字符，防止 setDestinationInExternalPublicDir 抛异常
            filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (android.text.TextUtils.isEmpty(filename)) {
                filename = "download_" + System.currentTimeMillis();
            }
            DownloadManager.Request request = new DownloadManager.Request(android.net.Uri.parse(url));
            request.setTitle(filename);
            request.setDescription(url);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, filename);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                toast(R.string.chrome_download_started);
                refreshAllTabs();
            } else {
                toast(R.string.chrome_download_failed);
            }
        } catch (Exception e) {
            timber.log.Timber.e(e, "startDownload failed: url = %s", url);
            toast(getString(R.string.chrome_download_failed) + "：" + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        try {
            unregisterReceiver(mDownloadCompleteReceiver);
        } catch (Exception ignored) {
        }
    }

    // ========================= 轮询刷新 =========================

    private void startPolling() {
        mRefreshHandler.postDelayed(mRefreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void stopPolling() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
    }

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (mOngoingFragment != null) {
                mOngoingFragment.refresh();
            }
            mRefreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private void refreshAllTabs() {
        if (mOngoingFragment != null) mOngoingFragment.refresh();
        if (mDoneFragment != null) mDoneFragment.refresh();
    }

    // ========================= ViewPager Adapter =========================

    private static class DownloadPagerAdapter extends FragmentPagerAdapter {
        private final DownloadListFragment mOngoing;
        private final DownloadListFragment mDone;
        private final String mTitleOngoing;
        private final String mTitleDone;

        DownloadPagerAdapter(@NonNull FragmentManager fm,
                             DownloadListFragment ongoing, DownloadListFragment done,
                             String titleOngoing, String titleDone) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
            mOngoing = ongoing;
            mDone = done;
            mTitleOngoing = titleOngoing;
            mTitleDone = titleDone;
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            return position == 0 ? mOngoing : mDone;
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(int position) {
            return position == 0 ? mTitleOngoing : mTitleDone;
        }
    }

    // ========================= 下载列表 Fragment =========================

    public static final class DownloadListFragment extends Fragment
            implements com.hjq.demo.action.StatusAction {

        private static final String ARG_DONE = "done";

        private boolean mIsDone;

        private RecyclerView mRecyclerView;
        private com.hjq.demo.widget.StatusLayout mStatusLayout;
        private DownloadItemAdapter mAdapter;

        /**
         * 上一次查询的结果，用于计算下载速度（id → bytesDownloaded）
         */
        private final java.util.Map<Long, Long> mLastBytesMap = new java.util.HashMap<>();
        private long mLastRefreshTime = 0;

        public static DownloadListFragment newInstance(boolean isDone) {
            DownloadListFragment f = new DownloadListFragment();
            Bundle args = new Bundle();
            args.putBoolean(ARG_DONE, isDone);
            f.setArguments(args);
            return f;
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mIsDone = getArguments() != null && getArguments().getBoolean(ARG_DONE, false);
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.chrome_download_list_fragment, container, false);
            mStatusLayout = view.findViewById(R.id.sl_download_status);
            mRecyclerView = view.findViewById(R.id.rv_download_list);
            mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
            mAdapter = new DownloadItemAdapter(mIsDone);
            mRecyclerView.setAdapter(mAdapter);
            return view;
        }

        @Override
        public com.hjq.demo.widget.StatusLayout acquireStatusLayout() {
            return mStatusLayout;
        }

        @Override
        public void onResume() {
            super.onResume();
            refresh();
        }

        public void refresh() {
            if (getActivity() == null || mAdapter == null) return;
            List<DownloadItem> items = queryDownloads(mIsDone);

            // 计算下载速度（仅下载中列表）
            if (!mIsDone) {
                long now = System.currentTimeMillis();
                long elapsed = now - mLastRefreshTime; // 毫秒
                for (DownloadItem item : items) {
                    Long lastBytes = mLastBytesMap.get(item.id);
                    if (lastBytes != null && elapsed > 0) {
                        long diff = item.bytesDownloaded - lastBytes;
                        // 转换为 bytes/s
                        item.speed = diff * 1000 / elapsed;
                    }
                    mLastBytesMap.put(item.id, item.bytesDownloaded);
                }
                mLastRefreshTime = now;
            }

            mAdapter.setData(items);
            if (items.isEmpty()) {
                showEmpty();
            } else {
                showComplete();
            }
        }

        /**
         * 查询 DownloadManager 中的任务
         */
        private List<DownloadItem> queryDownloads(boolean isDone) {
            List<DownloadItem> result = new ArrayList<>();
            Context ctx = getContext();
            if (ctx == null) return result;

            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return result;

            DownloadManager.Query query = new DownloadManager.Query();
            if (isDone) {
                query.setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL | DownloadManager.STATUS_FAILED);
            } else {
                query.setFilterByStatus(
                        DownloadManager.STATUS_PENDING |
                                DownloadManager.STATUS_RUNNING |
                                DownloadManager.STATUS_PAUSED);
            }

            Cursor cursor = null;
            try {
                cursor = dm.query(query);
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        DownloadItem item = new DownloadItem();
                        item.id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                        item.title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                        item.localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                        item.status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        item.bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        item.bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        item.mediaType = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                        item.lastModifiedTime = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));
                        result.add(item);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }
            // 按最后修改时间降序排列（最新的在最前面）
            result.sort((a, b) -> Long.compare(b.lastModifiedTime, a.lastModifiedTime));
            return result;
        }
    }

    // ========================= RecyclerView Adapter =========================

    private static class DownloadItemAdapter extends RecyclerView.Adapter<DownloadItemAdapter.VH> {

        private final boolean mIsDone;
        private final List<DownloadItem> mList = new ArrayList<>();

        DownloadItemAdapter(boolean isDone) {
            mIsDone = isDone;
        }

        void setData(List<DownloadItem> data) {
            if (mIsDone) {
                // 已完成列表不频繁刷新，直接全量更新
                mList.clear();
                mList.addAll(data);
                notifyDataSetChanged();
                return;
            }
            // 下载中列表：只更新进度/速度字段，避免整体重绘导致闪烁
            if (mList.size() != data.size()) {
                mList.clear();
                mList.addAll(data);
                notifyDataSetChanged();
            } else {
                for (int i = 0; i < data.size(); i++) {
                    DownloadItem newItem = data.get(i);
                    DownloadItem oldItem = mList.get(i);
                    oldItem.bytesDownloaded = newItem.bytesDownloaded;
                    oldItem.bytesTotal = newItem.bytesTotal;
                    oldItem.status = newItem.status;
                    oldItem.speed = newItem.speed;
                    // 只通知进度相关字段变化，用 payload 标记避免整行重绘
                    notifyItemChanged(i, "progress");
                }
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.chrome_download_item, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            DownloadItem item = mList.get(position);
            holder.bind(item, mIsDone);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position, @NonNull java.util.List<Object> payloads) {
            if (!payloads.isEmpty() && "progress".equals(payloads.get(0))) {
                // 只刷新进度/速度，不重绘图标、文件名、删除按钮，避免闪烁
                holder.updateProgress(mList.get(position));
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        }

        @Override
        public int getItemCount() {
            return mList.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivFileIcon;
            TextView tvFilename;
            TextView tvDeletedTag;
            TextView tvInfo;
            TextView tvSpeedInfo;
            ProgressBar pbProgress;
            ImageView ivDelete;
            LinearLayout llCard;

            VH(@NonNull View itemView) {
                super(itemView);
                llCard = itemView.findViewById(R.id.ll_download_card);
                ivFileIcon = itemView.findViewById(R.id.iv_download_file_icon);
                tvFilename = itemView.findViewById(R.id.tv_download_filename);
                tvDeletedTag = itemView.findViewById(R.id.tv_download_deleted_tag);
                tvInfo = itemView.findViewById(R.id.tv_download_info);
                tvSpeedInfo = itemView.findViewById(R.id.tv_download_speed_info);
                pbProgress = itemView.findViewById(R.id.pb_download_progress);
                ivDelete = itemView.findViewById(R.id.iv_download_delete);
            }

            void bind(@NonNull DownloadItem item, boolean isDone) {
                // 文件名
                String filename = item.title;
                if (TextUtils.isEmpty(filename) && !TextUtils.isEmpty(item.localUri)) {
                    filename = Uri.parse(item.localUri).getLastPathSegment();
                }
                if (TextUtils.isEmpty(filename)) filename = "未知文件";

                // 文件类型图标
                ivFileIcon.setImageResource(getFileIconByName(filename, item.mediaType));

                if (isDone) {
                    // 校验本地文件是否还存在
                    boolean fileExists = false;
                    if (!TextUtils.isEmpty(item.localUri)) {
                        try {
                            String path = Uri.parse(item.localUri).getPath();
                            fileExists = path != null && new File(path).exists();
                        } catch (Exception ignored) {
                        }
                    }

                    pbProgress.setVisibility(View.GONE);
                    tvSpeedInfo.setVisibility(View.GONE);

                    if (fileExists && item.status == DownloadManager.STATUS_SUCCESSFUL) {
                        // 文件存在：正常蓝色可点击
                        llCard.setBackgroundResource(R.drawable.chrome_download_card_bg);
                        tvFilename.setText(filename);
                        tvFilename.setTextColor(itemView.getContext().getResources()
                                .getColor(R.color.chrome_secure_color, null));
                        tvFilename.setPaintFlags(tvFilename.getPaintFlags()
                                & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                        tvDeletedTag.setVisibility(View.GONE);
                        ivFileIcon.setAlpha(1.0f);
                        tvInfo.setVisibility(View.VISIBLE);
                        tvInfo.setText(formatSize(item.bytesTotal));
                        itemView.setOnClickListener(v -> openFile(v.getContext(), item));
                    } else {
                        // 文件不存在：灰色圆角背景 + 删除线 + 「已删除」标签
                        llCard.setBackgroundResource(R.drawable.chrome_download_card_deleted_bg);
                        tvFilename.setText(filename);
                        tvFilename.setTextColor(itemView.getContext().getResources()
                                .getColor(R.color.chrome_url_hint_color, null));
                        tvFilename.setPaintFlags(tvFilename.getPaintFlags()
                                | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                        tvDeletedTag.setVisibility(View.VISIBLE);
                        ivFileIcon.setAlpha(0.4f);
                        tvInfo.setVisibility(View.GONE);
                        // 不可点击
                        itemView.setOnClickListener(null);
                    }
                } else {
                    // 下载中：白色卡片，显示进度条和网速
                    llCard.setBackgroundResource(R.drawable.chrome_download_card_bg);
                    tvFilename.setText(filename);
                    tvFilename.setTextColor(itemView.getContext().getResources()
                            .getColor(R.color.chrome_url_text_color, null));
                    tvFilename.setPaintFlags(tvFilename.getPaintFlags()
                            & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                    tvDeletedTag.setVisibility(View.GONE);
                    ivFileIcon.setAlpha(1.0f);
                    tvInfo.setVisibility(View.GONE);
                    itemView.setOnClickListener(null);

                    if (item.bytesTotal > 0) {
                        pbProgress.setVisibility(View.VISIBLE);
                        pbProgress.setMax(100);
                        pbProgress.setProgress((int) (item.bytesDownloaded * 100 / item.bytesTotal));
                    } else {
                        pbProgress.setVisibility(View.GONE);
                    }
                    String speedStr = item.speed > 0 ? formatSize(item.speed) + "/s" : "0 B/s";
                    String downloaded = formatSize(item.bytesDownloaded);
                    String total = item.bytesTotal > 0 ? formatSize(item.bytesTotal) : "未知";
                    tvSpeedInfo.setVisibility(View.VISIBLE);
                    tvSpeedInfo.setText(speedStr + " - " + downloaded + "，共 " + total);
                }

                // 删除按钮（防止快速多次点击）
                ivDelete.setOnClickListener(v -> {
                    if (!isSingleClick()) return;
                    Context ctx = v.getContext();
                    new MessageDialog.Builder(ctx)
                            .setTitle(ctx.getString(R.string.chrome_download_delete))
                            .setMessage(ctx.getString(R.string.chrome_download_delete_confirm))
                            .setConfirm(ctx.getString(R.string.common_confirm))
                            .setCancel(ctx.getString(R.string.common_cancel))
                            .setListener(dialog -> {
                                DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                                if (dm != null) dm.remove(item.id);
                                int idx = getAdapterPosition();
                                if (idx >= 0 && idx < mList.size()) {
                                    mList.remove(idx);
                                    notifyItemRemoved(idx);
                                }
                            })
                            .show();
                });
            }

            /**
             * 仅更新进度/速度，不重绘整行（避免 notifyItemChanged 全量 bind 导致闪烁）
             */
            void updateProgress(@NonNull DownloadItem item) {
                if (item.bytesTotal > 0) {
                    int percent = (int) (item.bytesDownloaded * 100 / item.bytesTotal);
                    pbProgress.setVisibility(View.VISIBLE);
                    pbProgress.setMax(100);
                    pbProgress.setProgress(percent);
                } else {
                    pbProgress.setVisibility(View.GONE);
                }
                String speedStr = item.speed > 0 ? formatSize(item.speed) + "/s" : "0 B/s";
                String downloaded = formatSize(item.bytesDownloaded);
                String total = item.bytesTotal > 0 ? formatSize(item.bytesTotal) : "未知";
                tvSpeedInfo.setVisibility(View.VISIBLE);
                tvSpeedInfo.setText(speedStr + " - " + downloaded + "，共 " + total);
            }

            /**
             * 根据文件名扩展名和 mimeType 返回对应图标资源
             * 复用与 FileManagerActivity 相同的图标体系
             */
            private int getFileIconByName(@NonNull String filename, @Nullable String mimeType) {
                String ext = "";
                int dotIndex = filename.lastIndexOf('.');
                if (dotIndex >= 0) {
                    ext = filename.substring(dotIndex + 1).toLowerCase();
                }

                // 图片
                if (matchExt(ext, "png", "jpg", "jpeg", "bmp", "gif", "webp"))
                    return R.drawable.file_image;
                // 视频
                if (matchExt(ext, "mp4", "avi", "flv", "mkv", "mov", "wmv", "3gp"))
                    return R.drawable.file_video;
                // 音频
                if (matchExt(ext, "mp3", "m4a", "wav", "aac", "ogg", "flac"))
                    return R.drawable.file_audio;
                // 文本/代码
                if (matchExt(ext, "txt", "c", "cpp", "java", "xml", "json", "md", "log"))
                    return R.drawable.file_text;
                // APK
                if (matchExt(ext, "apk"))
                    return R.drawable.file_app;
                // PDF
                if (matchExt(ext, "pdf"))
                    return R.drawable.file_pdf;
                // Office
                if (matchExt(ext, "xls", "xlsx"))
                    return R.drawable.file_excel;
                if (matchExt(ext, "ppt", "pptx"))
                    return R.drawable.file_ppt;
                // 用 mimeType 补充判断
                if (!TextUtils.isEmpty(mimeType)) {
                    if (mimeType.startsWith("image/")) return R.drawable.file_image;
                    if (mimeType.startsWith("video/")) return R.drawable.file_video;
                    if (mimeType.startsWith("audio/")) return R.drawable.file_audio;
                    if (mimeType.contains("pdf")) return R.drawable.file_pdf;
                }
                return R.drawable.file_unknown;
            }

            private boolean matchExt(@NonNull String ext, @NonNull String... targets) {
                for (String t : targets) {
                    if (t.equalsIgnoreCase(ext)) return true;
                }
                return false;
            }

            private void openFile(@NonNull Context ctx, @NonNull DownloadItem item) {
                if (TextUtils.isEmpty(item.localUri)) return;
                try {
                    File file = new File(Uri.parse(item.localUri).getPath());
                    Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".provider", file);
                    String mimeType = TextUtils.isEmpty(item.mediaType) ? "*/*" : item.mediaType;
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, mimeType);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                } catch (Exception e) {
                    try {
                        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                        if (dm != null) {
                            Uri fileUri = dm.getUriForDownloadedFile(item.id);
                            if (fileUri != null) {
                                Intent intent = new Intent(Intent.ACTION_VIEW, fileUri);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                ctx.startActivity(intent);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            /**
             * 防止 500ms 内重复点击
             */
            private long mLastClickTime = 0;

            private boolean isSingleClick() {
                long now = System.currentTimeMillis();
                if (now - mLastClickTime < 500) return false;
                mLastClickTime = now;
                return true;
            }

            private String formatSize(long bytes) {
                if (bytes <= 0) return "0 B";
                DecimalFormat df = new DecimalFormat("#.##");
                if (bytes < 1024) return bytes + " B";
                if (bytes < 1024 * 1024) return df.format(bytes / 1024.0) + " KB";
                if (bytes < 1024 * 1024 * 1024) return df.format(bytes / (1024.0 * 1024)) + " MB";
                return df.format(bytes / (1024.0 * 1024 * 1024)) + " GB";
            }
        }
    }

    // ========================= 数据模型 =========================

    public static class DownloadItem {
        public long id;
        public String title;
        public String localUri;
        public int status;
        public long bytesDownloaded;
        public long bytesTotal;
        public String mediaType;
        /**
         * 下载完成/最后修改时间（毫秒），用于排序
         */
        public long lastModifiedTime;
        /**
         * 当前下载速度（bytes/s），仅下载中有效
         */
        public long speed;
    }
}
