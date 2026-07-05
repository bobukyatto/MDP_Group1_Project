package com.example.mdpgroup1project;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {
    private float centerX, centerY, thumbX, thumbY, radius, thumbRadius;
    private Paint circlePaint, thumbPaint, textPaint;
    private JoystickListener listener;
    private String label = "";
    private boolean isVerticalOnly = false;
    private boolean isHorizontalOnly = false;

    public interface JoystickListener {
        void onMoved(float xPercent, float yPercent);
        void onReleased();
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.LTGRAY);
        circlePaint.setStyle(Paint.Style.FILL);
        circlePaint.setAlpha(50);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setColor(Color.DKGRAY);
        thumbPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(40);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(JoystickListener listener) { this.listener = listener; }
    public void setLabel(String label) { this.label = label; invalidate(); }
    public void setMode(boolean vertical, boolean horizontal) {
        this.isVerticalOnly = vertical;
        this.isHorizontalOnly = horizontal;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(w, h) / 3f;
        thumbRadius = radius / 3f;
        resetThumb();
    }

    private void resetThumb() {
        thumbX = centerX;
        thumbY = centerY;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Adapt colors to Dark Mode if necessary (using simple inversion for demo)
        if ((getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) 
            == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            circlePaint.setColor(Color.WHITE);
            circlePaint.setAlpha(30);
            thumbPaint.setColor(Color.LTGRAY);
            textPaint.setColor(Color.WHITE);
        }

        canvas.drawCircle(centerX, centerY, radius, circlePaint);
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint);
        canvas.drawText(label, centerX, centerY + radius + 50, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Disallow ViewPager2 from switching tabs while we are using the joystick
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (isVerticalOnly) {
                thumbY = centerY;
            } else if (isHorizontalOnly) {
                thumbX = centerX;
            } else {
                resetThumb();
            }
            if (listener != null) listener.onReleased();
            invalidate();
            return true;
        }

        float dx = event.getX() - centerX;
        float dy = event.getY() - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        // Cap to radius
        float moveX = dx;
        float moveY = dy;
        if (distance > radius) {
            float ratio = radius / distance;
            moveX = dx * ratio;
            moveY = dy * ratio;
        }

        thumbX = isVerticalOnly ? centerX : centerX + moveX;
        thumbY = isHorizontalOnly ? centerY : centerY + moveY;

        if (listener != null) {
            float xPct = (thumbX - centerX) / radius;
            float yPct = (centerY - thumbY) / radius; // Invert Y so Up is positive
            listener.onMoved(xPct, yPct);
        }

        invalidate();
        return true;
    }
}
