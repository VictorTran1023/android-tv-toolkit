package com.victor.tvhud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Foreground service that owns the overlay strip.
 * The strip is a 10-second peek: it appears when the service starts, when the screen wakes
 * and when the foreground app changes, then fades out on its own.
 */
public class HudService extends Service {

    public static final String ACTION_STOP = "com.victor.tvhud.STOP";
    public static final String ACTION_PEEK = "com.victor.tvhud.PEEK";
    private static final String CHANNEL = "hud";
    private static final long PEEK_MS = 10_000L;

    private WindowManager wm;
    private HudView view;
    private PowerManager power;
    private UsageStatsManager usm;
    private long revealUntil;
    private String lastPackage;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            peek();
        }
    };

    private final Stats stats = new Stats();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            stats.sample(HudService.this);
            if (view != null) {
                view.setStats(stats);
                String pkg = currentForeground();
                if (pkg != null && !pkg.equals(lastPackage)) {
                    lastPackage = pkg;
                    peek();
                }
                applyVisibility();
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
        usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        startForeground(1, notification());
        if (Settings.canDrawOverlays(this)) {
            addOverlay();
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
            peek();
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
                .setContentText("状态条运行中（展示 10 秒）")
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
        view.setVisibility(View.INVISIBLE);
        wm.addView(view, p);
    }

    /** show the strip for another 10 seconds */
    private void peek() {
        revealUntil = System.currentTimeMillis() + PEEK_MS;
        applyVisibility();
    }

    private void applyVisibility() {
        if (view == null) return;
        boolean interactive = power == null || power.isInteractive();
        boolean want = interactive && System.currentTimeMillis() < revealUntil;
        if (want) {
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 1f) {
                view.animate().cancel();
                view.setAlpha(1f);
                view.setVisibility(View.VISIBLE);
            }
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(350).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (view != null) view.setVisibility(View.INVISIBLE);
                }
            }).start();
        }
    }

    private String currentForeground() {
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 8000, now);
        if (events == null) return null;
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        long ts = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && e.getTimeStamp() > ts) {
                last = e.getPackageName();
                ts = e.getTimeStamp();
            }
        }
        return last;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("hud", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopSelf();
        } else if (intent != null && ACTION_PEEK.equals(intent.getAction())) {
            peek();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        try {
            unregisterReceiver(screenOn);
        } catch (Exception ignored) {
        }
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
