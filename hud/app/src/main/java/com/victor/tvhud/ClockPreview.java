package com.victor.tvhud;

import android.app.Activity;
import android.os.Bundle;

/** Lets you preview the screensaver without waiting for the TV to idle. */
public class ClockPreview extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new ClockView(this));
    }
}
