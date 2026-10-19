package com.victor.tvhud;

import android.content.Intent;
import android.service.dreams.DreamService;

/** Replaces the ad-filled screensaver with a clock. */
public class ClockDream extends DreamService {

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        stopService(new Intent(this, HudService.class));
    }

    @Override
    public void onDreamingStopped() {
        startForegroundService(new Intent(this, HudService.class));
        super.onDreamingStopped();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(new ClockView(this));
    }
}
