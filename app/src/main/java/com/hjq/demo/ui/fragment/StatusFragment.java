package com.hjq.demo.ui.fragment;

import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.hjq.base.BaseAdapter;
import com.hjq.custom.widget.layout.WrapRecyclerView;
import com.hjq.demo.R;
import com.hjq.demo.app.AppActivity;
import com.hjq.demo.app.TitleBarFragment;
import com.hjq.demo.ui.adapter.StatusAdapter;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;
import com.scwang.smart.refresh.layout.api.RefreshLayout;
import com.scwang.smart.refresh.layout.listener.OnRefreshLoadMoreListener;
import java.util.ArrayList;
import java.util.List;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/AndroidProject
 *    time   : 2020/07/10
 *    desc   : 加载案例 Fragment
 */
public final class StatusFragment extends TitleBarFragment<AppActivity>
        implements OnRefreshLoadMoreListener,
        BaseAdapter.OnItemClickListener {

    /**
     * 创建 Fragment 实例的静态工厂方法
     *
     * @return StatusFragment 实例
     */
    public static StatusFragment newInstance() {
        return new StatusFragment();
    }

    /** 下拉刷新 / 上拉加载布局 */
    private SmartRefreshLayout mRefreshLayout;
    /** 支持添加头部和尾部的 RecyclerView */
    private WrapRecyclerView mRecyclerView;

    /** 列表适配器 */
    private StatusAdapter mAdapter;

    /**
     * 获取布局资源 ID
     *
     * @return 布局文件 ID
     */
    @Override
    protected int getLayoutId() {
        return R.layout.status_fragment;
    }

    /**
     * 初始化 View：绑定控件、设置适配器、添加头尾布局、设置刷新监听
     */
    @Override
    protected void initView() {
        // 获取刷新布局和 RecyclerView 实例
        mRefreshLayout = findViewById(R.id.rl_status_refresh);
        mRecyclerView = findViewById(R.id.rv_status_list);

        // 初始化适配器并设置到 RecyclerView
        mAdapter = new StatusAdapter(mRecyclerView.getContext());
        mAdapter.setOnItemClickListener(this);
        mRecyclerView.setAdapter(mAdapter);

        // 添加头部视图
        TextView headerView = mRecyclerView.addHeaderView(R.layout.picker_item);
        if (headerView != null) {
            headerView.setText("我是头部");
            headerView.setOnClickListener(v -> toast("点击了头部"));
        }

        // 添加尾部视图
        TextView footerView = mRecyclerView.addFooterView(R.layout.picker_item);
        if (footerView != null) {
            footerView.setText("我是尾部");
            footerView.setOnClickListener(v -> toast("点击了尾部"));
        }

        // 设置下拉刷新和上拉加载的监听器
        mRefreshLayout.setOnRefreshLoadMoreListener(this);
    }

    /**
     * 初始化数据：加载模拟数据并设置到适配器
     */
    @Override
    protected void initData() {
        mAdapter.setData(analogData());
    }

    /**
     * 模拟数据
     */
    private List<String> analogData() {
        List<String> data = new ArrayList<>();
        for (int i = mAdapter.getCount(); i < mAdapter.getCount() + 20; i++) {
            data.add("我是第 " + i + " 条目");
        }
        return data;
    }

    /**
     * {@link BaseAdapter.OnItemClickListener}
     *
     * @param recyclerView      RecyclerView对象
     * @param itemView          被点击的条目对象
     * @param position          被点击的条目位置
     */
    @Override
    public void onItemClick(@NonNull RecyclerView recyclerView, @NonNull View itemView, int position) {
        toast(mAdapter.getItem(position));
    }

    /**
     * {@link OnRefreshLoadMoreListener}
     */

    /**
     * 下拉刷新回调：清空数据并重新加载模拟数据，延迟1秒模拟网络请求
     *
     * @param refreshLayout 刷新布局
     */
    @Override
    public void onRefresh(@NonNull RefreshLayout refreshLayout) {
        postDelayed(() -> {
            // 清空旧数据，重新生成并设置模拟数据
            mAdapter.clearData();
            mAdapter.setData(analogData());
            // 结束刷新动画
            mRefreshLayout.finishRefresh();
        }, 1000);
    }

    /**
     * 上拉加载更多回调：追加模拟数据，条目数超过 100 时标记为最后一页
     *
     * @param refreshLayout 刷新布局
     */
    @Override
    public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
        postDelayed(() -> {
            // 追加新的模拟数据
            mAdapter.addData(analogData());
            // 结束加载动画
            mRefreshLayout.finishLoadMore();

            // 当条目数达到或超过 100 时，标记为最后一页，禁用加载更多
            mAdapter.setLastPage(mAdapter.getCount() >= 100);
            mRefreshLayout.setNoMoreData(mAdapter.isLastPage());
        }, 1000);
    }
}