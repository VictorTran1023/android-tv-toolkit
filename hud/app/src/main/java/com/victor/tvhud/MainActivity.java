package com.victor.tvhud;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Single settings screen: start/stop the strip, grant the overlay permission, show the adb hint. */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.START);
        root.setBackgroundColor(0xFF0B0C10);
        int pad = dp(48);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("TV HUD");
        title.setTextColor(0xFFF5F6F8);
        title.setTextSize(34);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFF9A9AA2);
        status.setTextSize(15);
        status.setPadding(0, dp(8), 0, dp(20));
        root.addView(status);

        root.addView(button("开启状态条", v -> startHud()));
        root.addView(button("关闭状态条", v -> stopHud()));
        root.addView(button("显示 10 秒", v -> peek()));
        root.addView(button("悬浮窗权限设置", v -> overlaySettings()));
        root.addView(button("退出", v -> finish()));

        TextView hint = new TextView(this);
        hint.setText("屏保通过 adb 设置：\n"
                + "adb shell settings put secure screensaver_components com.victor.tvhud/.ClockDream\n"
                + "adb shell settings put secure screensaver_enabled 1");
        hint.setTextColor(0xFF6E7078);
        hint.setTextSize(12);
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint);

        setContentView(root);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(280), dp(56));
        lp.bottomMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean allowed() {
        return Settings.canDrawOverlays(this);
    }

    private void startHud() {
        if (!allowed()) {
            overlaySettings();
            return;
        }
        getSharedPreferences("hud", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        startForegroundService(new Intent(this, HudService.class));
        refresh();
    }

    private void stopHud() {
        getSharedPreferences("hud", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        stopService(new Intent(this, HudService.class));
        refresh();
    }

    private void peek() {
        if (!allowed()) { overlaySettings(); return; }
        startForegroundService(new Intent(this, HudService.class).setAction(HudService.ACTION_PEEK));
        refresh();
    }

    private void overlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void refresh() {
        boolean on = getSharedPreferences("hud", MODE_PRIVATE).getBoolean("enabled", false);
        status.setText("悬浮窗权限：" + (allowed() ? "已授权" : "未授权")
                + "    状态条：" + (on ? "启用" : "关闭"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        HudState.uiOpen = true;
        refresh();
    }

    @Override
    protected void onPause() {
        HudState.uiOpen = false;
        super.onPause();
    }
}



