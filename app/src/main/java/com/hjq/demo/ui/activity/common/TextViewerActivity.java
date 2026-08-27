package com.hjq.demo.ui.activity.common;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.AsyncTask;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.hjq.bar.OnTitleBarListener;
import com.hjq.bar.TitleBar;
import com.hjq.demo.R;
import com.hjq.demo.app.AppActivity;
import com.hjq.demo.ui.dialog.common.MenuDialog;
import com.hjq.demo.ui.dialog.common.MessageDialog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/AndroidProject
 *    time   : 2026/08/27
 *    desc   : 文本查看/编辑页面（参考 Markor 设计）
 *             - 只读模式默认打开，点击"编辑"切换为可编辑
 *             - 支持撤销/重做
 *             - 支持编码切换重新加载
 *             - 支持文本内查找（上一处/下一处高亮）
 *             - 编辑模式下 TitleBar 右侧显示"保存"按钮
 */
public final class TextViewerActivity extends AppActivity {

    private static final String INTENT_KEY_FILE_PATH = "filePath";
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024; // 2 MB
    private static final String[] ENCODINGS = {
            "UTF-8", "GBK", "GB2312", "GB18030", "ISO-8859-1", "UTF-16"
    };

    public static void start(@NonNull Context context, @NonNull File file) {
        Intent intent = new Intent(context, TextViewerActivity.class);
        intent.putExtra(INTENT_KEY_FILE_PATH, file.getAbsolutePath());
        context.startActivity(intent);
    }

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------
    private TitleBar mTitleBar;
    private TextView mEncodingView;
    private TextView mPositionView;
    private TextView mUndoView;
    private TextView mRedoView;
    private TextView mModeView;
    private LinearLayout mSearchLayout;
    private EditText mSearchInput;
    private TextView mSearchResult;
    private TextView mSearchPrev;
    private TextView mSearchNext;
    private TextView mSearchClose;
    private ScrollView mScrollView;
    private EditText mContentEditor;
    private FrameLayout mLoadingView;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private File mFile;
    private String mCurrentEncoding = "UTF-8";
    private boolean mIsEditMode = false;
    private boolean mIsModified = false;
    // true=右侧按钮为"保存"，false=右侧按钮为"查找"
    private boolean mRightIsSave = false;
    private LoadTask mLoadTask;

    // 撤销/重做栈
    private final List<String> mUndoStack = new ArrayList<>();
    private final List<String> mRedoStack = new ArrayList<>();
    private boolean mIsUndoRedo = false; // 防止撤销操作本身触发 TextWatcher

    // 查找相关
    private String mLastSearchQuery = "";
    private final List<Integer> mSearchPositions = new ArrayList<>();
    private int mSearchIndex = -1;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected int getLayoutId() {
        return R.layout.text_viewer_activity;
    }

    @Override
    protected void initView() {
        mTitleBar = findViewById(R.id.tb_text_viewer_title);
        mEncodingView = findViewById(R.id.tv_text_viewer_encoding);
        mPositionView = findViewById(R.id.tv_text_viewer_position);
        mUndoView = findViewById(R.id.tv_text_viewer_undo);
        mRedoView = findViewById(R.id.tv_text_viewer_redo);
        mModeView = findViewById(R.id.tv_text_viewer_mode);
        mSearchLayout = findViewById(R.id.ll_text_viewer_search);
        mSearchInput = findViewById(R.id.et_text_viewer_search);
        mSearchResult = findViewById(R.id.tv_text_viewer_search_result);
        mSearchPrev = findViewById(R.id.tv_text_viewer_search_prev);
        mSearchNext = findViewById(R.id.tv_text_viewer_search_next);
        mSearchClose = findViewById(R.id.tv_text_viewer_search_close);
        mScrollView = findViewById(R.id.sv_text_viewer_scroll);
        mContentEditor = findViewById(R.id.et_text_viewer_content);
        mLoadingView = findViewById(R.id.fl_text_viewer_loading);

        // 编码切换
        mEncodingView.setOnClickListener(v -> showEncodingMenu());

        // 撤销/重做
        mUndoView.setOnClickListener(v -> performUndo());
        mRedoView.setOnClickListener(v -> performRedo());

        // 只读/编辑 切换
        mModeView.setOnClickListener(v -> toggleEditMode());

        // 查找栏
        mSearchClose.setOnClickListener(v -> hideSearch());
        mSearchPrev.setOnClickListener(v -> navigateSearch(false));
        mSearchNext.setOnClickListener(v -> navigateSearch(true));
        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(mSearchInput.getText().toString());
                return true;
            }
            return false;
        });
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!TextUtils.isEmpty(s)) performSearch(s.toString());
                else clearSearchHighlight();
            }
        });

        // TitleBar 右侧"查找"按钮，通过 OnTitleBarListener 统一处理
        mTitleBar.setRightTitle("查找");
        mTitleBar.setOnTitleBarListener(new OnTitleBarListener() {
            @Override
            public void onLeftClick(@NonNull TitleBar titleBar) {
                // 左侧返回按钮触发返回逻辑
                onBackPressed();
            }

            @Override
            public void onRightClick(@NonNull TitleBar titleBar) {
                if (mRightIsSave) {
                    saveFile();
                } else {
                    toggleSearch();
                }
            }
        });

        // 光标位置监听
        mContentEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!mIsUndoRedo) {
                    pushUndo(s.toString());
                    mRedoStack.clear();
                    mIsModified = true;
                    updateUndoRedoState();
                }
                updatePositionInfo();
            }
        });

        updateUndoRedoState();
    }

    @Override
    protected void initData() {
        String filePath = getString(INTENT_KEY_FILE_PATH);
        if (filePath == null) {
            toast("文件路径无效");
            finish();
            return;
        }
        mFile = new File(filePath);
        if (!mFile.exists() || !mFile.isFile()) {
            toast("文件不存在");
            finish();
            return;
        }
        mTitleBar.setTitle(mFile.getName());
        loadFile(mCurrentEncoding);
    }

    @Override
    public void onBackPressed() {
        if (mIsModified && mIsEditMode) {
            new MessageDialog.Builder(this)
                    .setTitle("退出确认")
                    .setMessage("文件已修改但未保存，是否保存后退出？")
                    .setConfirm("保存退出")
                    .setCancel("不保存")
                    .setListener(new MessageDialog.OnListener() {
                        @Override
                        public void onConfirm(@NonNull com.hjq.base.BaseDialog dialog) {
                            saveFile();
                            finish();
                        }

                        @Override
                        public void onCancel(@NonNull com.hjq.base.BaseDialog dialog) {
                            finish();
                        }
                    })
                    .show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mLoadTask != null) mLoadTask.cancel(true);
    }

    // -----------------------------------------------------------------------
    // 编码切换
    // -----------------------------------------------------------------------

    private void showEncodingMenu() {
        new MenuDialog.Builder(this)
                .setList(ENCODINGS)
                .setListener(new MenuDialog.OnListener<String>() {
                    @Override
                    public void onSelected(@NonNull com.hjq.base.BaseDialog dialog,
                            int position, String data) {
                        if (!data.equals(mCurrentEncoding)) {
                            mCurrentEncoding = data;
                            mEncodingView.setText(data);
                            loadFile(mCurrentEncoding);
                        }
                    }
                })
                .show();
    }

    // -----------------------------------------------------------------------
    // 只读 / 编辑 模式切换
    // -----------------------------------------------------------------------

    private void toggleEditMode() {
        mIsEditMode = !mIsEditMode;
        mContentEditor.setEnabled(mIsEditMode);
        mContentEditor.setFocusable(mIsEditMode);
        mContentEditor.setFocusableInTouchMode(mIsEditMode);

        if (mIsEditMode) {
            mModeView.setText("只读");
            // 右侧改为"保存"按钮
            mRightIsSave = true;
            mTitleBar.setRightTitle("保存");
            mContentEditor.requestFocus();
        } else {
            mModeView.setText("编辑");
            mRightIsSave = false;
            mTitleBar.setRightTitle("查找");
            hideKeyboard(mContentEditor);
        }
    }

    // -----------------------------------------------------------------------
    // 保存文件
    // -----------------------------------------------------------------------

    private void saveFile() {
        if (mFile == null || !mIsModified) return;
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(mFile),
                        Charset.forName(mCurrentEncoding)))) {
            writer.write(mContentEditor.getText().toString());
            mIsModified = false;
            toast("保存成功");
        } catch (IOException e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 撤销 / 重做
    // -----------------------------------------------------------------------

    private void pushUndo(String text) {
        // 避免连续相同内容重复入栈
        if (!mUndoStack.isEmpty() && mUndoStack.get(mUndoStack.size() - 1).equals(text)) return;
        mUndoStack.add(text);
        // 栈深度限制为 50
        if (mUndoStack.size() > 50) mUndoStack.remove(0);
    }

    private void performUndo() {
        if (mUndoStack.size() < 2) return;
        mIsUndoRedo = true;
        // 当前状态压入重做栈
        mRedoStack.add(mUndoStack.remove(mUndoStack.size() - 1));
        String prev = mUndoStack.get(mUndoStack.size() - 1);
        mContentEditor.setText(prev);
        mContentEditor.setSelection(Math.min(prev.length(), mContentEditor.getSelectionEnd()));
        mIsModified = true;
        mIsUndoRedo = false;
        updateUndoRedoState();
    }

    private void performRedo() {
        if (mRedoStack.isEmpty()) return;
        mIsUndoRedo = true;
        String next = mRedoStack.remove(mRedoStack.size() - 1);
        mUndoStack.add(next);
        mContentEditor.setText(next);
        mContentEditor.setSelection(Math.min(next.length(), mContentEditor.getSelectionEnd()));
        mIsModified = true;
        mIsUndoRedo = false;
        updateUndoRedoState();
    }

    private void updateUndoRedoState() {
        mUndoView.setEnabled(mUndoStack.size() >= 2);
        mRedoView.setEnabled(!mRedoStack.isEmpty());
        mUndoView.setAlpha(mUndoStack.size() >= 2 ? 1.0f : 0.4f);
        mRedoView.setAlpha(!mRedoStack.isEmpty() ? 1.0f : 0.4f);
    }

    // -----------------------------------------------------------------------
    // 查找
    // -----------------------------------------------------------------------

    private void toggleSearch() {
        if (mSearchLayout.getVisibility() == View.VISIBLE) {
            hideSearch();
        } else {
            mSearchLayout.setVisibility(View.VISIBLE);
            mSearchInput.requestFocus();
            showKeyboard(mSearchInput);
        }
    }

    private void hideSearch() {
        mSearchLayout.setVisibility(View.GONE);
        clearSearchHighlight();
        hideKeyboard(mSearchInput);
    }

    private void performSearch(String query) {
        if (TextUtils.isEmpty(query)) {
            clearSearchHighlight();
            return;
        }
        mLastSearchQuery = query;
        mSearchPositions.clear();
        mSearchIndex = -1;

        String content = mContentEditor.getText().toString();
        String lowerContent = content.toLowerCase();
        String lowerQuery = query.toLowerCase();

        int start = 0;
        while (true) {
            int idx = lowerContent.indexOf(lowerQuery, start);
            if (idx == -1) break;
            mSearchPositions.add(idx);
            start = idx + 1;
        }

        if (mSearchPositions.isEmpty()) {
            mSearchResult.setText("无匹配");
            clearSearchHighlight();
        } else {
            mSearchIndex = 0;
            highlightSearch();
        }
    }

    private void navigateSearch(boolean next) {
        if (mSearchPositions.isEmpty()) return;
        if (next) {
            mSearchIndex = (mSearchIndex + 1) % mSearchPositions.size();
        } else {
            mSearchIndex = (mSearchIndex - 1 + mSearchPositions.size()) % mSearchPositions.size();
        }
        highlightSearch();
    }

    private void highlightSearch() {
        if (mSearchPositions.isEmpty() || mSearchIndex < 0) return;
        String content = mContentEditor.getText().toString();
        SpannableString spannable = new SpannableString(content);
        int queryLen = mLastSearchQuery.length();

        // 所有匹配项黄色高亮
        for (int pos : mSearchPositions) {
            spannable.setSpan(new BackgroundColorSpan(Color.parseColor("#FFFF00")),
                    pos, pos + queryLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // 当前匹配项橙色高亮
        int curPos = mSearchPositions.get(mSearchIndex);
        spannable.setSpan(new BackgroundColorSpan(Color.parseColor("#FF9800")),
                curPos, curPos + queryLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        mIsUndoRedo = true;
        mContentEditor.setText(spannable);
        mIsUndoRedo = false;

        // 滚动到当前匹配行
        int line = mContentEditor.getLayout() != null
                ? mContentEditor.getLayout().getLineForOffset(curPos) : 0;
        int lineY = mContentEditor.getLayout() != null
                ? mContentEditor.getLayout().getLineTop(line) : 0;
        mScrollView.smoothScrollTo(0, Math.max(0, lineY - 200));

        mSearchResult.setText((mSearchIndex + 1) + "/" + mSearchPositions.size());
    }

    private void clearSearchHighlight() {
        mSearchPositions.clear();
        mSearchIndex = -1;
        mSearchResult.setText("");
        // 还原纯文本（去掉 span）
        CharSequence text = mContentEditor.getText();
        if (text instanceof Spannable) {
            mIsUndoRedo = true;
            mContentEditor.setText(text.toString());
            mIsUndoRedo = false;
        }
    }

    // -----------------------------------------------------------------------
    // 光标位置信息
    // -----------------------------------------------------------------------

    private void updatePositionInfo() {
        int selStart = mContentEditor.getSelectionStart();
        if (selStart < 0) return;
        String text = mContentEditor.getText().toString();
        if (selStart > text.length()) selStart = text.length();
        int line = 1;
        for (int i = 0; i < selStart; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        int totalLines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') totalLines++;
        }
        mPositionView.setText("第 " + line + " 行 / 共 " + totalLines + " 行");
    }

    // -----------------------------------------------------------------------
    // 异步加载文件
    // -----------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void loadFile(String encoding) {
        if (mLoadTask != null) mLoadTask.cancel(true);
        mScrollView.setVisibility(View.GONE);
        mLoadingView.setVisibility(View.VISIBLE);
        mUndoStack.clear();
        mRedoStack.clear();
        mIsModified = false;
        mLoadTask = new LoadTask(encoding);
        mLoadTask.execute();
    }

    @SuppressWarnings("deprecation")
    private final class LoadTask extends AsyncTask<Void, Void, String> {

        private final String mEncoding;
        private String mWarning;

        LoadTask(String encoding) {
            mEncoding = encoding;
        }

        @Override
        protected String doInBackground(Void... voids) {
            if (mFile.length() > MAX_FILE_SIZE) {
                mWarning = "文件超过 2 MB，仅显示前 2 MB 内容";
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(mFile),
                            Charset.forName(mEncoding)))) {
                char[] buf = new char[8192];
                int read;
                long total = 0;
                while ((read = reader.read(buf)) != -1) {
                    if (isCancelled()) return null;
                    sb.append(buf, 0, read);
                    total += read;
                    if (total >= MAX_FILE_SIZE) break;
                }
            } catch (IOException e) {
                mWarning = "读取失败：" + e.getMessage();
                return "";
            }
            return sb.toString();
        }

        @Override
        protected void onPostExecute(String content) {
            if (isCancelled() || isFinishing()) return;
            mLoadingView.setVisibility(View.GONE);
            mScrollView.setVisibility(View.VISIBLE);

            if (content == null) return;
            if (mWarning != null) toast(mWarning);

            mIsUndoRedo = true;
            mContentEditor.setText(content);
            mIsUndoRedo = false;

            // 初始状态压入撤销栈
            mUndoStack.clear();
            mUndoStack.add(content);
            updateUndoRedoState();
            updatePositionInfo();
        }
    }
}
