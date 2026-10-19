package com.victor.tvhud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** Foreground service that owns the overlay strip. */
public class HudService extends Service {

    public static final String ACTION_STOP = "com.victor.tvhud.STOP";
    private static final String CHANNEL = "hud";

    private WindowManager wm;
    private HudView view;
    private PowerManager power;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Stats stats = new Stats();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            stats.sample(HudService.this);
            if (view != null) {
                view.setStats(stats);
                view.setVisibility(shouldHide() ? View.INVISIBLE : View.VISIBLE);
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        getSharedPreferences("hud", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        power = (PowerManager) getSystemService(POWER_SERVICE);
        startForeground(1, notification());
        if (Settings.canDrawOverlays(this)) {
            addOverlay();
            handler.post(tick);
        } else {
            stopSelf();
        }
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "TV HUD",
                    NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("TV HUD")
                .setContentText("状态条运行中")
                .setOngoing(true)
                .build();
    }

    private void addOverlay() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                (int) getResources().getDimension(R.dimen.hud_height),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        view = new HudView(this);
        wm.addView(view, p);
    }

    /** hidden while the panel is off, so it does not keep a lit strip in standby */
    private boolean shouldHide() {
        return power != null && !power.isInteractive();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("hud", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
            view = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
