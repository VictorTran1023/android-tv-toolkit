package com.victor.tvhud;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Cheap, allocation-light system sampler: /proc/stat, /proc/meminfo, TrafficStats,
 * ConnectivityManager and PowerManager thermal status. No root, no hidden APIs.
 */
public class Stats {

    public int cpuPercent;
    public int memUsedMb;
    public int memTotalMb;
    public float rxBps;
    public float txBps;
    public boolean vpn;
    public int thermalStatus;

    private long lastCpuTotal = -1;
    private long lastCpuIdle = -1;
    private long lastRx = -1;
    private long lastTx = -1;
    private long lastSampleAt;

    public void sample(Context ctx) {
        long now = System.currentTimeMillis();
        float seconds = lastSampleAt == 0 ? 1f : Math.max(0.2f, (now - lastSampleAt) / 1000f);
        lastSampleAt = now;
        sampleCpu();
        sampleMem();
        sampleNet(seconds);
        sampleVpn(ctx);
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        thermalStatus = pm != null ? pm.getCurrentThermalStatus() : 0;
    }

    private void sampleCpu() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = r.readLine();
            if (line == null) return;
            String[] p = line.trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
            if (lastCpuTotal > 0 && total > lastCpuTotal) {
                long dt = total - lastCpuTotal;
                long di = idle - lastCpuIdle;
                cpuPercent = (int) Math.max(0, Math.min(100, 100 * (dt - di) / dt));
            }
            lastCpuTotal = total;
            lastCpuIdle = idle;
        } catch (Exception ignored) {
        }
    }

    private void sampleMem() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            long totalKb = 0, availKb = 0;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) totalKb = firstNumber(line);
                else if (line.startsWith("MemAvailable:")) availKb = firstNumber(line);
                if (totalKb > 0 && availKb > 0) break;
            }
            memTotalMb = (int) (totalKb / 1024);
            memUsedMb = (int) ((totalKb - availKb) / 1024);
        } catch (Exception ignored) {
        }
    }

    private static long firstNumber(String line) {
        String[] p = line.trim().split("\\s+");
        try {
            return Long.parseLong(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private void sampleNet(float seconds) {
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        if (rx < 0) rx = 0;
        if (tx < 0) tx = 0;
        if (lastRx >= 0) {
            rxBps = Math.max(0, rx - lastRx) / seconds;
            txBps = Math.max(0, tx - lastTx) / seconds;
        }
        lastRx = rx;
        lastTx = tx;
    }

    private void sampleVpn(Context ctx) {
        vpn = false;
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpn = true;
                return;
            }
        }
    }

    public String thermalLabel() {
        switch (thermalStatus) {
            case PowerManager.THERMAL_STATUS_NONE:
                return "正常";
            case PowerManager.THERMAL_STATUS_LIGHT:
            case PowerManager.THERMAL_STATUS_MODERATE:
                return "偏热";
            case PowerManager.THERMAL_STATUS_SEVERE:
            case PowerManager.THERMAL_STATUS_CRITICAL:
            case PowerManager.THERMAL_STATUS_EMERGENCY:
            case PowerManager.THERMAL_STATUS_SHUTDOWN:
                return "降频";
            default:
                return "正常";
        }
    }
}
