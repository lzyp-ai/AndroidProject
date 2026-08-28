package com.hjq.demo.ui.activity.common;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.drake.softinput.SoftInputKt;
import com.hjq.demo.R;
import com.hjq.demo.action.StatusAction;
import com.hjq.demo.aop.CheckNet;
import com.hjq.demo.aop.Log;
import com.hjq.demo.app.AppActivity;
import com.hjq.demo.ui.dialog.common.AddShortcutDialog;
import com.hjq.demo.ui.dialog.common.MessageDialog;
import com.hjq.demo.widget.StatusLayout;
import com.hjq.demo.widget.webview.BrowserChromeClient;
import com.hjq.demo.widget.webview.BrowserFullScreenController;
import com.hjq.demo.widget.webview.BrowserView;
import com.hjq.demo.widget.webview.BrowserViewClient;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.scwang.smart.refresh.layout.listener.OnRefreshListener;
import com.tencent.mmkv.MMKV;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/AndroidProject
 *    time   : 2024/01/01
 *    desc   : Chrome PC 风格多标签浏览器界面
 */
public final class ChromeBrowserActivity extends AppActivity
        implements StatusAction, OnRefreshListener {

    private static final String INTENT_KEY_IN_URL = "url";

    /** 默认首页 URL */
    private static final String DEFAULT_HOME_URL = "https://www.baidu.com";

    /** MMKV key：用户自定义主页 */
    private static final String MMKV_KEY_HOMEPAGE = "chrome_homepage";

    /** MMKV key：书签列表 */
    private static final String MMKV_KEY_SHORTCUTS = "chrome_shortcuts";
    /** MMKV key 前缀：书签 favicon（后面跟 url 的 hashCode） */
    private static final String MMKV_KEY_FAVICON_PREFIX = "chrome_favicon_";

    private static final String SHORTCUT_SEPARATOR = "\\|\\|";
    private static final String SHORTCUT_JOIN = "||";
    private static final String ITEM_SEPARATOR = "::";

    // ========================= 启动入口 =========================

    @CheckNet
    @Log
    public static void start(@NonNull Context context, @Nullable String url) {
        Intent intent = new Intent(context, ChromeBrowserActivity.class);
        if (!TextUtils.isEmpty(url)) {
            intent.putExtra(INTENT_KEY_IN_URL, url);
        }
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    // ========================= 字段 =========================

    @NonNull
    private final BrowserFullScreenController mFullScreenController = new BrowserFullScreenController();

    private MMKV mMmkv;

    // 标签页
    private final List<TabModel> mTabList = new ArrayList<>();
    private int mCurrentTabIndex = -1;

    // 顶部工具栏
    private LinearLayout mTabContainer;
    private HorizontalScrollView mTabScrollView;
    private ImageView mIvBack;
    private ImageView mIvForward;
    private ImageView mIvReload;
    private ImageView mIvSecure;
    private ImageView mIvMore;
    private EditText mEtUrl;
    private ProgressBar mProgressBar;

    // 内容区域
    private View mNewTabView;
    private StatusLayout mStatusLayout;
    private SmartRefreshLayout mRefreshLayout;
    private BrowserView mBrowserView;

    // 快速访问书签容器（横向 LinearLayout，末尾固定加号）
    private LinearLayout mShortcutsContainer;

    // 书签数据
    private final List<ShortcutModel> mShortcutList = new ArrayList<>();

    // 更多菜单弹窗
    private PopupWindow mPopupWindow;

    // ========================= 生命周期 =========================

    @Override
    protected int getLayoutId() {
        return R.layout.chrome_browser_activity;
    }

    @Override
    protected boolean isStatusBarEnabled() {
        return true;
    }

    @Override
    protected boolean isStatusBarDarkFont() {
        return true;
    }

    @Override
    protected void initView() {
        mTabContainer = findViewById(R.id.ll_chrome_tab_container);
        mTabScrollView = findViewById(R.id.hsv_chrome_tabs);
        mIvBack = findViewById(R.id.iv_chrome_back);
        mIvForward = findViewById(R.id.iv_chrome_forward);
        mIvReload = findViewById(R.id.iv_chrome_reload);
        mIvSecure = findViewById(R.id.iv_chrome_secure);
        mIvMore = findViewById(R.id.iv_chrome_more);
        mEtUrl = findViewById(R.id.et_chrome_url);
        mProgressBar = findViewById(R.id.pb_chrome_progress);
        mNewTabView = findViewById(R.id.include_chrome_new_tab);
        mStatusLayout = findViewById(R.id.sl_chrome_status);
        mRefreshLayout = findViewById(R.id.srl_chrome_refresh);
        mBrowserView = findViewById(R.id.wv_chrome_browser);

        mBrowserView.setLifecycleOwner(this);
        mRefreshLayout.setOnRefreshListener(this);
        SoftInputKt.setWindowSoftInput(this, mBrowserView);

        // 工具栏按钮
        setOnClickListener(
                R.id.iv_chrome_new_tab,
                R.id.iv_chrome_back,
                R.id.iv_chrome_forward,
                R.id.iv_chrome_home,
                R.id.iv_chrome_more,
                R.id.iv_chrome_reload
        );

        // 地址栏回车
        mEtUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                loadUrlOrSearch(mEtUrl.getText().toString().trim());
                return true;
            }
            return false;
        });

        // 新标签页搜索框
        EditText etSearch = mNewTabView.findViewById(R.id.et_chrome_new_tab_search);
        ImageView ivSearchBtn = mNewTabView.findViewById(R.id.iv_chrome_new_tab_search_btn);
        if (etSearch != null) {
            etSearch.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    loadUrlOrSearch(etSearch.getText().toString().trim());
                    return true;
                }
                return false;
            });
        }
        if (ivSearchBtn != null && etSearch != null) {
            final EditText finalEt = etSearch;
            ivSearchBtn.setOnClickListener(v -> loadUrlOrSearch(finalEt.getText().toString().trim()));
        }

        // 快速访问横向容器
        mShortcutsContainer = mNewTabView.findViewById(R.id.ll_chrome_shortcuts_container);

        // 末尾加号按钮
        View addBtn = mNewTabView.findViewById(R.id.ll_chrome_add_shortcut);
        if (addBtn != null) {
            addBtn.setOnClickListener(v -> showAddShortcutDialog(null, null));
        }
    }

    @Override
    protected void initData() {
        mMmkv = MMKV.mmkvWithID("chrome_browser");

        mBrowserView.setBrowserViewClient(new AppBrowserViewClient());
        mBrowserView.setBrowserChromeClient(new AppBrowserChromeClient(mBrowserView));

        // 拦截网页触发的下载链接，交给系统 DownloadManager 处理
        mBrowserView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                android.app.DownloadManager.Request request =
                        new android.app.DownloadManager.Request(Uri.parse(downloadUrl));
                String filename = android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType);
                request.setTitle(filename);
                request.setDescription(downloadUrl);
                request.setMimeType(mimeType);
                request.setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS, filename);
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(true);
                request.addRequestHeader("User-Agent", userAgent);
                android.app.DownloadManager dm =
                        (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    toast(R.string.chrome_download_started);
                    ChromeDownloadActivity.start(this);
                } else {
                    toast(R.string.chrome_download_failed);
                }
            } catch (Exception e) {
                timber.log.Timber.e(e, "DownloadListener failed: url = %s", downloadUrl);
                toast(getString(R.string.chrome_download_failed) + "：" + e.getMessage());
            }
        });

        loadShortcuts();

        String url = getString(INTENT_KEY_IN_URL);
        addNewTab(TextUtils.isEmpty(url) ? null : url);
    }

    @Override
    public StatusLayout acquireStatusLayout() {
        return mStatusLayout;
    }

    // ========================= 标签页管理 =========================

    private void addNewTab(@Nullable String url) {
        TabModel tab = new TabModel();
        tab.title = getString(R.string.chrome_new_tab);
        tab.url = url;
        mTabList.add(tab);

        View tabView = LayoutInflater.from(this).inflate(R.layout.chrome_browser_tab_item, mTabContainer, false);
        tab.tabView = tabView;

        TextView tvTitle = tabView.findViewById(R.id.tv_chrome_tab_title);
        ImageView ivClose = tabView.findViewById(R.id.iv_chrome_tab_close);
        tvTitle.setText(tab.title);
        tabView.setOnClickListener(v -> switchToTab(mTabList.indexOf(tab)));
        ivClose.setOnClickListener(v -> closeTab(mTabList.indexOf(tab)));

        mTabContainer.addView(tabView);
        switchToTab(mTabList.size() - 1);

        if (TextUtils.isEmpty(url)) {
            showNewTabPage();
        } else {
            loadUrl(url);
        }
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= mTabList.size()) return;
        if (mCurrentTabIndex >= 0 && mCurrentTabIndex < mTabList.size()) {
            TabModel prev = mTabList.get(mCurrentTabIndex);
            if (prev.tabView != null) {
                prev.tabView.setSelected(false);
                prev.tabView.setAlpha(0.7f);
            }
        }
        mCurrentTabIndex = index;
        TabModel current = mTabList.get(index);
        if (current.tabView != null) {
            current.tabView.setSelected(true);
            current.tabView.setAlpha(1.0f);
        }
        scrollTabIntoView(current.tabView);
        mEtUrl.setText(current.url);
        if (TextUtils.isEmpty(current.url)) {
            showNewTabPage();
        } else {
            showWebContent();
            if (!TextUtils.equals(mBrowserView.getUrl(), current.url)) {
                loadUrl(current.url);
            }
        }
        updateNavButtonState();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= mTabList.size()) return;
        TabModel tab = mTabList.get(index);
        if (tab.tabView != null) mTabContainer.removeView(tab.tabView);
        mTabList.remove(index);
        if (mTabList.isEmpty()) {
            finish();
            return;
        }
        mCurrentTabIndex = -1;
        switchToTab(Math.min(index, mTabList.size() - 1));
    }

    private void scrollTabIntoView(@Nullable View tabView) {
        if (tabView == null) return;
        mTabScrollView.post(() -> {
            int left = tabView.getLeft();
            int right = tabView.getRight();
            int scrollX = mTabScrollView.getScrollX();
            int width = mTabScrollView.getWidth();
            if (left < scrollX) {
                mTabScrollView.smoothScrollTo(left, 0);
            } else if (right > scrollX + width) {
                mTabScrollView.smoothScrollTo(right - width, 0);
            }
        });
    }

    // ========================= 页面加载 =========================

    private void loadUrl(@NonNull String url) {
        String normalized = normalizeUrl(url);
        mEtUrl.setText(normalized);
        if (mCurrentTabIndex >= 0 && mCurrentTabIndex < mTabList.size()) {
            mTabList.get(mCurrentTabIndex).url = normalized;
        }
        showWebContent();
        showLoading();
        mBrowserView.loadUrl(normalized);
    }

    private void loadUrlOrSearch(@NonNull String input) {
        if (TextUtils.isEmpty(input)) return;
        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            url = "https://" + input;
        } else {
            url = "https://www.baidu.com/s?wd=" + Uri.encode(input);
        }
        loadUrl(url);
    }

    @NonNull
    private String normalizeUrl(@NonNull String url) {
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("about:")) {
            url = "https://" + url;
        }
        return url;
    }

    @CheckNet
    private void reload() {
        mBrowserView.reload();
    }

    // ========================= UI 状态 =========================

    private void showNewTabPage() {
        mNewTabView.setVisibility(View.VISIBLE);
        mStatusLayout.setVisibility(View.GONE);
        mEtUrl.setText("");
        mIvSecure.setVisibility(View.GONE);
        updateNavButtonState();
    }

    private void showWebContent() {
        mNewTabView.setVisibility(View.GONE);
        mStatusLayout.setVisibility(View.VISIBLE);
    }

    private void updateNavButtonState() {
        mIvBack.setAlpha(mBrowserView.canGoBack() ? 1.0f : 0.35f);
        mIvForward.setAlpha(mBrowserView.canGoForward() ? 1.0f : 0.35f);
    }

    private void updateSecureIcon(@NonNull String url) {
        mIvSecure.setVisibility(View.VISIBLE);
        if (url.startsWith("https://")) {
            mIvSecure.setImageResource(R.drawable.chrome_lock_ic);
            mIvSecure.setColorFilter(getResources().getColor(R.color.chrome_secure_color, getTheme()));
        } else {
            mIvSecure.setImageResource(R.drawable.chrome_lock_open_ic);
            mIvSecure.setColorFilter(getResources().getColor(R.color.chrome_insecure_color, getTheme()));
        }
    }

    private void updateCurrentTabTitle(@NonNull String title) {
        if (mCurrentTabIndex < 0 || mCurrentTabIndex >= mTabList.size()) return;
        TabModel tab = mTabList.get(mCurrentTabIndex);
        tab.title = title;
        if (tab.tabView != null) {
            TextView tv = tab.tabView.findViewById(R.id.tv_chrome_tab_title);
            if (tv != null) tv.setText(title);
        }
    }

    private void updateCurrentTabFavicon(@NonNull Bitmap icon) {
        if (mCurrentTabIndex < 0 || mCurrentTabIndex >= mTabList.size()) return;
        TabModel tab = mTabList.get(mCurrentTabIndex);
        if (tab.tabView != null) {
            ImageView iv = tab.tabView.findViewById(R.id.iv_chrome_tab_favicon);
            if (iv != null) {
                iv.setImageBitmap(icon);
                // android:tint 需要用 setImageTintList(null) 才能彻底清除，
                // clearColorFilter() 只清除 setColorFilter() 设置的，无法覆盖 XML 里的 tint
                iv.setImageTintList(null);
            }
        }
    }

    // ========================= 快速访问书签 =========================

    /**
     * 从 MMKV 加载书签列表，并刷新 UI
     */
    private void loadShortcuts() {
        mShortcutList.clear();
        String saved = mMmkv.decodeString(MMKV_KEY_SHORTCUTS, "");
        if (!TextUtils.isEmpty(saved)) {
            for (String item : saved.split(SHORTCUT_SEPARATOR)) {
                String[] parts = item.split(ITEM_SEPARATOR, 2);
                if (parts.length == 2) {
                    mShortcutList.add(new ShortcutModel(parts[0], parts[1]));
                }
            }
        }
        rebuildShortcutsView();
    }

    /**
     * 保存书签列表到 MMKV
     */
    private void saveShortcuts() {
        if (mShortcutList.isEmpty()) {
            mMmkv.encode(MMKV_KEY_SHORTCUTS, "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mShortcutList.size(); i++) {
            ShortcutModel s = mShortcutList.get(i);
            sb.append(s.title).append(ITEM_SEPARATOR).append(s.url);
            if (i < mShortcutList.size() - 1) {
                sb.append(SHORTCUT_JOIN);
            }
        }
        mMmkv.encode(MMKV_KEY_SHORTCUTS, sb.toString());
    }

    /**
     * 将 favicon Bitmap 编码为 Base64 存入 MMKV
     */
    private void saveFavicon(@NonNull String url, @NonNull Bitmap bitmap) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            String b64 = Base64.encodeToString(out.toByteArray(), Base64.DEFAULT);
            mMmkv.encode(MMKV_KEY_FAVICON_PREFIX + url.hashCode(), b64);
        } catch (Exception e) {
            // 忽略缓存失败
        }
    }

    /**
     * 从 MMKV 读取缓存的 favicon
     */
    @Nullable
    private Bitmap loadFavicon(@NonNull String url) {
        String b64 = mMmkv.decodeString(MMKV_KEY_FAVICON_PREFIX + url.hashCode(), null);
        if (TextUtils.isEmpty(b64)) return null;
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 完全重建快速访问横向列表（书签 View + 末尾加号固定不变）
     */
    private void rebuildShortcutsView() {
        if (mShortcutsContainer == null) return;

        // 移除所有书签 View，保留末尾加号（最后一个子 View）
        int addBtnIndex = mShortcutsContainer.getChildCount() - 1;
        if (addBtnIndex > 0) {
            mShortcutsContainer.removeViews(0, addBtnIndex);
        }

        // 按顺序在加号前面插入书签
        for (int i = 0; i < mShortcutList.size(); i++) {
            ShortcutModel item = mShortcutList.get(i);
            View itemView = buildShortcutView(item, i);
            mShortcutsContainer.addView(itemView, i);
        }
    }

    /**
     * 构建单个书签 View
     */
    private View buildShortcutView(@NonNull ShortcutModel item, int position) {
        View itemView = LayoutInflater.from(this).inflate(R.layout.chrome_browser_shortcut_item, mShortcutsContainer, false);
        TextView tvTitle = itemView.findViewById(R.id.tv_shortcut_title);
        ImageView ivIcon = itemView.findViewById(R.id.iv_shortcut_icon);

        tvTitle.setText(item.title);

        // 优先显示缓存的 favicon，没有则保持默认地球图标
        Bitmap cached = loadFavicon(item.url);
        if (cached != null) {
            ivIcon.setImageBitmap(cached);
            ivIcon.setImageTintList(null);
            ivIcon.setPadding(0, 0, 0, 0);
        }

        // 点击打开：记录来源书签 index，用于后续 favicon 缓存
        int shortcutIndex = mShortcutList.indexOf(item);
        itemView.setOnClickListener(v -> {
            // 若该书签还没有 favicon 缓存，打开时记录来源 index
            boolean hasFavicon = loadFavicon(item.url) != null;
            loadUrl(item.url);
            if (!hasFavicon && mCurrentTabIndex >= 0 && mCurrentTabIndex < mTabList.size()) {
                mTabList.get(mCurrentTabIndex).fromShortcutIndex = shortcutIndex;
            }
        });

        // 长按删除
        itemView.setOnLongClickListener(v -> {
            new MessageDialog.Builder(this)
                    .setTitle("删除确认")
                    .setMessage("删除「" + item.title + "」？")
                    .setConfirm(R.string.common_confirm)
                    .setCancel(R.string.common_cancel)
                    .setListener(dialog -> {
                        int idx = mShortcutList.indexOf(item);
                        if (idx >= 0) {
                            mShortcutList.remove(idx);
                            saveShortcuts();
                            mShortcutsContainer.removeViewAt(idx);
                        }
                    })
                    .show();
            return true;
        });

        return itemView;
    }

    /**
     * 新增书签，并在 WebView 回调 favicon 时自动更新图标
     */
    private void addShortcut(@NonNull String title, @NonNull String url) {
        ShortcutModel model = new ShortcutModel(title, url);
        mShortcutList.add(model);
        saveShortcuts();

        // 插入到加号前面
        int insertIndex = mShortcutsContainer.getChildCount() - 1;
        View itemView = buildShortcutView(model, mShortcutList.size() - 1);
        mShortcutsContainer.addView(itemView, insertIndex);
    }

    /**
     * 当前正在加载的页面有 favicon 时，更新对应书签图标缓存
     */
    /**
     * 当前正在加载的页面有 favicon 时，更新对应书签图标缓存。
     * 精准匹配：只看当前标签是否从某个书签打开的（mFromShortcutIndex 记录），
     * 如有则用书签的 URL 作为 key 存储，保证下次能命中。
     */
    private void updateShortcutFaviconIfExists(@NonNull Bitmap favicon) {
        if (mCurrentTabIndex < 0 || mCurrentTabIndex >= mTabList.size()) return;
        int fromIdx = mTabList.get(mCurrentTabIndex).fromShortcutIndex;
        if (fromIdx < 0 || fromIdx >= mShortcutList.size()) return;

        String shortcutUrl = mShortcutList.get(fromIdx).url;
        // 没有缓存才保存一次，保存后立即清除标记，后续浏览其他页面不再触发
        if (loadFavicon(shortcutUrl) == null) {
            saveFavicon(shortcutUrl, favicon);
            // 保存成功后清除来源标记，防止同一 Tab 继续浏览时再次覆盖
            mTabList.get(mCurrentTabIndex).fromShortcutIndex = -1;
            // 实时更新 UI
            if (fromIdx < mShortcutsContainer.getChildCount() - 1) {
                View itemView = mShortcutsContainer.getChildAt(fromIdx);
                ImageView ivIcon = itemView.findViewById(R.id.iv_shortcut_icon);
                if (ivIcon != null) {
                    ivIcon.setImageBitmap(favicon);
                    ivIcon.setImageTintList(null);
                    ivIcon.setPadding(0, 0, 0, 0);
                }
            }
        } else {
            // 已有缓存，清除标记，后续不再处理
            mTabList.get(mCurrentTabIndex).fromShortcutIndex = -1;
        }
    }

    // ========================= 添加书签对话框 =========================

    private void showAddShortcutDialog(@Nullable String defaultTitle, @Nullable String defaultUrl) {
        // 自动填入当前标签的 URL（新标签页则为空）
        String preUrl = defaultUrl;
        if (TextUtils.isEmpty(preUrl) && mCurrentTabIndex >= 0 && mCurrentTabIndex < mTabList.size()) {
            preUrl = mTabList.get(mCurrentTabIndex).url;
        }
        if (preUrl == null) preUrl = "";

        // 自动填入当前标签的网站名称
        String preTitle = defaultTitle;
        if (TextUtils.isEmpty(preTitle) && mCurrentTabIndex >= 0 && mCurrentTabIndex < mTabList.size()) {
            String tabTitle = mTabList.get(mCurrentTabIndex).title;
            // 排除默认的「新标签页」占位标题
            if (!TextUtils.isEmpty(tabTitle) && !tabTitle.equals(getString(R.string.chrome_new_tab))) {
                preTitle = tabTitle;
            }
        }
        if (preTitle == null) preTitle = "";

        new com.hjq.demo.ui.dialog.common.AddShortcutDialog.Builder(this)
                .setTitle(getString(R.string.chrome_shortcut_add_title))
                .setName(preTitle)
                .setUrl(preUrl)
                .setWidth(650)
                .setConfirm(getString(R.string.chrome_shortcut_confirm))
                .setCancel(R.string.common_cancel)
                .setListener((dialog, name, url) -> {
                    if (TextUtils.isEmpty(name)) {
                        toast(R.string.chrome_shortcut_name_empty);
                        return;
                    }
                    if (TextUtils.isEmpty(url)) {
                        toast(R.string.chrome_shortcut_url_empty);
                        return;
                    }
                    String normalized = normalizeUrl(url);
                    addShortcut(name, normalized);

                    // 如果当前 WebView 正好是这个 URL，直接缓存 favicon
                    if (mCurrentTabIndex >= 0 && mCurrentTabIndex < mTabList.size()) {
                        TabModel tab = mTabList.get(mCurrentTabIndex);
                        if (tab.favicon != null && TextUtils.equals(tab.url, normalized)) {
                            saveFavicon(normalized, tab.favicon);
                            int lastIdx = mShortcutList.size() - 1;
                            if (lastIdx >= 0 && lastIdx < mShortcutsContainer.getChildCount() - 1) {
                                View v = mShortcutsContainer.getChildAt(lastIdx);
                                ImageView iv = v.findViewById(R.id.iv_shortcut_icon);
                                if (iv != null) {
                                    iv.setImageBitmap(tab.favicon);
                                    iv.setImageTintList(null);
                                    iv.setPadding(0, 0, 0, 0);
                                }
                            }
                        }
                    }
                })
                .show();
    }

    // ========================= 主页管理 =========================

    /** 获取当前主页地址，优先读用户设置，否则返回默认百度 */
    @NonNull
    private String getHomeUrl() {
        String saved = mMmkv.decodeString(MMKV_KEY_HOMEPAGE, "");
        return TextUtils.isEmpty(saved) ? DEFAULT_HOME_URL : saved;
    }

    /** 弹出设置主页对话框 */
    private void showSetHomepageDialog() {
        new com.hjq.demo.ui.dialog.common.InputDialog.Builder(this)
                .setTitle(getString(R.string.chrome_homepage_title))
                .setHint(getString(R.string.chrome_homepage_hint))
                .setContent(getHomeUrl())
                .setConfirm(R.string.common_confirm)
                .setCancel(R.string.common_cancel)
                .setListener((dialog, input) -> {
                    String url = input.trim();
                    if (TextUtils.isEmpty(url)) {
                        toast(R.string.chrome_homepage_empty);
                        return;
                    }
                    String normalized = normalizeUrl(url);
                    mMmkv.encode(MMKV_KEY_HOMEPAGE, normalized);
                    toast(R.string.chrome_homepage_saved);
                })
                .show();
    }

    // ========================= 更多菜单 PopupWindow =========================

    private void showMoreMenu() {
        View menuView = LayoutInflater.from(this).inflate(R.layout.chrome_browser_popup_menu, null);

        // 预先将 menuView measure 出实际尺寸
        int popupWidth = (int) (getResources().getDisplayMetrics().density * 420 + 0.5f);

        mPopupWindow = new PopupWindow(menuView,
                popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        mPopupWindow.setElevation(16f);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.setBackgroundDrawable(null);

        // 内容超过屏幕 70% 时限高，FrameLayout 整体裁剪保证圆角正确
        menuView.post(() -> {
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int maxHeight = (int) (screenHeight * 0.7f);
            if (menuView.getHeight() > maxHeight) {
                mPopupWindow.update(mPopupWindow.getWidth(), maxHeight);
            }
        });

        String currentUrl = mBrowserView.getUrl();
        if (TextUtils.isEmpty(currentUrl)) currentUrl = "";
        final String finalUrl = currentUrl;

        boolean isNewTabPage = mNewTabView.getVisibility() == View.VISIBLE;

        menuView.findViewById(R.id.ll_menu_new_tab).setOnClickListener(v -> {
            mPopupWindow.dismiss();
            addNewTab(null);
        });

        View llReload = menuView.findViewById(R.id.ll_menu_reload);
        llReload.setVisibility(isNewTabPage ? View.GONE : View.VISIBLE);
        llReload.setOnClickListener(v -> { mPopupWindow.dismiss(); reload(); });

        View llAddBookmark = menuView.findViewById(R.id.ll_menu_add_bookmark);
        llAddBookmark.setVisibility(isNewTabPage ? View.GONE : View.VISIBLE);
        llAddBookmark.setOnClickListener(v -> {
            mPopupWindow.dismiss();
            showAddShortcutDialog(null, finalUrl);
        });

        // 分割线1：新建标签页 和 页面操作区之间（新标签页时隐藏）
        menuView.findViewById(R.id.v_menu_divider_1)
                .setVisibility(isNewTabPage ? View.GONE : View.VISIBLE);
        View llCopyLink = menuView.findViewById(R.id.ll_menu_copy_link);
        llCopyLink.setVisibility(isNewTabPage ? View.GONE : View.VISIBLE);
        llCopyLink.setOnClickListener(v -> {
            mPopupWindow.dismiss();
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("url", finalUrl));
                toast(R.string.chrome_menu_copy_success);
            }
        });

        View llOpenBrowser = menuView.findViewById(R.id.ll_menu_open_browser);
        llOpenBrowser.setVisibility(isNewTabPage ? View.GONE : View.VISIBLE);
        llOpenBrowser.setOnClickListener(v -> {
            mPopupWindow.dismiss();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                toast(R.string.chrome_menu_open_fail);
            }
        });

        View llShare = menuView.findViewById(R.id.ll_menu_share);
        llShare.setVisibility(isNewTabPage ? View.GONE : View.VISIBLE);
        llShare.setOnClickListener(v -> {
            mPopupWindow.dismiss();
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, finalUrl);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.chrome_menu_share)));
        });

        // 分割线2（链接区和操作区之间）、分割线3（操作区和设置区之间）：新标签页时隐藏
        menuView.findViewById(R.id.v_menu_divider_2)
                .setVisibility(isNewTabPage ? View.GONE : View.VISIBLE);
        menuView.findViewById(R.id.v_menu_divider_3)
                .setVisibility(isNewTabPage ? View.GONE : View.VISIBLE);

        // 设置主页（始终显示）
        menuView.findViewById(R.id.ll_menu_set_homepage).setOnClickListener(v -> {
            mPopupWindow.dismiss();
            showSetHomepageDialog();
        });

        // 下载（始终显示）
        menuView.findViewById(R.id.ll_menu_downloads).setOnClickListener(v -> {
            mPopupWindow.dismiss();
            ChromeDownloadActivity.start(this);
        });

        mPopupWindow.showAsDropDown(mIvMore, 0, 4, Gravity.END);
    }

    // ========================= 点击事件 =========================

    @Override
    public void onClick(@NonNull View view) {
        int id = view.getId();
        if (id == R.id.iv_chrome_new_tab) {
            addNewTab(null);
        } else if (id == R.id.iv_chrome_back) {
            if (mBrowserView.canGoBack()) mBrowserView.goBack();
        } else if (id == R.id.iv_chrome_forward) {
            if (mBrowserView.canGoForward()) mBrowserView.goForward();
        } else if (id == R.id.iv_chrome_reload) {
            if (mProgressBar.getVisibility() == View.VISIBLE) {
                mBrowserView.stopLoading();
                mProgressBar.setVisibility(View.GONE);
                mIvReload.setImageResource(R.drawable.chrome_reload_ic);
            } else {
                reload();
            }
        } else if (id == R.id.iv_chrome_home) {
            loadUrl(getHomeUrl());
        } else if (id == R.id.iv_chrome_more) {
            showMoreMenu();
        }
    }

    // ========================= 返回键 =========================

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mFullScreenController.isFullScreen()) {
                mFullScreenController.exitFullScreen(this);
                return true;
            }
            if (mBrowserView.canGoBack()) {
                mBrowserView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ========================= OnRefreshListener =========================

    @Override
    public void onRefresh(@NonNull RefreshLayout refreshLayout) {
        reload();
    }

    // ========================= WebViewClient =========================

    private class AppBrowserViewClient extends BrowserViewClient {

        @Override
        protected void onUserRefuseSslError(@Nullable SslErrorHandler handler) {
            super.onUserRefuseSslError(handler);
            if (!mBrowserView.canGoBack()) showNewTabPage();
        }

        @Override
        public void onWebPageLoadStarted(@NonNull WebView view, @NonNull String url, @Nullable Bitmap favicon) {
            super.onWebPageLoadStarted(view, url, favicon);
            mProgressBar.setVisibility(View.VISIBLE);
            mIvReload.setImageResource(R.drawable.chrome_close_ic);
            mEtUrl.setText(url);
            if (mCurrentTabIndex >= 0 && mCurrentTabIndex < mTabList.size()) {
                mTabList.get(mCurrentTabIndex).url = url;
            }
            updateSecureIcon(url);
        }

        @Override
        public void onWebPageLoadFinished(@NonNull WebView view, @NonNull String url, boolean success) {
            super.onWebPageLoadFinished(view, url, success);
            mProgressBar.setVisibility(View.GONE);
            mIvReload.setImageResource(R.drawable.chrome_reload_ic);
            mRefreshLayout.finishRefresh();
            updateNavButtonState();
            if (success) {
                showComplete();
                String finalUrl = mBrowserView.getUrl();
                if (!TextUtils.isEmpty(finalUrl)) {
                    mEtUrl.setText(finalUrl);
                    updateSecureIcon(finalUrl);
                    if (mCurrentTabIndex >= 0 && mCurrentTabIndex < mTabList.size()) {
                        mTabList.get(mCurrentTabIndex).url = finalUrl;
                    }
                }
            } else {
                showError(listener -> reload());
            }
        }
    }

    // ========================= ChromeClient =========================

    private class AppBrowserChromeClient extends BrowserChromeClient {

        AppBrowserChromeClient(@NonNull BrowserView view) {
            super(view);
        }

        @Override
        public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {
            updateCurrentTabTitle(title);
        }

        @Override
        public void onReceivedIcon(@NonNull WebView view, @NonNull Bitmap icon) {
            // 更新 Tab 图标
            updateCurrentTabFavicon(icon);
            // 缓存当前 Tab favicon 到模型（用于添加书签时直接使用）
            if (mCurrentTabIndex >= 0 && mCurrentTabIndex < mTabList.size()) {
                mTabList.get(mCurrentTabIndex).favicon = icon;
            }
            // 如果当前 URL 已在书签列表中，同步更新书签图标缓存
            updateShortcutFaviconIfExists(icon);
        }

        @Override
        public void onProgressChanged(@NonNull WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            mProgressBar.setProgress(newProgress);
        }

        @Override
        public void onShowCustomView(@Nullable View view, @Nullable CustomViewCallback callback) {
            mBrowserView.setVisibility(View.INVISIBLE);
            mFullScreenController.enterFullScreen(ChromeBrowserActivity.this, view, callback);
            mBrowserView.setVisibility(View.VISIBLE);
        }

        @Override
        public void onHideCustomView() {
            mFullScreenController.exitFullScreen(ChromeBrowserActivity.this);
        }
    }

    // ========================= 数据模型 =========================

    private static class TabModel {
        String title;
        String url;
        View tabView;
        /** 当前标签页加载的 favicon，用于添加书签时直接取用 */
        Bitmap favicon;
        /** 如果是从书签点击打开的，记录书签的 index（-1 表示非书签打开）*/
        int fromShortcutIndex = -1;
    }

    private static class ShortcutModel {
        String title;
        String url;

        ShortcutModel(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }
}
