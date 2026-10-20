package ua.spokiy.trevoga;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Простий контейнер, що переносить дочірні елементи (плашки загроз) на новий рядок. */
public class FlowLayout extends ViewGroup {
    private final int gap;

    public FlowLayout(Context c, int gapPx) {
        super(c);
        gap = gapPx;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0, n = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            if (x > 0 && x + w > maxW) { x = 0; y += rowH + gap; rowH = 0; }
            x += w + gap;
            rowH = Math.max(rowH, h);
            n++;
        }
        int h = n == 0 ? 0 : y + rowH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(MeasureSpec.getSize(wSpec), h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        int left = getPaddingLeft(), top = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            if (x > 0 && x + w > maxW) { x = 0; y += rowH + gap; rowH = 0; }
            v.layout(left + x, top + y, left + x + w, top + y + h);
            x += w + gap;
            rowH = Math.max(rowH, h);
        }
    }
}
