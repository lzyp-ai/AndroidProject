package com.hjq.demo.ui.dialog;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hjq.base.BaseDialog;
import com.hjq.demo.R;

import java.io.File;
import java.io.IOException;
import java.util.Formatter;
import java.util.Locale;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/AndroidProject
 *    time   : 2026/08/27
 *    desc   : 音频播放底部弹框（进度条 + 播放控制）
 */
public final class AudioPlayerDialog {

    public static final class Builder
            extends BaseDialog.Builder<Builder>
            implements SeekBar.OnSeekBarChangeListener,
            MediaPlayer.OnPreparedListener,
            MediaPlayer.OnCompletionListener,
            MediaPlayer.OnErrorListener {

        private static final int PROGRESS_INTERVAL = 500;

        private final TextView mNameView;
        private final ImageView mCloseView;
        private final TextView mCurrentTimeView;
        private final SeekBar mSeekBar;
        private final TextView mDurationView;
        private final ImageView mToggleView;
        private final ImageView mRewindView;
        private final ImageView mForwardView;

        private MediaPlayer mMediaPlayer;
        private boolean mIsPrepared = false;
        private boolean mIsUserSeeking = false;
        private File mFile;

        private final Handler mHandler = new Handler(Looper.getMainLooper());
        // 用匿名 Runnable 避免 lambda 自引用初始化问题
        private final Runnable mProgressTask = new Runnable() {
            @Override
            public void run() {
                if (mMediaPlayer != null && mIsPrepared && mMediaPlayer.isPlaying() && !mIsUserSeeking) {
                    int current = mMediaPlayer.getCurrentPosition();
                    mSeekBar.setProgress(current);
                    mCurrentTimeView.setText(formatTime(current));
                }
                mHandler.postDelayed(this, PROGRESS_INTERVAL);
            }
        };

        @Nullable
        private OnListener mListener;

        public Builder(@NonNull Context context) {
            super(context);
            setContentView(R.layout.audio_player_dialog);
            setGravity(Gravity.BOTTOM);
            setAnimStyle(BaseDialog.ANIM_BOTTOM);

            mNameView = findViewById(R.id.tv_audio_player_name);
            mCloseView = findViewById(R.id.iv_audio_player_close);
            mCurrentTimeView = findViewById(R.id.tv_audio_player_current);
            mSeekBar = findViewById(R.id.sb_audio_player_progress);
            mDurationView = findViewById(R.id.tv_audio_player_duration);
            mToggleView = findViewById(R.id.iv_audio_player_toggle);
            mRewindView = findViewById(R.id.iv_audio_player_rewind);
            mForwardView = findViewById(R.id.iv_audio_player_forward);

            mSeekBar.setOnSeekBarChangeListener(this);
            setOnClickListener(mCloseView, mToggleView, mRewindView, mForwardView);

            // 弹框显示后开始准备播放
            addOnShowListener(dialog -> {
                if (mFile != null) preparePlayer();
            });

            // 关闭弹框时释放播放器
            addOnDismissListener(dialog -> releasePlayer());
        }

        public Builder setFile(@NonNull File file) {
            mFile = file;
            mNameView.setText(file.getName());
            return this;
        }

        public Builder setListener(@Nullable OnListener listener) {
            mListener = listener;
            return this;
        }

        public void startPlay() {
            if (mFile != null) {
                preparePlayer();
            }
        }

        // -----------------------------------------------------------------------
        // MediaPlayer
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
                mDurationView.setText("加载失败");
            }
        }

        private void releasePlayer() {
            mHandler.removeCallbacks(mProgressTask);
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
                mToggleView.setImageResource(R.drawable.audio_play_ic);
            } else {
                mMediaPlayer.start();
                mToggleView.setImageResource(R.drawable.audio_pause_ic);
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

        // -----------------------------------------------------------------------
        // MediaPlayer callbacks
        // -----------------------------------------------------------------------

        @Override
        public void onPrepared(MediaPlayer mp) {
            mIsPrepared = true;
            int duration = mp.getDuration();
            mSeekBar.setMax(duration > 0 ? duration : 0);
            mDurationView.setText(formatTime(duration));
            mCurrentTimeView.setText(formatTime(0));
            mp.start();
            mToggleView.setImageResource(R.drawable.audio_pause_ic);
            mHandler.post(mProgressTask);
        }

        @Override
        public void onCompletion(MediaPlayer mp) {
            mToggleView.setImageResource(R.drawable.audio_play_ic);
            mSeekBar.setProgress(0);
            mCurrentTimeView.setText(formatTime(0));
            if (mListener != null) mListener.onCompletion(getDialog());
        }

        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            mDurationView.setText("播放出错");
            return true;
        }

        // -----------------------------------------------------------------------
        // SeekBar callbacks
        // -----------------------------------------------------------------------

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) mCurrentTimeView.setText(formatTime(progress));
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
        // Click
        // -----------------------------------------------------------------------

        @Override
        public void onClick(@NonNull View view) {
            int id = view.getId();
            if (id == R.id.iv_audio_player_close) {
                dismiss();
            } else if (id == R.id.iv_audio_player_toggle) {
                togglePlayPause();
            } else if (id == R.id.iv_audio_player_rewind) {
                seekBy(-15_000);
            } else if (id == R.id.iv_audio_player_forward) {
                seekBy(15_000);
            }
        }

        // -----------------------------------------------------------------------
        // 工具
        // -----------------------------------------------------------------------

        private String formatTime(int ms) {
            if (ms < 0) ms = 0;
            int totalSeconds = ms / 1000;
            StringBuilder sb = new StringBuilder();
            new Formatter(sb, Locale.getDefault())
                    .format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
            return sb.toString();
        }
    }

    public interface OnListener {
        /** 播放完成回调 */
        default void onCompletion(@NonNull BaseDialog dialog) {}
    }
}
