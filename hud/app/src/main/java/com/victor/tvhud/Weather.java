package com.victor.tvhud;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Weather for the clock screensaver.
 *
 * Location: manual city > ipip.net (domestic, direct-routed under rule mode) > system time zone.
 * Weather: Open-Meteo (no key) > wttr.in (fallback). Everything is cached for 30 minutes;
 * a failed refresh keeps the last good reading instead of showing invented numbers.
 */
final class Weather {

    static final String TAG = "TVHUD-Weather";
    private static final String PREFS = "weather";
    private static final long CACHE_MS = 30 * 60 * 1000L;
    private static final long RETRY_MS = 5 * 60 * 1000L;

    interface Listener {
        void onWeather(Data data);
    }

    static final class Data {
        String city = "";
        float tempC;
        float tmax;
        float tmin;
        int code;
        int icon;
        String desc = "";
        String source = "";
        long updated;
        boolean fromCache;
    }

    static final class Place {
        final String name;
        final double lat;
        final double lon;
        final String source;

        Place(String name, double lat, double lon, String source) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.source = source;
        }
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean running;
    private static long lastAttempt;

    private Weather() {
    }

    static Data cached(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long updated = p.getLong("updated", 0);
        if (updated == 0) return null;
        Data d = new Data();
        d.city = p.getString("city", "");
        d.tempC = p.getFloat("temp", 0);
        d.tmax = p.getFloat("tmax", d.tempC);
        d.tmin = p.getFloat("tmin", d.tempC);
        d.code = p.getInt("code", -1);
        d.icon = p.getInt("icon", group(d.code));
        d.desc = p.getString("desc", describe(d.code));
        d.source = p.getString("source", "");
        d.updated = updated;
        d.fromCache = true;
        if (d.desc == null || d.desc.isEmpty() || "—".equals(d.desc)) {
            d.desc = describe(d.code);
        }
        return d;
    }

    static boolean isFresh(Data d) {
        return d != null && System.currentTimeMillis() - d.updated < CACHE_MS;
    }

    /** Refresh if stale or forced; callback always runs on the main thread. */
    static void refresh(Context ctx, boolean force, Listener listener) {
        final Context app = ctx.getApplicationContext();
        final Data cache = cached(app);

        if (!force && isFresh(cache)) {
            post(listener, cache);
            return;
        }
        if (!force && lastAttempt != 0 && System.currentTimeMillis() - lastAttempt < RETRY_MS) {
            post(listener, cache);
            return;
        }
        synchronized (LOCK) {
            if (running) {
                post(listener, cache);
                return;
            }
            running = true;
            lastAttempt = System.currentTimeMillis();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Data fresh = null;
                try {
                    Place place = resolve(app);
                    Log.i(TAG, "place=" + place.name + " (" + place.source + ") "
                            + String.format(Locale.US, "%.3f,%.3f", place.lat, place.lon));
                    fresh = fetch(place);
                    if (fresh != null) {
                        save(app, fresh);
                        Log.i(TAG, "weather=" + fresh.desc + " " + fresh.tempC + "°C via " + fresh.source);
                    } else {
                        Log.w(TAG, "all weather sources failed");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "refresh failed", t);
                } finally {
                    synchronized (LOCK) {
                        running = false;
                    }
                }
                final Data out = fresh != null ? fresh : cache;
                post(listener, out);
            }
        }, "weather-refresh").start();
    }

    private static void post(final Listener l, final Data d) {
        if (l == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                l.onWeather(d);
            }
        });
    }

    // ---------------------------------------------------------------- location

    private static Place resolve(Context ctx) {
        Cities.City manual = Cities.manual(ctx);
        if (manual != null) {
            return new Place(manual.name, manual.lat, manual.lon, "手动");
        }

        try {
            String text = get("https://myip.ipip.net/", 6000);
            Cities.City city = Cities.match(text);
            if (city != null) {
                boolean proxyLike = Cities.isProxyCity(city);
                if (proxyLike && isVpn(ctx) && Cities.isMainlandTimeZone()) {
                    Log.i(TAG, "ignore proxy location " + city.name);
                } else {
                    return new Place(city.name, city.lat, city.lon, "IP");
                }
            } else {
                Log.i(TAG, "ipip text has no known city: " + trim(text));
            }
        } catch (Throwable t) {
            Log.i(TAG, "ipip failed: " + t);
        }

        Cities.City tz = Cities.forTimeZone();
        return new Place(tz.name, tz.lat, tz.lon, "时区");
    }

    private static boolean isVpn(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- weather

    private static Data fetch(Place p) {
        Data d = fromOpenMeteo(p);
        if (d == null) d = fromWttr(p);
        return d;
    }

    private static Data fromOpenMeteo(Place p) {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=1", p.lat, p.lon);
        try {
            JSONObject root = new JSONObject(get(url, 10000));
            JSONObject cur = root.optJSONObject("current");
            if (cur == null) return null;
            double t = cur.optDouble("temperature_2m", Double.NaN);
            if (Double.isNaN(t)) return null;
            int code = cur.optInt("weather_code", -1);
            double tmax = t, tmin = t;
            JSONObject daily = root.optJSONObject("daily");
            if (daily != null) {
                JSONArray max = daily.optJSONArray("temperature_2m_max");
                JSONArray min = daily.optJSONArray("temperature_2m_min");
                if (max != null && max.length() > 0) tmax = max.optDouble(0, t);
                if (min != null && min.length() > 0) tmin = min.optDouble(0, t);
            }
            Data d = new Data();
            d.city = p.name;
            d.tempC = (float) t;
            d.tmax = (float) tmax;
            d.tmin = (float) tmin;
            d.code = code;
            d.icon = group(code);
            d.desc = describe(code);
            d.source = "Open-Meteo";
            d.updated = System.currentTimeMillis();
            return d;
        } catch (Throwable t) {
            Log.i(TAG, "open-meteo failed: " + t);
            return null;
        }
    }

    private static Data fromWttr(Place p) {
        try {
            String url = "https://wttr.in/" + URLEncoder.encode(p.name, "UTF-8")
                    + "?format=j1&lang=zh";
            JSONObject root = new JSONObject(get(url, 12000));
            JSONArray cc = root.optJSONArray("current_condition");
            if (cc == null || cc.length() == 0) return null;
            JSONObject cur = cc.getJSONObject(0);
            float t = Float.parseFloat(cur.optString("temp_C", "NaN"));
            if (Float.isNaN(t)) return null;
            int ww = cur.optInt("weatherCode", -1);
            float tmax = t, tmin = t;
            JSONArray days = root.optJSONArray("weather");
            if (days != null && days.length() > 0) {
                JSONObject day = days.getJSONObject(0);
                tmax = parseFloat(day.optString("maxtempC"), t);
                tmin = parseFloat(day.optString("mintempC"), t);
            }
            Data d = new Data();
            d.city = p.name;
            d.tempC = t;
            d.tmax = tmax;
            d.tmin = tmin;
            d.code = ww;
            d.icon = wwGroup(ww);
            d.desc = wwDescribe(ww);
            d.source = "wttr.in";
            d.updated = System.currentTimeMillis();
            return d;
        } catch (Throwable t) {
            Log.i(TAG, "wttr failed: " + t);
            return null;
        }
    }

    private static float parseFloat(String s, float fallback) {
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void save(Context ctx, Data d) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("city", d.city)
                .putFloat("temp", d.tempC)
                .putFloat("tmax", d.tmax)
                .putFloat("tmin", d.tmin)
                .putInt("code", d.code)
                .putInt("icon", d.icon)
                .putString("desc", d.desc)
                .putString("source", d.source)
                .putLong("updated", d.updated)
                .apply();
    }

    // ------------------------------------------------------------- description

    static String describe(int code) {
        if (code == 0) return "晴";
        if (code == 1) return "少云";
        if (code == 2) return "多云";
        if (code == 3) return "阴";
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code == 61) return "小雨";
        if (code == 63) return "中雨";
        if (code == 65) return "大雨";
        if (code == 66 || code == 67) return "冻雨";
        if (code == 71) return "小雪";
        if (code == 73) return "中雪";
        if (code == 75) return "大雪";
        if (code == 77) return "米雪";
        if (code == 80) return "阵雨";
        if (code == 81) return "强阵雨";
        if (code == 82) return "暴雨";
        if (code == 85 || code == 86) return "阵雪";
        if (code == 95) return "雷阵雨";
        if (code == 96 || code == 99) return "雷暴冰雹";
        return "—";
    }

    /** 0 sun, 1 cloud, 2 rain, 3 snow, 4 thunder, 5 fog. */
    static int group(int code) {
        if (code == 0 || code == 1) return 0;
        if (code == 2 || code == 3) return 1;
        if (code == 45 || code == 48) return 5;
        if (code >= 51 && code <= 67) return 2;
        if (code >= 71 && code <= 77) return 3;
        if (code >= 80 && code <= 82) return 2;
        if (code == 85 || code == 86) return 3;
        if (code >= 95) return 4;
        return 1;
    }

    static String wwDescribe(int ww) {
        switch (ww) {
            case 113: return "晴";
            case 116: return "多云";
            case 119: return "多云";
            case 122: return "阴";
            case 143:
            case 248:
            case 260: return "雾";
            case 176:
            case 293:
            case 296: return "小雨";
            case 299:
            case 302: return "中雨";
            case 305:
            case 308: return "大雨";
            case 179:
            case 323:
            case 326: return "小雪";
            case 227:
            case 230: return "暴雪";
            case 329:
            case 332:
            case 335:
            case 338: return "大雪";
            case 200:
            case 386:
            case 389: return "雷阵雨";
            case 353:
            case 356:
            case 359: return "阵雨";
            case 368:
            case 371: return "阵雪";
            default: return "—";
        }
    }

    static int wwGroup(int ww) {
        if (ww == 113) return 0;
        if (ww == 116 || ww == 119 || ww == 122 || ww == 143) return 1;
        if (ww == 248 || ww == 260) return 5;
        if (ww == 200 || ww == 386 || ww == 389) return 4;
        if (ww == 179 || ww == 227 || ww == 230 || ww == 323 || ww == 326
                || ww == 329 || ww == 332 || ww == 335 || ww == 338
                || ww == 368 || ww == 371) return 3;
        return 2;
    }

    // -------------------------------------------------------------------- http

    private static String get(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs + 5000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 11; TV) TVHUD/1.6");
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 262144) break;
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    static String updatedLabel(Data d) {
        if (d == null || d.updated == 0) return "—";
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(d.updated));
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }
}
