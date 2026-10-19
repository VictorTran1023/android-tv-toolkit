package com.victor.tvhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The strip that is drawn on top of every app. */
public class HudView extends View {

    private static final int TEXT = 0xFFF5F6F8;
    private static final int MUTED = 0xFF9A9AA2;
    private static final int GREEN = 0xFF3DDC84;
    private static final int AMBER = 0xFFFFB020;
    private static final int RED = 0xFFFF453A;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shield = new Path();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat date = new SimpleDateFormat("M月d日 EEE", Locale.CHINA);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Stats stats = new Stats();

    public HudView(Context c) {
        super(c);
        line.setColor(0x28FFFFFF);
        line.setStrokeWidth(dp(1));
    }

    public void setStats(Stats s) {
        stats = s;
        invalidate();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void prepare(Paint p, float sizeDp, boolean bold) {
        p.setTypeface(bold ? medium : regular);
        p.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(sizeDp));
        p.setAntiAlias(true);
    }

    private float width(String s, float sizeDp, boolean bold) {
        prepare(measure, sizeDp, bold);
        return measure.measureText(s);
    }

    /** draws text vertically centred on centerY, returns its width. */
    private float draw(Canvas c, String s, float x, float centerY, float sizeDp, boolean bold, int color) {
        prepare(text, sizeDp, bold);
        text.setColor(color);
        float baseline = centerY - (text.ascent() + text.descent()) / 2f;
        c.drawText(s, x, baseline, text);
        return text.measureText(s);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        bg.setShader(new LinearGradient(0, 0, 0, h, 0xD905060A, 0x33000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        line.setColor(0x26FFFFFF);
        c.drawLine(0, h - dp(0.6f), w, h - dp(0.6f), line);

        float cy = h / 2f;
        float x = dp(26);

        // clock + date
        x += draw(c, clock.format(new Date()), x, cy, 21, true, TEXT) + dp(12);
        x += draw(c, date.format(new Date()), x, cy, 11, false, MUTED) + dp(16);
        c.drawLine(x, dp(18), x, h - dp(18), line);
        x += dp(16);

        // VPN
        float s = dp(15);
        buildShield(x, cy - s / 2, s);
        bar.setColor(stats.vpn ? GREEN : 0xFF6B6D75);
        c.drawPath(shield, bar);
        x += s + dp(8);
        x += draw(c, "VPN", x, cy, 10, false, MUTED) + dp(8);
        draw(c, stats.vpn ? "已连接" : "未连接", x, cy, 12, true,
                stats.vpn ? GREEN : 0xFF8A6A6A);

        // right cluster, right to left
        float right = w - dp(26);
        right = pair(c, "↑", rate(stats.txBps), right, cy, TEXT);
        right = pair(c, "↓", rate(stats.rxBps), right, cy, TEXT);
        right = pair(c, "RAM", (stats.memUsedMb / 1024f) + "G", right, cy, TEXT);
        int tc = stats.thermalStatus >= 5 ? RED : stats.thermalStatus >= 2 ? AMBER : GREEN;
        right = pair(c, "TEMP", stats.thermalLabel(), right, cy, tc);

        // CPU cluster
        String cpuValue = stats.cpuPercent + "%";
        float lw = width("CPU", 10, false);
        float vw = width(cpuValue, 13, true);
        float cx = right - (lw + dp(8) + vw + dp(8) + dp(34));
        draw(c, "CPU", cx, cy, 10, false, MUTED);
        draw(c, cpuValue, cx + lw + dp(8), cy, 13, true, TEXT);

        float bx = cx + lw + dp(8) + vw + dp(8);
        float bw = dp(34);
        bar.setColor(0x33FFFFFF);
        c.drawRoundRect(bx, cy - dp(1.5f), bx + bw, cy + dp(1.5f), dp(1.5f), dp(1.5f), bar);
        bar.setColor(stats.cpuPercent > 70 ? AMBER : GREEN);
        c.drawRoundRect(bx, cy - dp(1.5f), bx + bw * stats.cpuPercent / 100f, cy + dp(1.5f),
                dp(1.5f), dp(1.5f), bar);
    }

    /** right-aligned "label value" with a separator; returns the next right edge. */
    private float pair(Canvas c, String label, String value, float right, float cy, int valueColor) {
        float vw = width(value, 13, true);
        float lw = width(label, 10, false);
        float left = right - vw - dp(6) - lw;
        draw(c, label, left, cy, 10, false, MUTED);
        draw(c, value, left + lw + dp(6), cy, 13, true, valueColor);

        float next = left - dp(20);
        line.setColor(0x22FFFFFF);
        line.setStrokeWidth(dp(0.8f));
        c.drawLine(next + dp(10), dp(18), next + dp(10), getHeight() - dp(18), line);
        return next;
    }

    private void buildShield(float x, float y, float s) {
        shield.reset();
        shield.moveTo(x, y + s * 0.18f);
        shield.lineTo(x + s * 0.5f, y);
        shield.lineTo(x + s, y + s * 0.18f);
        shield.lineTo(x + s * 0.86f, y + s * 0.68f);
        shield.lineTo(x + s * 0.5f, y + s);
        shield.lineTo(x + s * 0.14f, y + s * 0.68f);
        shield.close();
    }

    private String rate(float bps) {
        if (bps >= 1048576) return String.format(Locale.US, "%.1fM", bps / 1048576f);
        if (bps >= 1024) return String.format(Locale.US, "%.0fK", bps / 1024f);
        return String.format(Locale.US, "%.0fB", bps);
    }
}
