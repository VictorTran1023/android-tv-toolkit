package com.victor.tvhud;

import android.service.dreams.DreamService;

/** Replaces the ad-filled screensaver with a clock. */
public class ClockDream extends DreamService {

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(new ClockView(this));
    }
}
