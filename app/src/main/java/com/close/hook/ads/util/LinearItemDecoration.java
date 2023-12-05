package com.close.hook.ads.util;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class LinearItemDecoration extends RecyclerView.ItemDecoration {

    private final int space;

    public LinearItemDecoration(int space){
        this.space = space;
    }

    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, RecyclerView parent, @NonNull RecyclerView.State state){
        int position = parent.getChildAdapterPosition(view);
        if (position == 0){
            outRect.top = this.space;
        }
        outRect.left = this.space;
        outRect.right = this.space;
        outRect.bottom = this.space;
    }

}
