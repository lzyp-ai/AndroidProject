package com.hjq.demo.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.hjq.demo.R;

/**
 * RecyclerView 分割线装饰器
 */
public class FileListDivider extends RecyclerView.ItemDecoration {

    private final Paint mPaint;
    private final int mDividerHeight;
    private final int mLeftMargin;

    public FileListDivider(Context context) {
        mPaint = new Paint();
        mPaint.setColor(ContextCompat.getColor(context, R.color.common_line_color));
        mDividerHeight = context.getResources().getDimensionPixelSize(R.dimen.line_size);
        mLeftMargin = context.getResources().getDimensionPixelSize(R.dimen.dp_45);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                           @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        int itemCount = parent.getAdapter() != null ? parent.getAdapter().getItemCount() : 0;
        
        // 最后一个 item 不显示分割线
        if (position == itemCount - 1) {
            outRect.set(0, 0, 0, 0);
        } else {
            outRect.set(0, 0, 0, mDividerHeight);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent,
                      @NonNull RecyclerView.State state) {
        int childCount = parent.getChildCount();
        
        for (int i = 0; i < childCount - 1; i++) {
            View child = parent.getChildAt(i);
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) child.getLayoutParams();
            
            int left = mLeftMargin;
            int right = parent.getWidth() - parent.getPaddingRight();
            int top = child.getBottom() + params.bottomMargin;
            int bottom = top + mDividerHeight;
            
            c.drawRect(left, top, right, bottom, mPaint);
        }
    }
}
