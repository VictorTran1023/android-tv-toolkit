package com.victor.tvhud;

/** In-process flags: no prefs timing, the overlay and the UI share one process. */
public final class HudState {
    public static volatile boolean dreaming;
    public static volatile boolean uiOpen;

    private HudState() {
    }
}
