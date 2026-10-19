package com.victor.tvhud;

import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences p = context.getSharedPreferences("hud", Context.MODE_PRIVATE);
        if (p.getBoolean("enabled", false) && Settings.canDrawOverlays(context)) {
            context.startForegroundService(new Intent(context, HudService.class));
        }
    }
}
