package com.hjq.demo.ui.dialog.common;

import android.content.Context;
import android.text.Editable;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hjq.base.BaseDialog;
import com.hjq.custom.widget.view.RegexEditText;
import com.hjq.demo.R;
import com.hjq.demo.aop.SingleClick;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/AndroidProject
 *    time   : 2024/01/01
 *    desc   : 添加快捷方式弹框（名称 + 网址两个输入框）
 */
public final class AddShortcutDialog {

    public static final class Builder
            extends StyleDialog.Builder<Builder>
            implements BaseDialog.OnShowListener {

        @NonNull
        private final RegexEditText mNameView;
        @NonNull
        private final RegexEditText mUrlView;

        @Nullable
        private OnListener mListener;

        public Builder(@NonNull Context context) {
            super(context);
            setCustomView(R.layout.add_shortcut_dialog);

            mNameView = findViewById(R.id.et_shortcut_name);
            mUrlView = findViewById(R.id.et_shortcut_url);

            addOnShowListener(this);
        }

        public Builder setName(CharSequence text) {
            mNameView.setText(text);
            return this;
        }

        public Builder setUrl(CharSequence text) {
            mUrlView.setText(text);
            if (text != null && text.length() > 0) {
                mUrlView.setSelection(text.length());
            }
            return this;
        }

        public Builder setListener(@Nullable OnListener listener) {
            mListener = listener;
            return this;
        }

        @Override
        public void onShow(@NonNull BaseDialog dialog) {
            // 打开时自动聚焦名称输入框
            postDelayed(() -> showKeyboard(mNameView), 300);
        }

        @SingleClick
        @Override
        public void onClick(@NonNull View view) {
            int viewId = view.getId();
            if (viewId == R.id.tv_ui_confirm) {
                Editable nameEditable = mNameView.getText();
                Editable urlEditable = mUrlView.getText();
                String name = nameEditable != null ? nameEditable.toString().trim() : "";
                String url = urlEditable != null ? urlEditable.toString().trim() : "";
                performClickDismiss();
                if (mListener != null) {
                    mListener.onConfirm(getDialog(), name, url);
                }
            } else if (viewId == R.id.tv_ui_cancel) {
                performClickDismiss();
                if (mListener != null) {
                    mListener.onCancel(getDialog());
                }
            }
        }
    }

    public interface OnListener {

        /**
         * 点击完成时回调
         */
        void onConfirm(@NonNull BaseDialog dialog, @NonNull String name, @NonNull String url);

        /**
         * 点击取消时回调
         */
        default void onCancel(@NonNull BaseDialog dialog) {
        }
    }
}
