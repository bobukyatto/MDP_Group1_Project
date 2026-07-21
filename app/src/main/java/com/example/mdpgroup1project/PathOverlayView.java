package com.example.mdpgroup1project;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

// Draws the planned route (from a "PATH,x1:y1;x2:y2;..." status line) as a polyline over the map
// grid. Points are stored in arena centimeters; onDraw converts them to pixels using the same
// scale/origin convention as the car overlay (bottom-left origin, y flipped for screen coordinates).
public class PathOverlayView extends View {
    private final List<float[]> pointsCm = new ArrayList<>();
    private float scalePxPerCm = 0f;
    private float gridPixelSize = 0f;
    private final Paint linePaint;

    public PathOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#FF9800"));
        linePaint.setStrokeWidth(6f);
        linePaint.setStyle(Paint.Style.STROKE);
    }

    public void setGridMetrics(float gridPixelSize, float arenaSizeCm) {
        this.gridPixelSize = gridPixelSize;
        this.scalePxPerCm = arenaSizeCm > 0 ? gridPixelSize / arenaSizeCm : 0f;
        invalidate();
    }

    public void setPathPoints(List<float[]> newPointsCm) {
        pointsCm.clear();
        pointsCm.addAll(newPointsCm);
        invalidate();
    }

    public void clearPath() {
        pointsCm.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pointsCm.size() < 2 || scalePxPerCm <= 0) return;
        Float prevX = null, prevY = null;
        for (float[] p : pointsCm) {
            float px = p[0] * scalePxPerCm;
            float py = gridPixelSize - p[1] * scalePxPerCm;
            if (prevX != null) canvas.drawLine(prevX, prevY, px, py, linePaint);
            prevX = px; prevY = py;
        }
    }
}
