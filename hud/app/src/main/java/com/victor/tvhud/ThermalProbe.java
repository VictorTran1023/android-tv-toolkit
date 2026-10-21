package com.victor.tvhud;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CPU 温度：App 自己读 /sys/class/thermal 会被 SELinux 拒，但 shell 身份可以。
 * 所以这里用内置的 {@link Adb} 连本机 5555，执行 dumpsys thermalservice 取 mName=cpu。
 *
 * <p>第一次连接时电视会弹「允许 USB 调试？」，用户点一次「一律允许」即可，之后长期有效。
 * 没授权 / ADB 关掉时静默失败，{@link #celsius} 返回 0，界面自动退回三档文字。
 */
final class ThermalProbe {

    private static final String TAG = "TVHUDF";
    private static final Pattern CPU = Pattern.compile(
            "Temperature\\{[^}]*mName=cpu[^}]*\\}");

    private static volatile float celsius = 0f;
    private static volatile long fetchedAt = 0;
    private static volatile long blockedUntil = 0;
    private static volatile boolean everOk = false;
    private static Thread worker;

    private static final long TTL_MS = 20_000;
    private static final long RETRY_MS = 180_000;

    /** 已缓存的温度（°C）；0 表示暂时拿不到。非阻塞。 */
    static float celsius(Context c) {
        long now = System.currentTimeMillis();
        if (now < blockedUntil) return celsius;
        if (now - fetchedAt >= TTL_MS) refresh(c);
        return celsius;
    }

    static boolean everOk() {
        return everOk;
    }

    private static void refresh(Context c) {
        synchronized (ThermalProbe.class) {
            if (worker != null && worker.isAlive()) return;
            final Context app = c.getApplicationContext();
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    int approval = everOk ? 3000 : 30000;
                    try (Adb adb = Adb.connect(app, approval)) {
                        String out = adb.shell("dumpsys thermalservice");
                        float v = parse(out);
                        if (v > 0) {
                            celsius = v;
                            everOk = true;
                            blockedUntil = 0;
                        } else {
                            block();
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "温度探针失败: " + e);
                        block();
                    } finally {
                        fetchedAt = System.currentTimeMillis();
                    }
                }
            }, "thermal-probe");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void block() {
        blockedUntil = System.currentTimeMillis() + RETRY_MS;
    }

    private static float parse(String dump) {
        if (dump == null) return 0f;
        Matcher m = CPU.matcher(dump);
        if (!m.find()) return 0f;
        Matcher v = Pattern.compile("mValue=([0-9]+(?:\\.[0-9]+)?)").matcher(m.group());
        if (!v.find()) return 0f;
        try {
            return Float.parseFloat(v.group(1));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
