package com.hjq.demo.ui.dialog.common;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.hjq.base.BaseDialog;
import com.hjq.core.recycler.PickerLayoutManager;
import com.hjq.demo.R;
import com.hjq.demo.aop.SingleClick;
import com.hjq.demo.app.AppAdapter;

import java.util.ArrayList;
import java.util.Calendar;

/**
 *    author : Assistant
 *    time   : 2026/03/28
 *    desc   : 时间范围选择对话框
 */
public final class TimeRangeDialog {

    public static final class Builder
            extends StyleDialog.Builder<Builder> {

        @NonNull
        private final RecyclerView mStartHourView;
        @NonNull
        private final RecyclerView mStartMinuteView;
        @NonNull
        private final RecyclerView mStartSecondView;

        @NonNull
        private final RecyclerView mEndHourView;
        @NonNull
        private final RecyclerView mEndMinuteView;
        @NonNull
        private final RecyclerView mEndSecondView;

        @NonNull
        private final PickerLayoutManager mStartHourManager;
        @NonNull
        private final PickerLayoutManager mStartMinuteManager;
        @NonNull
        private final PickerLayoutManager mStartSecondManager;

        @NonNull
        private final PickerLayoutManager mEndHourManager;
        @NonNull
        private final PickerLayoutManager mEndMinuteManager;
        @NonNull
        private final PickerLayoutManager mEndSecondManager;

        @NonNull
        private final PickerAdapter mStartHourAdapter;
        @NonNull
        private final PickerAdapter mStartMinuteAdapter;
        @NonNull
        private final PickerAdapter mStartSecondAdapter;

        @NonNull
        private final PickerAdapter mEndHourAdapter;
        @NonNull
        private final PickerAdapter mEndMinuteAdapter;
        @NonNull
        private final PickerAdapter mEndSecondAdapter;

        @Nullable
        private OnListener mListener;

        private boolean mIgnoreMinute = false;
        private boolean mIgnoreSecond = false;

        @SuppressWarnings("all")
        public Builder(@NonNull Context context) {
            super(context);
            setCustomView(R.layout.time_range_dialog);
            setTitle(R.string.time_range_title);

            // 开始时间
            mStartHourView = findViewById(R.id.rv_start_hour);
            mStartMinuteView = findViewById(R.id.rv_start_minute);
            mStartSecondView = findViewById(R.id.rv_start_second);

            // 结束时间
            mEndHourView = findViewById(R.id.rv_end_hour);
            mEndMinuteView = findViewById(R.id.rv_end_minute);
            mEndSecondView = findViewById(R.id.rv_end_second);

            // 初始化Adapter
            mStartHourAdapter = new PickerAdapter(context);
            mStartMinuteAdapter = new PickerAdapter(context);
            mStartSecondAdapter = new PickerAdapter(context);

            mEndHourAdapter = new PickerAdapter(context);
            mEndMinuteAdapter = new PickerAdapter(context);
            mEndSecondAdapter = new PickerAdapter(context);

            // 生成小时数据
            ArrayList<String> hourData = new ArrayList<>(24);
            for (int i = 0; i <= 23; i++) {
                hourData.add((i < 10 ? "0" : "") + i + " " + getString(R.string.common_hour));
            }

            // 生成分钟数据
            ArrayList<String> minuteData = new ArrayList<>(60);
            for (int i = 0; i <= 59; i++) {
                minuteData.add((i < 10 ? "0" : "") + i + " " + getString(R.string.common_minute));
            }

            // 生成秒钟数据
            ArrayList<String> secondData = new ArrayList<>(60);
            for (int i = 0; i <= 59; i++) {
                secondData.add((i < 10 ? "0" : "") + i + " " + getString(R.string.common_second));
            }

            // 开始时间设置数据
            mStartHourAdapter.setData(hourData);
            mStartMinuteAdapter.setData(minuteData);
            mStartSecondAdapter.setData(secondData);

            // 结束时间设置数据
            mEndHourAdapter.setData(hourData);
            mEndMinuteAdapter.setData(minuteData);
            mEndSecondAdapter.setData(secondData);

            // 开始时间LayoutManager
            mStartHourManager = new PickerLayoutManager.Builder(context).build();
            mStartMinuteManager = new PickerLayoutManager.Builder(context).build();
            mStartSecondManager = new PickerLayoutManager.Builder(context).build();

            // 结束时间LayoutManager
            mEndHourManager = new PickerLayoutManager.Builder(context).build();
            mEndMinuteManager = new PickerLayoutManager.Builder(context).build();
            mEndSecondManager = new PickerLayoutManager.Builder(context).build();

            // 开始时间设置LayoutManager
            mStartHourView.setLayoutManager(mStartHourManager);
            mStartMinuteView.setLayoutManager(mStartMinuteManager);
            mStartSecondView.setLayoutManager(mStartSecondManager);

            // 结束时间设置LayoutManager
            mEndHourView.setLayoutManager(mEndHourManager);
            mEndMinuteView.setLayoutManager(mEndMinuteManager);
            mEndSecondView.setLayoutManager(mEndSecondManager);

            // 开始时间设置Adapter
            mStartHourView.setAdapter(mStartHourAdapter);
            mStartMinuteView.setAdapter(mStartMinuteAdapter);
            mStartSecondView.setAdapter(mStartSecondAdapter);

            // 结束时间设置Adapter
            mEndHourView.setAdapter(mEndHourAdapter);
            mEndMinuteView.setAdapter(mEndMinuteAdapter);
            mEndSecondView.setAdapter(mEndSecondAdapter);

            // 设置默认时间
            Calendar calendar = Calendar.getInstance();
            int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
            int currentMinute = calendar.get(Calendar.MINUTE);
            int currentSecond = calendar.get(Calendar.SECOND);

            // 开始时间设置为当前时间
            setStartHour(currentHour);
            setStartMinute(currentMinute);
            setStartSecond(currentSecond);

            // 结束时间设置为当前时间
            setEndHour(currentHour);
            setEndMinute(currentMinute);
            setEndSecond(currentSecond);
        }

        public Builder setListener(@Nullable OnListener listener) {
            mListener = listener;
            return this;
        }

        /**
         * 不选择秒数
         */
        public Builder setIgnoreSecond() {
            mStartSecondView.setVisibility(View.GONE);
            mEndSecondView.setVisibility(View.GONE);
            mIgnoreSecond = true;
            return this;
        }

        /**
         * 不选择分钟和秒数
         */
        public Builder setIgnoreMinuteAndSecond() {
            mStartMinuteView.setVisibility(View.GONE);
            mEndMinuteView.setVisibility(View.GONE);
            mStartSecondView.setVisibility(View.GONE);
            mEndSecondView.setVisibility(View.GONE);
            mIgnoreMinute = true;
            mIgnoreSecond = true;
            return this;
        }

        /**
         * 设置开始时间
         */
        public Builder setStartTime(String startTime) {
            if (startTime == null) {
                return this;
            }
            // 102030
            if (startTime.matches("\\d{6}")) {
                setStartHour(startTime.substring(0, 2));
                setStartMinute(startTime.substring(2, 4));
                setStartSecond(startTime.substring(4, 6));
            // 10:20:30
            } else if (startTime.matches("\\d{2}:\\d{2}:\\d{2}")) {
                setStartHour(startTime.substring(0, 2));
                setStartMinute(startTime.substring(3, 5));
                setStartSecond(startTime.substring(6, 8));
            }
            return this;
        }

        /**
         * 设置结束时间
         */
        public Builder setEndTime(String endTime) {
            if (endTime == null) {
                return this;
            }
            // 102030
            if (endTime.matches("\\d{6}")) {
                setEndHour(endTime.substring(0, 2));
                setEndMinute(endTime.substring(2, 4));
                setEndSecond(endTime.substring(4, 6));
            // 10:20:30
            } else if (endTime.matches("\\d{2}:\\d{2}:\\d{2}")) {
                setEndHour(endTime.substring(0, 2));
                setEndMinute(endTime.substring(3, 5));
                setEndSecond(endTime.substring(6, 8));
            }
            return this;
        }

        public Builder setStartHour(String hour) {
            if (hour == null) {
                return this;
            }
            return setStartHour(Integer.parseInt(hour));
        }

        public Builder setStartHour(int hour) {
            int index = hour;
            if (index < 0 || hour == 24) {
                index = 0;
            } else if (index > mStartHourAdapter.getCount() - 1) {
                index = mStartHourAdapter.getCount() - 1;
            }
            mStartHourView.scrollToPosition(index);
            return this;
        }

        public Builder setStartMinute(String minute) {
            if (minute == null) {
                return this;
            }
            return setStartMinute(Integer.parseInt(minute));
        }

        public Builder setStartMinute(int minute) {
            int index = minute;
            if (index < 0) {
                index = 0;
            } else if (index > mStartMinuteAdapter.getCount() - 1) {
                index = mStartMinuteAdapter.getCount() - 1;
            }
            mStartMinuteView.scrollToPosition(index);
            return this;
        }

        public Builder setStartSecond(String second) {
            if (second == null) {
                return this;
            }
            return setStartSecond(Integer.parseInt(second));
        }

        public Builder setStartSecond(int second) {
            int index = second;
            if (index < 0) {
                index = 0;
            } else if (index > mStartSecondAdapter.getCount() - 1) {
                index = mStartSecondAdapter.getCount() - 1;
            }
            mStartSecondView.scrollToPosition(index);
            return this;
        }

        public Builder setEndHour(String hour) {
            if (hour == null) {
                return this;
            }
            return setEndHour(Integer.parseInt(hour));
        }

        public Builder setEndHour(int hour) {
            int index = hour;
            if (index < 0 || hour == 24) {
                index = 0;
            } else if (index > mEndHourAdapter.getCount() - 1) {
                index = mEndHourAdapter.getCount() - 1;
            }
            mEndHourView.scrollToPosition(index);
            return this;
        }

        public Builder setEndMinute(String minute) {
            if (minute == null) {
                return this;
            }
            return setEndMinute(Integer.parseInt(minute));
        }

        public Builder setEndMinute(int minute) {
            int index = minute;
            if (index < 0) {
                index = 0;
            } else if (index > mEndMinuteAdapter.getCount() - 1) {
                index = mEndMinuteAdapter.getCount() - 1;
            }
            mEndMinuteView.scrollToPosition(index);
            return this;
        }

        public Builder setEndSecond(String second) {
            if (second == null) {
                return this;
            }
            return setEndSecond(Integer.parseInt(second));
        }

        public Builder setEndSecond(int second) {
            int index = second;
            if (index < 0) {
                index = 0;
            } else if (index > mEndSecondAdapter.getCount() - 1) {
                index = mEndSecondAdapter.getCount() - 1;
            }
            mEndSecondView.scrollToPosition(index);
            return this;
        }

        @SingleClick
        @Override
        public void onClick(@NonNull View view) {
            int viewId = view.getId();
            if (viewId == R.id.tv_ui_confirm) {
                int startHour = mStartHourManager.getPickedPosition();
                int startMinute = mIgnoreMinute ? 0 : mStartMinuteManager.getPickedPosition();
                int startSecond = mIgnoreSecond ? 0 : mStartSecondManager.getPickedPosition();

                int endHour = mEndHourManager.getPickedPosition();
                int endMinute = mIgnoreMinute ? 0 : mEndMinuteManager.getPickedPosition();
                int endSecond = mIgnoreSecond ? 0 : mEndSecondManager.getPickedPosition();

                // 将时间转换为秒进行比较
                int startSeconds = startHour * 3600 + startMinute * 60 + startSecond;
                int endSeconds = endHour * 3600 + endMinute * 60 + endSecond;

                // 验证时间范围
                if (startSeconds > endSeconds) {
                    if (mListener != null) {
                        mListener.onTimeRangeInvalid(getDialog());
                    }
                    return;
                }

                performClickDismiss();
                if (mListener == null) {
                    return;
                }
                // 调用原始的回调方法（向后兼容）
                mListener.onSelected(getDialog(),
                        startHour, startMinute, startSecond,
                        endHour, endMinute, endSecond);
                
                // 调用新的回调方法，返回格式化后的时间字符串
                mListener.onSelectedTime(getDialog(),
                        formatTime(startHour, startMinute, startSecond),
                        formatTime(endHour, endMinute, endSecond));
            } else if (viewId == R.id.tv_ui_cancel) {
                performClickDismiss();
                if (mListener == null) {
                    return;
                }
                mListener.onCancel(getDialog());
            }
        }
    }

    /**
     * 格式化时间为字符串
     *
     * @param hour   小时 (0-23)
     * @param minute 分钟 (0-59)
     * @param second 秒钟 (0-59)
     * @return 格式化的时间字符串 (格式：HH:mm:ss)
     */
    private static String formatTime(int hour, int minute, int second) {
        String hourStr = (hour < 10 ? "0" : "") + hour;
        String minuteStr = (minute < 10 ? "0" : "") + minute;
        String secondStr = (second < 10 ? "0" : "") + second;
        return hourStr + ":" + minuteStr + ":" + secondStr;
    }

    private static final class PickerAdapter extends AppAdapter<String> {

        private PickerAdapter(@NonNull Context context) {
            super(context);
        }

        @NonNull
        @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder();
        }

        private final class ViewHolder extends AppViewHolder {

            private final TextView mPickerView;

            ViewHolder() {
                super(R.layout.picker_item);
                mPickerView = findViewById(R.id.tv_picker_name);
            }

            @Override
            public void onBindView(int position) {
                mPickerView.setText(getItem(position));
            }
        }
    }

    public interface OnListener {

        /**
         * 选择完时间范围后回调
         *
         * @param startHour         开始时钟
         * @param startMinute       开始分钟
         * @param startSecond       开始秒钟
         * @param endHour           结束时钟
         * @param endMinute         结束分钟
         * @param endSecond         结束秒钟
         */
        void onSelected(@NonNull BaseDialog dialog,
                       int startHour, int startMinute, int startSecond,
                       int endHour, int endMinute, int endSecond);

        /**
         * 选择完时间范围后回调（返回格式化字符串）
         *
         * @param dialog            对话框对象
         * @param startTime         开始时间字符串（格式：HH:mm:ss）
         * @param endTime           结束时间字符串（格式：HH:mm:ss）
         */
        default void onSelectedTime(@NonNull BaseDialog dialog, String startTime, String endTime) {
            // default implementation ignored, call the other onSelected method
        }

        /**
         * 点击取消时回调
         */
        default void onCancel(@NonNull BaseDialog dialog) {
            // default implementation ignored
        }

        /**
         * 时间范围无效时回调（开始时间大于结束时间）
         */
        default void onTimeRangeInvalid(@NonNull BaseDialog dialog) {
            // default implementation ignored
        }
    }
}
