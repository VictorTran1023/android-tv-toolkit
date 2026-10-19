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

/**
 * Compact left-aligned strip: time, VPN, CPU, thermal status and throughput.
 * Everything stays left of ~600 px so the TV's own top bar (tabs and icons) is not covered.
 */
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
    private final Path shield = new Path();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
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
        p.setTextSize(dp(sizeDp));
        p.setAntiAlias(true);
    }

    private float draw(Canvas c, String s, float x, float centerY, float sizeDp, boolean bold, int color) {
        prepare(text, sizeDp, bold);
        text.setColor(color);
        float baseline = centerY - (text.ascent() + text.descent()) / 2f;
        c.drawText(s, x, baseline, text);
        return text.measureText(s);
    }

    private void divider(Canvas c, float x, float h) {
        line.setColor(0x26FFFFFF);
        line.setStrokeWidth(dp(1));
        c.drawLine(x, dp(16), x, h - dp(16), line);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        bg.setShader(new LinearGradient(0, 0, 0, h, 0xF205060A, 0x66000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        line.setColor(0x26FFFFFF);
        c.drawLine(0, h - dp(0.6f), w, h - dp(0.6f), line);

        float cy = h / 2f;
        float x = dp(26);

        x += draw(c, clock.format(new Date()), x, cy, 21, true, TEXT) + dp(18);
        divider(c, x, h);
        x += dp(16);

        float s = dp(15);
        buildShield(x, cy - s / 2, s);
        bar.setColor(stats.vpn ? GREEN : 0xFF6B6D75);
        c.drawPath(shield, bar);
        x += s + dp(8);
        x += draw(c, "VPN", x, cy, 10, false, MUTED) + dp(6);
        x += draw(c, stats.vpn ? "已连接" : "未连接", x, cy, 12, true,
                stats.vpn ? GREEN : 0xFF8A6A6A) + dp(18);
        divider(c, x, h);
        x += dp(16);

        x += draw(c, "CPU", x, cy, 10, false, MUTED) + dp(6);
        x += draw(c, stats.cpuPercent + "%", x, cy, 13, true, TEXT) + dp(8);
        float bw = dp(34);
        bar.setColor(0x33FFFFFF);
        c.drawRoundRect(x, cy - dp(1.5f), x + bw, cy + dp(1.5f), dp(1.5f), dp(1.5f), bar);
        bar.setColor(stats.cpuPercent > 70 ? AMBER : GREEN);
        c.drawRoundRect(x, cy - dp(1.5f), x + bw * stats.cpuPercent / 100f, cy + dp(1.5f),
                dp(1.5f), dp(1.5f), bar);
        x += bw + dp(18);
        divider(c, x, h);
        x += dp(16);

        int tc = stats.thermalStatus >= 5 ? RED : stats.thermalStatus >= 2 ? AMBER : GREEN;
        x += draw(c, "TEMP", x, cy, 10, false, MUTED) + dp(6);
        x += draw(c, stats.thermalLabel(), x, cy, 13, true, tc) + dp(18);
        divider(c, x, h);
        x += dp(16);

        x += draw(c, "↓", x, cy, 11, false, MUTED) + dp(4);
        x += draw(c, rate(stats.rxBps), x, cy, 12, true, TEXT) + dp(12);
        x += draw(c, "↑", x, cy, 11, false, MUTED) + dp(4);
        draw(c, rate(stats.txBps), x, cy, 12, true, MUTED);
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
