package com.victor.tvhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Clock screensaver: large thin time, date and four status readouts on a near-black glow. */
public class ClockView extends View {

    private static final int TEXT = 0xFFF4F5F7;
    private static final int MUTED = 0xFF9A9CA4;
    private static final int DIM = 0xFF5E6068;
    private static final int GREEN = 0xFF3DDC84;
    private static final int AMBER = 0xFFFFB020;
    private static final int RED = 0xFFFF453A;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
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

    private void draw(Canvas c, String s, float x, float baseline, float sizeDp, Typeface tf, int color) {
        text.setTypeface(tf);
        text.setTextSize(dp(sizeDp));
        text.setColor(color);
        c.drawText(s, x, baseline, text);
    }

    private float width(String s, float sizeDp, Typeface tf) {
        text.setTypeface(tf);
        text.setTextSize(dp(sizeDp));
        return text.measureText(s);
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

        base.setShader(new LinearGradient(0, 0, 0, h, 0xFF07080C, 0xFF040406, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, base);

        glow.setShader(new RadialGradient(w * 0.82f, h * 0.12f, w * 0.55f,
                new int[]{0x332B4F6B, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(w * 0.82f, h * 0.12f, w * 0.55f, glow);
        glow.setShader(new RadialGradient(w * 0.10f, h * 0.95f, w * 0.45f,
                new int[]{0x223D2A1E, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(w * 0.10f, h * 0.95f, w * 0.45f, glow);

        Date now = new Date();
        int minute = Integer.parseInt(new SimpleDateFormat("mm", Locale.US).format(now));
        int hour = Integer.parseInt(new SimpleDateFormat("HH", Locale.US).format(now));
        float dx = ((minute % 5) - 2) * dp(4);
        float dy = ((hour % 2) == 0 ? -1 : 1) * dp(3);

        float x = w * 0.075f;
        c.save();
        c.translate(dx, dy);
        draw(c, clock.format(now), x, h * 0.55f, 165, light, TEXT);
        draw(c, date.format(now), x + dp(4), h * 0.655f, 27, regular, MUTED);
        c.restore();

        line.setColor(0x1CFFFFFF);
        c.drawLine(x, h * 0.705f, x + w * 0.34f, h * 0.705f, line);

        float labelY = h * 0.765f;
        float valueY = h * 0.815f;
        float step = dp(190);
        float cx = x + dp(4);

        chip(c, cx, labelY, valueY, "VPN", stats.vpn ? "已连接" : "未连接",
                stats.vpn ? GREEN : 0xFF8A6A6A);
        chip(c, cx + step, labelY, valueY, "CPU", stats.cpuPercent + "%",
                stats.cpuPercent > 70 ? AMBER : GREEN);
        int tc = stats.thermalStatus >= 5 ? RED : stats.thermalStatus >= 2 ? AMBER : GREEN;
        chip(c, cx + step * 2, labelY, valueY, "热状态", stats.thermalLabel(), tc);
        chip(c, cx + step * 3, labelY, valueY, "内存",
                String.format(Locale.US, "%.1fG", stats.memUsedMb / 1024f), 0xFFB9BBC4);

        draw(c, "按 OK 唤醒", x + dp(4), h * 0.93f, 13, regular, DIM);
        String name = "TCL T7K";
        draw(c, name, w - w * 0.075f - width(name, 12, regular), h * 0.93f, 12, regular, 0xFF44464E);
    }

    private void chip(Canvas c, float x, float labelY, float valueY, String label, String value, int color) {
        draw(c, label, x, labelY, 10, regular, 0xFF6E7078);
        draw(c, value, x, valueY, 17, medium, color);
    }
}
