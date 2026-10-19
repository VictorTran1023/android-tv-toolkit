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
 * Everything stays left of ~620 px so the TV's own top bar is not covered.
 */
public class HudView extends View {

    private static final int TEXT = 0xFFF2F3F5;
    private static final int MUTED = 0xFF9A9CA4;
    private static final int GREEN = 0xFF3DDC84;
    private static final int AMBER = 0xFFFFB020;
    private static final int RED = 0xFFFF453A;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shield = new Path();
    private final Path thermo = new Path();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Stats stats = new Stats();

    public HudView(Context c) {
        super(c);
        line.setColor(0x26FFFFFF);
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

    private void prepare(Paint p, float sizeDp, Typeface tf) {
        p.setTypeface(tf);
        p.setTextSize(dp(sizeDp));
        p.setAntiAlias(true);
    }

    private float draw(Canvas c, String s, float x, float centerY, float sizeDp, Typeface tf, int color) {
        prepare(text, sizeDp, tf);
        text.setColor(color);
        float baseline = centerY - (text.ascent() + text.descent()) / 2f;
        c.drawText(s, x, baseline, text);
        return text.measureText(s);
    }

    private float capped(Canvas c, String s, float x, float centerY, int color) {
        prepare(text, 9, regular);
        text.setColor(color);
        text.setLetterSpacing(0.12f);
        float baseline = centerY - (text.ascent() + text.descent()) / 2f;
        c.drawText(s, x, baseline, text);
        float w = text.measureText(s);
        text.setLetterSpacing(0);
        return w;
    }

    private void divider(Canvas c, float x, float h) {
        line.setColor(0x22FFFFFF);
        line.setStrokeWidth(dp(1));
        c.drawLine(x, h * 0.22f, x, h * 0.78f, line);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        bg.setShader(new LinearGradient(0, 0, 0, h, 0xF0090B10, 0x00090B10, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);

        float cy = h / 2f;
        float x = dp(24);

        x += draw(c, clock.format(new Date()), x, cy, 23, light, TEXT) + dp(16);
        divider(c, x, h);
        x += dp(14);

        // VPN
        float s = dp(14);
        buildShield(x, cy - s / 2, s);
        shape.setColor(stats.vpn ? GREEN : 0xFF6B6D75);
        c.drawPath(shield, shape);
        x += s + dp(8);
        x += capped(c, "VPN", x, cy, MUTED) + dp(6);
        x += draw(c, stats.vpn ? "已连接" : "未连接", x, cy, 12, medium,
                stats.vpn ? GREEN : 0xFF8A6A6A) + dp(16);
        divider(c, x, h);
        x += dp(14);

        // CPU
        x += capped(c, "CPU", x, cy, MUTED) + dp(7);
        x += draw(c, stats.cpuPercent + "%", x, cy, 13, medium, TEXT) + dp(8);
        float bw = dp(34), bh = dp(3.2f);
        shape.setColor(0x2EFFFFFF);
        c.drawRoundRect(x, cy - bh / 2, x + bw, cy + bh / 2, bh / 2, bh / 2, shape);
        shape.setColor(stats.cpuPercent > 70 ? AMBER : GREEN);
        c.drawRoundRect(x, cy - bh / 2, x + bw * stats.cpuPercent / 100f, cy + bh / 2,
                bh / 2, bh / 2, shape);
        x += bw + dp(16);
        divider(c, x, h);
        x += dp(14);

        // thermal
        int tc = stats.thermalStatus >= 5 ? RED : stats.thermalStatus >= 2 ? AMBER : GREEN;
        x += capped(c, "TEMP", x, cy, MUTED) + dp(7);
        buildThermo(x, cy, dp(9), tc);
        c.drawPath(thermo, shape);
        x += dp(11);
        x += draw(c, stats.thermalLabel(), x, cy, 12, medium, tc) + dp(16);
        divider(c, x, h);
        x += dp(14);

        // throughput
        x += draw(c, "↓", x, cy, 11, regular, MUTED) + dp(4);
        x += draw(c, rate(stats.rxBps), x, cy, 12, medium, TEXT) + dp(12);
        x += draw(c, "↑", x, cy, 11, regular, MUTED) + dp(4);
        draw(c, rate(stats.txBps), x, cy, 12, medium, 0xFFC9CBD2);
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

    private void buildThermo(float x, float cy, float h, int color) {
        float w = h * 0.5f;
        float top = cy - h / 2f;
        float bulb = h * 0.32f;
        thermo.reset();
        thermo.addRoundRect(x, top, x + w, top + h - bulb, w / 2, w / 2, Path.Direction.CW);
        thermo.addCircle(x + w / 2, top + h - bulb + bulb * 0.55f, bulb * 0.62f, Path.Direction.CW);
        shape.setColor(color);
    }

    private String rate(float bps) {
        if (bps >= 1048576) return String.format(Locale.US, "%.1fM", bps / 1048576f);
        if (bps >= 1024) return String.format(Locale.US, "%.0fK", bps / 1024f);
        return String.format(Locale.US, "%.0fB", bps);
    }
}

