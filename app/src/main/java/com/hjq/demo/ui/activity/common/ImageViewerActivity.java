package com.hjq.demo.ui.activity.common;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.github.chrisbanes.photoview.PhotoView;
import com.hjq.bar.TitleBar;
import com.hjq.demo.R;
import com.hjq.demo.app.AppActivity;
import com.hjq.demo.http.glide.GlideApp;

import java.io.File;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/AndroidProject
 *    time   : 2026/08/27
 *    desc   : 图片查看页面（带 TitleBar，支持双指缩放）
 */
public final class ImageViewerActivity extends AppActivity {

    private static final String INTENT_KEY_FILE_PATH = "filePath";

    public static void start(@NonNull Context context, @NonNull File file) {
        Intent intent = new Intent(context, ImageViewerActivity.class);
        intent.putExtra(INTENT_KEY_FILE_PATH, file.getAbsolutePath());
        context.startActivity(intent);
    }

    private TitleBar mTitleBar;
    private PhotoView mPhotoView;

    @Override
    protected int getLayoutId() {
        return R.layout.image_viewer_activity;
    }

    @Override
    protected void initView() {
        mTitleBar = findViewById(R.id.tb_image_viewer_title);
        mPhotoView = findViewById(R.id.pv_image_viewer_photo);
    }

    @Override
    protected void initData() {
        String filePath = getString(INTENT_KEY_FILE_PATH);
        if (filePath == null) {
            toast("文件路径无效");
            finish();
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            toast("图片文件不存在");
            finish();
            return;
        }

        // 标题栏显示文件名
        mTitleBar.setTitle(file.getName());

        // Glide 加载本地图片
        GlideApp.with(this)
                .load(file)
                .into(mPhotoView);
    }
}
