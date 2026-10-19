package com.victor.tvhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Minimal clock face with a status line, used as the screensaver. */
public class ClockView extends View {

    private static final int TEXT = 0xFFF5F6F8;
    private static final int MUTED = 0xFF9A9AA2;
    private static final int GREEN = 0xFF3DDC84;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat date = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
    private final Stats stats = new Stats();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            stats.sample(getContext());
            invalidate();
            handler.postDelayed(this, 1000);
        }
    };

    public ClockView(Context c) {
        super(c);
        line.setColor(0x1FFFFFFF);
        line.setStrokeWidth(dp(1));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void draw(Canvas c, String s, float x, float y, float sizeDp, Typeface tf, int color) {
        text.setTypeface(tf);
        text.setTextSize(dp(sizeDp));
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        bg.setShader(new LinearGradient(0, 0, 0, h, 0xFF07080C, 0xFF040406, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);

        float left = w * 0.075f;
        draw(c, clock.format(new Date()), left, h * 0.52f, 150, light, TEXT);
        draw(c, date.format(new Date()), left + dp(6), h * 0.63f, 26, regular, MUTED);

        String lineText = (stats.vpn ? "VPN 已连接" : "VPN 未连接")
                + "   ·   CPU " + stats.cpuPercent + "%"
                + "   ·   热状态 " + stats.thermalLabel()
                + "   ·   RAM " + String.format(Locale.US, "%.1fG", stats.memUsedMb / 1024f);
        draw(c, lineText, left + dp(6), h * 0.71f, 16, regular, 0xFF8A8C94);

        draw(c, "按 OK 唤醒", left + dp(6), h * 0.90f, 13, regular, 0xFF5A5C64);
        c.drawLine(left, h * 0.86f, w - left, h * 0.86f, line);
    }
}


