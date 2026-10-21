package com.victor.tvhud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Single settings screen: HUD, weather city, overlay permission and the adb hint. */
public class MainActivity extends Activity {

    private TextView status;
    private TextView weatherStatus;
    private Button cityButton;

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
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        weatherStatus = new TextView(this);
        weatherStatus.setTextColor(0xFFB9BBC4);
        weatherStatus.setTextSize(15);
        weatherStatus.setPadding(0, dp(4), 0, dp(18));
        root.addView(weatherStatus);

        root.addView(button("开启状态条", v -> startHud()));
        root.addView(button("关闭状态条", v -> stopHud()));
        root.addView(button("显示 10 秒", v -> peek()));
        root.addView(button("刷新天气", v -> refreshWeather(true)));
        cityButton = button("天气城市", v -> chooseCity());
        root.addView(cityButton);
        root.addView(button("悬浮窗权限设置", v -> overlaySettings()));
        root.addView(button("退出", v -> finish()));

        TextView hint = new TextView(this);
        hint.setText("天气定位：优先国内 IP（myip.ipip.net），失败时按系统时区；"
                + "开着代理可能显示代理所在地，可在「天气城市」手动指定。\n"
                + "天气源：Open-Meteo，失败时 wttr.in；缓存 30 分钟，刷新失败保留上次结果。\n\n"
                + "屏保通过 adb 设置：\n"
                + "adb shell settings put secure screensaver_components com.victor.tvhud/.ClockDream\n"
                + "adb shell settings put secure screensaver_enabled 1");
        hint.setTextColor(0xFF6E7078);
        hint.setTextSize(13);
        hint.setPadding(0, dp(18), 0, 0);
        root.addView(hint);

        setContentView(root);
        updateCityLabel();
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(320), dp(56));
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

    private void refreshWeather(boolean force) {
        weatherStatus.setText("天气：获取中…");
        Weather.refresh(this, force, new Weather.Listener() {
            @Override
            public void onWeather(Weather.Data d) {
                showWeather(d);
            }
        });
    }

    private void showWeather(Weather.Data d) {
        if (d == null) {
            weatherStatus.setText("天气：尚未获取（检查网络后点「刷新天气」）");
            return;
        }
        String updated = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(d.updated));
        weatherStatus.setText(String.format(Locale.US,
                "天气：%s %s %.0f°  %.0f°/%.0f°  ·  %s 更新  ·  %s",
                d.city, d.desc, d.tempC, d.tmax, d.tmin, updated, d.source));
    }

    private void updateCityLabel() {
        Cities.City manual = Cities.manual(this);
        cityButton.setText(manual == null ? "天气城市：自动" : "天气城市：" + manual.name);
    }

    private void chooseCity() {
        final Cities.City manual = Cities.manual(this);
        String[] items = new String[Cities.ALL.length + 1];
        items[0] = "自动（IP / 系统时区）";
        int checked = 0;
        for (int i = 0; i < Cities.ALL.length; i++) {
            items[i + 1] = Cities.ALL[i].name;
            if (manual != null && manual.name.equals(Cities.ALL[i].name)) checked = i + 1;
        }

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("天气城市")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which == 0) {
                        Cities.clearManual(this);
                    } else {
                        Cities.saveManual(this, Cities.ALL[which - 1]);
                    }
                    dialog.dismiss();
                    updateCityLabel();
                    refreshWeather(true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HudState.uiOpen = true;
        refresh();
        updateCityLabel();
        refreshWeather(false);
    }

    @Override
    protected void onPause() {
        HudState.uiOpen = false;
        super.onPause();
    }
}
