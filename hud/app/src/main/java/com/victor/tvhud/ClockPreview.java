package com.victor.tvhud;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Lets you preview the screensaver without waiting for the TV to idle. */
public class ClockPreview extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new ClockView(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopService(new Intent(this, HudService.class));
    }

    @Override
    protected void onPause() {
        startForegroundService(new Intent(this, HudService.class));
        super.onPause();
    }
}
