package com.hjq.demo.ui.activity.common;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.hjq.bar.TitleBar;
import com.hjq.demo.R;
import com.hjq.demo.app.AppActivity;

import java.io.File;
import java.io.IOException;
import java.util.Formatter;
import java.util.Locale;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/AndroidProject
 *    time   : 2026/08/27
 *    desc   : 音频播放页面
 */
public final class AudioPlayerActivity extends AppActivity
        implements SeekBar.OnSeekBarChangeListener,
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener {

    private static final String INTENT_KEY_FILE_PATH = "filePath";

    /** 进度刷新间隔 ms */
    private static final int PROGRESS_INTERVAL = 500;

    public static void start(@NonNull Context context, @NonNull File file) {
        Intent intent = new Intent(context, AudioPlayerActivity.class);
        intent.putExtra(INTENT_KEY_FILE_PATH, file.getAbsolutePath());
        context.startActivity(intent);
    }

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------
    private TitleBar mTitleBar;
    private TextView mNameView;
    private TextView mPathView;
    private SeekBar mSeekBar;
    private TextView mCurrentTimeView;
    private TextView mDurationView;
    private ImageView mToggleView;
    private ImageView mRewindView;
    private ImageView mForwardView;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private MediaPlayer mMediaPlayer;
    private File mFile;
    private boolean mIsPrepared = false;
    private boolean mIsUserSeeking = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mProgressTask = new Runnable() {
        @Override
        public void run() {
            if (mMediaPlayer != null && mIsPrepared && mMediaPlayer.isPlaying() && !mIsUserSeeking) {
                int current = mMediaPlayer.getCurrentPosition();
                int duration = mMediaPlayer.getDuration();
                mSeekBar.setProgress(current);
                mCurrentTimeView.setText(formatTime(current));
                mDurationView.setText(formatTime(duration));
            }
            mHandler.postDelayed(this, PROGRESS_INTERVAL);
        }
    };

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected int getLayoutId() {
        return R.layout.audio_player_activity;
    }

    @Override
    protected void initView() {
        mTitleBar = findViewById(R.id.tb_audio_player_title);
        mNameView = findViewById(R.id.tv_audio_player_name);
        mPathView = findViewById(R.id.tv_audio_player_path);
        mSeekBar = findViewById(R.id.sb_audio_player_progress);
        mCurrentTimeView = findViewById(R.id.tv_audio_player_current);
        mDurationView = findViewById(R.id.tv_audio_player_duration);
        mToggleView = findViewById(R.id.iv_audio_player_toggle);
        mRewindView = findViewById(R.id.iv_audio_player_rewind);
        mForwardView = findViewById(R.id.iv_audio_player_forward);

        mSeekBar.setOnSeekBarChangeListener(this);
        mToggleView.setOnClickListener(v -> togglePlayPause());
        mRewindView.setOnClickListener(v -> seekBy(-15_000));
        mForwardView.setOnClickListener(v -> seekBy(15_000));

        // 触发 marquee 滚动效果
        mNameView.setSelected(true);
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

        // 显示文件信息
        String name = mFile.getName();
        // 去掉扩展名作为标题
        int dotIdx = name.lastIndexOf('.');
        mTitleBar.setTitle(dotIdx > 0 ? name.substring(0, dotIdx) : name);
        mNameView.setText(name);
        mPathView.setText(mFile.getParent());

        preparePlayer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
            updateToggleIcon(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mProgressTask);
        releasePlayer();
    }

    // -----------------------------------------------------------------------
    // MediaPlayer 准备与控制
    // -----------------------------------------------------------------------

    private void preparePlayer() {
        try {
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setDataSource(mFile.getAbsolutePath());
            mMediaPlayer.setOnPreparedListener(this);
            mMediaPlayer.setOnCompletionListener(this);
            mMediaPlayer.setOnErrorListener(this);
            mMediaPlayer.prepareAsync();
        } catch (IOException e) {
            toast("加载失败：" + e.getMessage());
            finish();
        }
    }

    private void releasePlayer() {
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
            } catch (Exception ignored) {}
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        mIsPrepared = false;
    }

    private void togglePlayPause() {
        if (!mIsPrepared || mMediaPlayer == null) return;
        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
            updateToggleIcon(false);
        } else {
            mMediaPlayer.start();
            updateToggleIcon(true);
        }
    }

    private void seekBy(int deltaMs) {
        if (!mIsPrepared || mMediaPlayer == null) return;
        int target = mMediaPlayer.getCurrentPosition() + deltaMs;
        target = Math.max(0, Math.min(target, mMediaPlayer.getDuration()));
        mMediaPlayer.seekTo(target);
        mSeekBar.setProgress(target);
        mCurrentTimeView.setText(formatTime(target));
    }

    private void updateToggleIcon(boolean isPlaying) {
        mToggleView.setImageResource(isPlaying ? R.drawable.audio_pause_ic : R.drawable.audio_play_ic);
    }

    // -----------------------------------------------------------------------
    // MediaPlayer.OnPreparedListener
    // -----------------------------------------------------------------------

    @Override
    public void onPrepared(MediaPlayer mp) {
        mIsPrepared = true;
        int duration = mp.getDuration();
        mSeekBar.setMax(duration > 0 ? duration : 0);
        mDurationView.setText(formatTime(duration));
        mCurrentTimeView.setText(formatTime(0));
        // 自动播放
        mp.start();
        updateToggleIcon(true);
        mHandler.post(mProgressTask);
    }

    // -----------------------------------------------------------------------
    // MediaPlayer.OnCompletionListener
    // -----------------------------------------------------------------------

    @Override
    public void onCompletion(MediaPlayer mp) {
        updateToggleIcon(false);
        mSeekBar.setProgress(0);
        mCurrentTimeView.setText(formatTime(0));
    }

    // -----------------------------------------------------------------------
    // MediaPlayer.OnErrorListener
    // -----------------------------------------------------------------------

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        toast("播放出错（" + what + "，" + extra + "），该格式可能不受支持");
        finish();
        return true;
    }

    // -----------------------------------------------------------------------
    // SeekBar.OnSeekBarChangeListener
    // -----------------------------------------------------------------------

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            mCurrentTimeView.setText(formatTime(progress));
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        mIsUserSeeking = true;
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        mIsUserSeeking = false;
        if (mIsPrepared && mMediaPlayer != null) {
            mMediaPlayer.seekTo(seekBar.getProgress());
        }
    }

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    private String formatTime(int ms) {
        if (ms < 0) ms = 0;
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        Formatter formatter = new Formatter(sb, Locale.getDefault());
        formatter.format("%02d:%02d", minutes, seconds);
        return sb.toString();
    }
}
