package com.hjq.demo.ui.dialog.common;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hjq.base.BaseDialog;
import com.hjq.demo.R;
import com.hjq.demo.aop.SingleClick;
import com.hjq.demo.app.AppAdapter;
import com.hjq.demo.ui.adapter.common.TabAdapter;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.scwang.smart.refresh.layout.listener.OnRefreshListener;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/AndroidProject
 *    time   : 2025/03/06
 *    desc   : 应用选择对话框
 */
public final class AppSelectDialog {

    private static final int TYPE_USER_APP = 0;
    private static final int TYPE_SYSTEM_APP = 1;

    public static final class Builder
            extends BaseDialog.Builder<Builder>
            implements TabAdapter.OnTabListener,
            BaseDialog.OnShowListener, BaseDialog.OnDismissListener {

        @NonNull
        private final TextView mTitleView;
        @NonNull
        private final ImageView mCloseView;
        @NonNull
        private final ImageView mSearchIcon;
        @NonNull
        private final ImageView mSearchCloseView;
        @NonNull
        private final LinearLayout mTitleBar;
        @NonNull
        private final LinearLayout mSearchBar;
        @NonNull
        private final EditText mSearchEdit;
        @NonNull
        private final RecyclerView mTabView;
        @NonNull
        private final RecyclerView mListView;
        @NonNull
        private final SmartRefreshLayout mRefreshLayout;

        @NonNull
        private final TabAdapter mTabAdapter;
        @NonNull
        private final AppAdapter<AppInfo> mAdapter;

        /** 当前 Tab 完整列表（搜索时从此过滤） */
        @NonNull
        private List<AppInfo> mFullList = new ArrayList<>();

        @Nullable
        private OnListener mListener;
        private int mCurrentType = TYPE_USER_APP;

        @SuppressWarnings("all")
        public Builder(@NonNull Context context) {
            super(context);
            setContentView(R.layout.app_select_dialog);

            mTitleBar = findViewById(R.id.ll_app_select_title_bar);
            mSearchBar = findViewById(R.id.ll_app_select_search_bar);
            mTitleView = findViewById(R.id.tv_app_select_title);
            mCloseView = findViewById(R.id.iv_app_select_close);
            mSearchIcon = findViewById(R.id.iv_app_select_search);
            mSearchCloseView = findViewById(R.id.iv_app_select_search_close);
            mSearchEdit = findViewById(R.id.et_app_select_search);
            mTabView = findViewById(R.id.rv_app_select_tab);
            mListView = findViewById(R.id.rv_app_select_list);
            mRefreshLayout = findViewById(R.id.srl_app_select);

            // 初始化列表适配器
            mAdapter = new AppAdapter<AppInfo>(getContext()) {

                @NonNull
                @Override
                public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    return new AppViewHolder(R.layout.app_select_item) {

                        private ImageView mIconView;
                        private TextView mNameView;
                        private TextView mPackageView;
                        private TextView mVersionView;

                        @Override
                        public void onBindView(int position) {
                            // 初始化视图（只在第一次绑定的时候）
                            if (mIconView == null) {
                                mIconView = findViewById(R.id.iv_app_select_icon);
                                mNameView = findViewById(R.id.tv_app_select_name);
                                mPackageView = findViewById(R.id.tv_app_select_package);
                                mVersionView = findViewById(R.id.tv_app_select_version);

                                itemView.setOnClickListener(v -> {
                                    int pos = getViewHolderPosition();
                                    AppInfo appInfo = getItem(pos);
                                    if (mListener != null) {
                                        mListener.onSelected(getDialog(), appInfo);
                                    }
                                    dismiss();
                                });
                            }

                            // 绑定数据
                            AppInfo appInfo = getItem(position);
                            mIconView.setImageDrawable(appInfo.getIcon());
                            mNameView.setText(appInfo.getName());
                            mPackageView.setText(appInfo.getPackageName());
                            mVersionView.setText("版本: " + appInfo.getVersionName());
                        }
                    };
                }
            };

            // 设置列表布局管理器、分割线和适配器
            mListView.setLayoutManager(new LinearLayoutManager(context));
            mListView.addItemDecoration(new DividerItemDecoration(context, DividerItemDecoration.VERTICAL));
            mListView.setAdapter(mAdapter);

            // 点击搜索图标 → 切换到搜索模式
            mSearchIcon.setOnClickListener(v -> showSearchMode(true));

            // 点击搜索栏的关闭图标 → 退出搜索模式
            mSearchCloseView.setOnClickListener(v -> showSearchMode(false));

            // 点击对话框关闭按钮
            setOnClickListener(mCloseView);

            // 搜索框输入监听
            mSearchEdit.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterList(s.toString().trim());
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });

            // 设置下拉刷新，禁用上拉加载更多（应用列表为一次性全量加载）
            mRefreshLayout.setEnableLoadMore(false);
            mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
                @Override
                public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                    loadAppList(mCurrentType);
                    refreshLayout.finishRefresh();
                }
            });

            // 初始化 Tab 选项卡
            mTabAdapter = new TabAdapter(context, TabAdapter.TAB_MODE_SLIDING, true);
            mTabAdapter.addItem("用户应用");
            mTabAdapter.addItem("系统应用");
            mTabAdapter.setOnTabListener(this);
            mTabView.setAdapter(mTabAdapter);

            // 默认加载用户应用列表
            loadAppList(TYPE_USER_APP);

            addOnShowListener(this);
            addOnDismissListener(this);
        }

        /**
         * 切换标题栏 / 搜索栏
         *
         * @param searchMode true 显示搜索栏，false 显示标题栏
         */
        private void showSearchMode(boolean searchMode) {
            if (searchMode) {
                mTitleBar.setVisibility(View.GONE);
                mSearchBar.setVisibility(View.VISIBLE);
                mSearchEdit.requestFocus();
                // 弹出键盘
                InputMethodManager imm = (InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(mSearchEdit, InputMethodManager.SHOW_IMPLICIT);
                }
            } else {
                mTitleBar.setVisibility(View.VISIBLE);
                mSearchBar.setVisibility(View.GONE);
                mSearchEdit.setText("");
                // 收起键盘
                InputMethodManager imm = (InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(mSearchEdit.getWindowToken(), 0);
                }
                // 还原完整列表
                mAdapter.setData(new ArrayList<>(mFullList));
            }
        }

        /**
         * 根据关键词过滤列表
         *
         * @param keyword 搜索关键词
         */
        private void filterList(String keyword) {
            if (keyword.isEmpty()) {
                mAdapter.setData(new ArrayList<>(mFullList));
                return;
            }
            String lower = keyword.toLowerCase(Locale.getDefault());
            List<AppInfo> filtered = new ArrayList<>();
            for (AppInfo appInfo : mFullList) {
                if (appInfo.getName().toLowerCase(Locale.getDefault()).contains(lower)
                        || appInfo.getPackageName().toLowerCase(Locale.getDefault()).contains(lower)) {
                    filtered.add(appInfo);
                }
            }
            mAdapter.setData(filtered);
        }

        public Builder setTitle(@Nullable CharSequence title) {
            mTitleView.setText(title);
            return this;
        }

        public Builder setListener(@Nullable OnListener listener) {
            mListener = listener;
            return this;
        }

        @SingleClick
        @Override
        public void onClick(@NonNull View view) {
            if (view == mCloseView) {
                dismiss();
                if (mListener != null) {
                    mListener.onCancel(getDialog());
                }
            }
        }

        /**
         * {@link TabAdapter.OnTabListener}
         */
        @Override
        public boolean onTabSelected(@NonNull RecyclerView recyclerView, int position) {
            mCurrentType = position;
            // 切 Tab 时退出搜索模式
            showSearchMode(false);
            loadAppList(position);
            return true;
        }

        /**
         * 加载应用列表
         *
         * @param type 应用类型（用户应用或系统应用）
         */
        private void loadAppList(int type) {
            mFullList = getAppList(getContext(), type);
            mAdapter.setData(new ArrayList<>(mFullList));
        }

        /**
         * {@link BaseDialog.OnShowListener}
         */
        @Override
        public void onShow(@NonNull BaseDialog dialog) {
            // do nothing
        }

        /**
         * {@link BaseDialog.OnDismissListener}
         */
        @Override
        public void onDismiss(@NonNull BaseDialog dialog) {
            // do nothing
        }
    }

    /**
     * 获取应用列表
     *
     * @param context 上下文
     * @param type    应用类型（TYPE_USER_APP 或 TYPE_SYSTEM_APP）
     * @return 应用列表
     */
    private static List<AppInfo> getAppList(@NonNull Context context, int type) {
        List<AppInfo> appList = new ArrayList<>();
        PackageManager packageManager = context.getPackageManager();
        List<PackageInfo> packages = packageManager.getInstalledPackages(0);

        for (PackageInfo packageInfo : packages) {
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;

            // 判断应用类型，不匹配则跳过
            boolean isUserApp = isUserApp(applicationInfo);
            if (type == TYPE_USER_APP && !isUserApp) {
                continue;
            }
            if (type == TYPE_SYSTEM_APP && isUserApp) {
                continue;
            }

            AppInfo appInfo = new AppInfo();
            appInfo.setIcon(applicationInfo.loadIcon(packageManager));
            appInfo.setName(applicationInfo.loadLabel(packageManager).toString());
            appInfo.setPackageName(packageInfo.packageName);
            appInfo.setVersionName(packageInfo.versionName);
            appInfo.setVersionCode(packageInfo.versionCode);

            appList.add(appInfo);
        }

        // 按应用名称排序（英文字母优先，中文按拼音）
        Collections.sort(appList, new Comparator<AppInfo>() {
            Collator collator = Collator.getInstance(Locale.CHINA);

            @Override
            public int compare(AppInfo o1, AppInfo o2) {
                String name1 = o1.getName();
                String name2 = o2.getName();
                boolean english1 = name1.matches("^[a-zA-Z].*");
                boolean english2 = name2.matches("^[a-zA-Z].*");

                // 英文优先
                if (english1 && !english2) return -1;
                if (!english1 && english2) return 1;

                // 英文 A-Z
                if (english1) return name1.compareToIgnoreCase(name2);

                // 中文拼音
                return collator.compare(name1, name2);
            }
        });
        return appList;
    }

    /**
     * 判断应用程序是否是用户程序
     * 用户应用：不是系统应用，也不是系统更新应用
     */
    private static boolean isUserApp(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                && (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    /**
     * 应用信息模型
     */
    public static final class AppInfo {

        private Drawable icon;
        private String name;
        private String packageName;
        private String versionName;
        private int versionCode;

        public Drawable getIcon() {
            return icon;
        }

        public void setIcon(Drawable icon) {
            this.icon = icon;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public String getVersionName() {
            return versionName;
        }

        public void setVersionName(String versionName) {
            this.versionName = versionName;
        }

        public int getVersionCode() {
            return versionCode;
        }

        public void setVersionCode(int versionCode) {
            this.versionCode = versionCode;
        }
    }

    public interface OnListener {

        /**
         * 选择应用后回调
         *
         * @param appInfo 应用信息
         */
        void onSelected(@NonNull BaseDialog dialog, @NonNull AppInfo appInfo);

        /**
         * 点击取消时回调
         */
        default void onCancel(@NonNull BaseDialog dialog) {
            // default implementation ignored
        }
    }
}
