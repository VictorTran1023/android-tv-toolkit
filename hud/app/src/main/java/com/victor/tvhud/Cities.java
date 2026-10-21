package com.victor.tvhud;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.TimeZone;

/**
 * Tiny built-in city table for weather lookup.
 *
 * No geocoding request, no permissions, works offline: the IP service only has to
 * return a city name, and we map it to coordinates locally. If nothing matches we
 * fall back to the system time zone (Asia/Shanghai -> Shanghai), which is far more
 * reliable on this TV than an international IP database when a proxy is active.
 */
final class Cities {

    static final class City {
        final String name;
        final double lat;
        final double lon;

        City(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    static final City[] ALL = {
            new City("北京", 39.9042, 116.4074),
            new City("上海", 31.2304, 121.4737),
            new City("广州", 23.1291, 113.2644),
            new City("深圳", 22.5431, 114.0579),
            new City("杭州", 30.2741, 120.1551),
            new City("南京", 32.0603, 118.7969),
            new City("苏州", 31.2989, 120.5853),
            new City("成都", 30.5728, 104.0668),
            new City("重庆", 29.5630, 106.5516),
            new City("武汉", 30.5928, 114.3055),
            new City("西安", 34.3416, 108.9398),
            new City("天津", 39.0842, 117.2009),
            new City("长沙", 28.2282, 112.9388),
            new City("郑州", 34.7466, 113.6254),
            new City("济南", 36.6512, 117.1201),
            new City("青岛", 36.0671, 120.3826),
            new City("厦门", 24.4798, 118.0894),
            new City("昆明", 25.0389, 102.7183),
            new City("大连", 38.9140, 121.6147),
            new City("沈阳", 41.8057, 123.4315),
            new City("哈尔滨", 45.8038, 126.5350),
            new City("合肥", 31.8206, 117.2272),
            new City("福州", 26.0745, 119.2965),
            new City("南昌", 28.6820, 115.8579),
            new City("南宁", 22.8170, 108.3665),
            new City("贵阳", 26.6470, 106.6302),
            new City("兰州", 36.0611, 103.8343),
            new City("海口", 20.0440, 110.1999),
            new City("乌鲁木齐", 43.8256, 87.6168),
            new City("拉萨", 29.6520, 91.1721),
            new City("石家庄", 38.0428, 114.5149),
            new City("太原", 37.8706, 112.5489),
            new City("长春", 43.8171, 125.3235),
            new City("呼和浩特", 40.8424, 111.7490),
            new City("西宁", 36.6171, 101.7782),
            new City("银川", 38.4872, 106.2309),
            new City("香港", 22.3193, 114.1694),
            new City("澳门", 22.1987, 113.5439),
            new City("台北", 25.0330, 121.5654),
    };

    private static final String PREFS = "weather_city";
    private static final String K_NAME = "name";
    private static final String K_LAT = "lat";
    private static final String K_LON = "lon";

    private Cities() {
    }

    static City byName(String name) {
        if (name == null) return null;
        for (City c : ALL) {
            if (c.name.equals(name)) return c;
        }
        return null;
    }

    /** Normalize "上海市" / "香港特别行政区" / "内蒙古自治区" to a comparable form. */
    private static String norm(String s) {
        if (s == null) return "";
        return s.replace("特别行政区", "")
                .replace("维吾尔自治区", "")
                .replace("壮族自治区", "")
                .replace("回族自治区", "")
                .replace("自治区", "")
                .replace("省", "")
                .replace("市", "")
                .replace(" ", "")
                .replace("\u00a0", "")
                .trim();
    }

    /**
     * Find a city mentioned in a free-form location string such as
     * "当前 IP：1.2.3.4 来自于：中国 上海 上海 联通".
     */
    static City match(String text) {
        String n = norm(text);
        if (n.isEmpty()) return null;
        for (City c : ALL) {
            if (n.contains(c.name)) return c;
        }
        // Province-only answer: map to the provincial capital.
        String[][] provinces = {
                {"广东", "广州"}, {"江苏", "南京"}, {"浙江", "杭州"}, {"山东", "济南"},
                {"四川", "成都"}, {"湖北", "武汉"}, {"湖南", "长沙"}, {"河南", "郑州"},
                {"福建", "福州"}, {"安徽", "合肥"}, {"江西", "南昌"}, {"河北", "石家庄"},
                {"山西", "太原"}, {"陕西", "西安"}, {"辽宁", "沈阳"}, {"吉林", "长春"},
                {"黑龙江", "哈尔滨"}, {"云南", "昆明"}, {"贵州", "贵阳"}, {"广西", "南宁"},
                {"海南", "海口"}, {"甘肃", "兰州"}, {"青海", "西宁"}, {"宁夏", "银川"},
                {"新疆", "乌鲁木齐"}, {"西藏", "拉萨"}, {"内蒙古", "呼和浩特"},
        };
        for (String[] p : provinces) {
            if (n.contains(p[0])) return byName(p[1]);
        }
        return null;
    }

    static City forTimeZone() {
        String id = TimeZone.getDefault().getID();
        if (id == null) return byName("上海");
        if (id.contains("Hong_Kong")) return byName("香港");
        if (id.contains("Macau")) return byName("澳门");
        if (id.contains("Taipei")) return byName("台北");
        if (id.contains("Chongqing")) return byName("重庆");
        if (id.contains("Urumqi")) return byName("乌鲁木齐");
        if (id.contains("Harbin")) return byName("哈尔滨");
        if (id.startsWith("Asia/Shanghai")) return byName("上海");
        return byName("上海");
    }

    static boolean isMainlandTimeZone() {
        String id = TimeZone.getDefault().getID();
        if (id == null) return false;
        return id.startsWith("Asia/Shanghai") || id.startsWith("Asia/Chongqing")
                || id.startsWith("Asia/Urumqi") || id.startsWith("Asia/Harbin");
    }

    static boolean isProxyCity(City c) {
        return c != null && ("香港".equals(c.name) || "澳门".equals(c.name) || "台北".equals(c.name));
    }

    static City manual(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String name = p.getString(K_NAME, null);
        if (name == null) return null;
        City known = byName(name);
        if (known != null) return known;
        float lat = p.getFloat(K_LAT, Float.NaN);
        float lon = p.getFloat(K_LON, Float.NaN);
        if (Float.isNaN(lat) || Float.isNaN(lon)) return null;
        return new City(name, lat, lon);
    }

    static void saveManual(Context ctx, City c) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_NAME, c.name)
                .putFloat(K_LAT, (float) c.lat)
                .putFloat(K_LON, (float) c.lon)
                .apply();
    }

    static void clearManual(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    static String label(City c) {
        return c == null ? "自动" : String.format(Locale.CHINA, "%s", c.name);
    }
}
