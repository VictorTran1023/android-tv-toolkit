package com.victor.tvhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Clock screensaver: large thin time, date, local weather and four status readouts. */
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
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bolt = new Path();
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat date = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
    private final Stats stats = new Stats();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Weather.Data weather;
    private boolean weatherLoading;
    private long lastWeatherCheck;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            stats.sample(getContext());
            if (System.currentTimeMillis() - lastWeatherCheck > 5 * 60 * 1000L) {
                lastWeatherCheck = System.currentTimeMillis();
                loadWeather(false);
            }
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

    private float draw(Canvas c, String s, float x, float baseline, float sizeDp, Typeface tf, int color) {
        text.setTypeface(tf);
        text.setTextSize(dp(sizeDp));
        text.setColor(color);
        c.drawText(s, x, baseline, text);
        return text.measureText(s);
    }

    private float width(String s, float sizeDp, Typeface tf) {
        text.setTypeface(tf);
        text.setTextSize(dp(sizeDp));
        return text.measureText(s);
    }

    private void loadWeather(boolean force) {
        if (weather == null) weatherLoading = true;
        Weather.refresh(getContext(), force, new Weather.Listener() {
            @Override
            public void onWeather(Weather.Data d) {
                if (d != null) weather = d;
                weatherLoading = false;
                invalidate();
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        weather = Weather.cached(getContext());
        weatherLoading = weather == null;
        lastWeatherCheck = System.currentTimeMillis();
        loadWeather(false);
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
        float x = w * 0.075f;

        c.save();
        draw(c, clock.format(now), x, h * 0.55f, 165, light, TEXT);
        draw(c, date.format(now), x + dp(4), h * 0.645f, 36, regular, MUTED);
        c.restore();

        drawWeather(c, x + dp(4), h * 0.705f, w);

        line.setColor(0x1CFFFFFF);
        c.drawLine(x, h * 0.755f, x + w * 0.34f, h * 0.755f, line);

        float labelY = h * 0.805f;
        float valueY = h * 0.855f;
        float step = dp(190);
        float cx = x + dp(4);

        chip(c, cx, labelY, valueY, "VPN", stats.vpn ? "已连接" : "未连接",
                stats.vpn ? GREEN : 0xFF8A8F98);
        chip(c, cx + step, labelY, valueY, "CPU", stats.cpuPercent + "%",
                stats.cpuPercent > 70 ? AMBER : GREEN);
        int tc = stats.thermalStatus >= 5 ? RED : stats.thermalStatus >= 2 ? AMBER : GREEN;
        chip(c, cx + step * 2, labelY, valueY, "热状态", stats.thermalLabel(), tc);
        chip(c, cx + step * 3, labelY, valueY, "内存",
                String.format(Locale.US, "%.1fG", stats.memUsedMb / 1024f), 0xFFB9BBC4);

        draw(c, "按 OK 唤醒", x + dp(4), h * 0.93f, 18, regular, 0xFF6E7078);
        String name = deviceName();
        draw(c, name, w - w * 0.075f - width(name, 12, regular), h * 0.93f, 12, regular, 0xFF44464E);
    }

    /** Use the user-set Android device name when available, otherwise the model. */
    private String deviceName() {
        try {
            String n = Settings.Global.getString(getContext().getContentResolver(),
                    Settings.Global.DEVICE_NAME);
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable ignored) {
        }
        String model = Build.MODEL;
        if (model != null && !model.trim().isEmpty()) return model.trim();
        return "Android TV";
    }

    private void drawWeather(Canvas c, float x, float baseline, int w) {
        float weatherY = baseline;
        if (weather == null) {
            draw(c, weatherLoading ? "天气获取中…" : "天气 —", x, weatherY, 20, regular, 0xFF6E7078);
            return;
        }

        float size = dp(28);
        drawWeatherIcon(c, x, weatherY - dp(4), size, weather.icon);
        float tx = x + size + dp(12);

        String temp = String.format(Locale.US, "%.0f°", weather.tempC);
        String desc = weather.desc == null || weather.desc.isEmpty()
                ? Weather.describe(weather.code) : weather.desc;
        tx += draw(c, temp, tx, weatherY, 32, medium, TEXT) + dp(10);
        tx += draw(c, desc, tx, weatherY, 24, regular, MUTED) + dp(16);

        String range = String.format(Locale.US, "↑%.0f° ↓%.0f°", weather.tmax, weather.tmin);
        if (tx + width(range, 16, regular) + dp(14) < w * 0.62f) {
            tx += draw(c, range, tx, weatherY, 16, regular, 0xFF8A8F98) + dp(14);
            if (tx + width(weather.city, 15, regular) < w * 0.66f) {
                draw(c, "· " + weather.city, tx, weatherY, 15, regular, 0xFF6E7078);
            }
        }
    }

    private void drawWeatherIcon(Canvas c, float x, float cy, float size, int group) {
        float cx = x + size / 2f;
        float r = size * 0.22f;
        icon.setStyle(Paint.Style.FILL);
        icon.setStrokeCap(Paint.Cap.ROUND);

        if (group == 0) {
            icon.setColor(0xFFFFC857);
            c.drawCircle(cx, cy, r, icon);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(dp(2));
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4.0;
                float x1 = (float) (cx + Math.cos(a) * r * 1.55);
                float y1 = (float) (cy + Math.sin(a) * r * 1.55);
                float x2 = (float) (cx + Math.cos(a) * r * 2.1);
                float y2 = (float) (cy + Math.sin(a) * r * 2.1);
                c.drawLine(x1, y1, x2, y2, icon);
            }
            return;
        }

        if (group == 5) {
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(dp(2.4f));
            icon.setColor(0xFFB9C2CC);
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * dp(7);
                c.drawLine(cx - size * 0.32f, y, cx + size * 0.32f, y, icon);
            }
            return;
        }

        drawCloud(c, cx, cy - (group == 1 ? 0 : dp(3)), size * 0.92f, 0xFFC9D2DC);

        if (group == 2) {
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(dp(2.2f));
            icon.setColor(0xFF6FA8DC);
            for (int i = -1; i <= 1; i++) {
                float lx = cx + i * size * 0.17f;
                c.drawLine(lx, cy + size * 0.13f, lx - size * 0.05f, cy + size * 0.30f, icon);
            }
        } else if (group == 3) {
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(dp(1.8f));
            icon.setColor(0xFFCFE8FF);
            for (int i = -1; i <= 1; i++) {
                float sx = cx + i * size * 0.19f;
                float sy = cy + size * 0.22f;
                c.drawLine(sx - dp(4), sy, sx + dp(4), sy, icon);
                c.drawLine(sx, sy - dp(4), sx, sy + dp(4), icon);
            }
        } else if (group == 4) {
            icon.setStyle(Paint.Style.FILL);
            icon.setColor(0xFFFFD166);
            bolt.reset();
            bolt.moveTo(cx + size * 0.02f, cy + size * 0.08f);
            bolt.lineTo(cx - size * 0.08f, cy + size * 0.28f);
            bolt.lineTo(cx + size * 0.01f, cy + size * 0.28f);
            bolt.lineTo(cx - size * 0.05f, cy + size * 0.46f);
            bolt.lineTo(cx + size * 0.17f, cy + size * 0.22f);
            bolt.lineTo(cx + size * 0.06f, cy + size * 0.22f);
            bolt.close();
            c.drawPath(bolt, icon);
        }
    }

    private void drawCloud(Canvas c, float cx, float cy, float size, int color) {
        icon.setStyle(Paint.Style.FILL);
        icon.setColor(color);
        float r = size * 0.16f;
        c.drawCircle(cx - size * 0.18f, cy + size * 0.02f, r, icon);
        c.drawCircle(cx, cy - size * 0.07f, r * 1.28f, icon);
        c.drawCircle(cx + size * 0.19f, cy + size * 0.02f, r * 0.96f, icon);
        c.drawRoundRect(cx - size * 0.18f, cy + size * 0.02f,
                cx + size * 0.19f, cy + size * 0.02f + r * 1.5f, dp(3), dp(3), icon);
    }

    private void chip(Canvas c, float x, float labelY, float valueY, String label, String value, int color) {
        draw(c, label, x, labelY, 14, regular, 0xFF8A8F98);
        draw(c, value, x, valueY, 22, medium, color);
    }
}
