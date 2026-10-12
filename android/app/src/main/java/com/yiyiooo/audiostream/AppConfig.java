package com.yiyiooo.audiostream;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.yiyiooo.audiostream.route.RouteConfig;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用偏好，落盘到 SharedPreferences：一组拉取路由、上次填过的地址、外观模式。
 *
 * 「一组路由」是本文件的主体：每一路各有自己的地址、设备与音量（见 {@link RouteConfig}），
 * 所以这里存的是一份 JSON 数组而不是几个扁平的键。数组里每一项都自带地址，
 * 意味着不同路可以来自不同的电脑——这正是「同时拉多个源」要的东西。
 *
 * 本机显示名**不在这里**——那是身份的一部分，归 {@link MachineIdentity} 管，
 * 改名不换 MachineId 的约束也由它保证。
 *
 * 地址容错集中在 {@link #normalizeHost(String)}：允许用户直接粘一个
 * {@code http://ip:port/xxx} 进来，这里把它剥成光秃秃的 host，好给 TCP 连接用。
 */
public final class AppConfig {

    private static final String TAG = "AudioStream";

    private static final String STORE = "audiostream-prefs";

    /** 当前格式：整组路由的 JSON 数组。 */
    private static final String KEY_ROUTES = "routes";
    /** 上次填过的地址。只用于「新加一路」时预填输入框，不参与任何一路的连接。 */
    private static final String KEY_LAST_HOST = "last_host";
    private static final String KEY_THEME = "theme_mode";

    // ---- 单路版本留下的键。只在迁移时读一次，读完就删 ----
    private static final String KEY_LEGACY_HOST = "host";
    private static final String KEY_LEGACY_DEVICE_ID = "device_id";
    private static final String KEY_LEGACY_DEVICE_NAME = "device_name";
    private static final String KEY_LEGACY_VOLUME = "volume";

    /** 输出音量的默认值：满增益。用户没调过就按原始音量放，不自作主张压低。 */
    public static final float DEFAULT_VOLUME = RouteConfig.DEFAULT_VOLUME;

    /** 外观：跟随系统 / 强制浅色 / 强制深色。数值会落盘，定下来就不要再改。 */
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private AppConfig() {
    }

    // ========== 路由列表 ==========

    /**
     * 读出全部路由。
     *
     * 首次在装有旧版本的机器上运行时，这里会顺手把旧的那一路搬过来——用户升级完不该发现自己
     * 原来配好的那一路不见了，还得凭记忆重填一遍 IP 与设备。
     */
    public static List<RouteConfig> loadRoutes(Context context) {
        SharedPreferences store = store(context);
        String raw = store.getString(KEY_ROUTES, null);
        if (raw != null) {
            return parseRoutes(raw);
        }
        List<RouteConfig> migrated = migrateLegacyRoute(store);
        // 立刻落成新格式并清掉旧键。清理是必须的：否则用户删掉迁移出来的这一路之后，
        // 下次启动又会从旧键里把它复活出来。
        saveRoutes(context, migrated);
        store.edit()
                .remove(KEY_LEGACY_HOST)
                .remove(KEY_LEGACY_DEVICE_ID)
                .remove(KEY_LEGACY_DEVICE_NAME)
                .remove(KEY_LEGACY_VOLUME)
                .apply();
        return migrated;
    }

    /** 把整组路由写下去。传 null 或空表就是「一路都不剩」，这是用户全删光的正常状态。 */
    public static void saveRoutes(Context context, List<RouteConfig> routes) {
        JSONArray array = new JSONArray();
        if (routes != null) {
            for (RouteConfig route : routes) {
                if (route == null) continue;
                try {
                    array.put(route.toJson());
                } catch (JSONException e) {
                    // 单写成失败不该拖垮整次保存：其余几路照存，用户至少不会一次丢掉全部配置
                    Log.w(TAG, "一路配置写不进 JSON，跳过：" + route, e);
                }
            }
        }
        store(context).edit().putString(KEY_ROUTES, array.toString()).apply();
    }

    private static List<RouteConfig> parseRoutes(String raw) {
        List<RouteConfig> routes = new ArrayList<RouteConfig>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                RouteConfig route = RouteConfig.fromJson(array.optJSONObject(i));
                // 缺地址或缺设备号的条目会在 fromJson 里变成 null，这里直接跳过
                if (route != null) routes.add(route);
            }
        } catch (JSONException e) {
            // 偏好被写坏了：当成没有路由，而不是让应用一打开就崩。
            // 用户重配一次的代价，远小于一个打不开的应用。
            Log.w(TAG, "路由列表解析失败，按空处理", e);
        }
        return routes;
    }

    /**
     * 把单路版本留下的那几个键搬成一路。
     *
     * 地址即使没有配套的设备号也留下来（写进「上次填过的地址」）：用户多半只是没选过设备，
     * 让他新加一路时不用重新敲一遍 IP。
     */
    private static List<RouteConfig> migrateLegacyRoute(SharedPreferences store) {
        List<RouteConfig> routes = new ArrayList<RouteConfig>();
        String host = normalizeHost(store.getString(KEY_LEGACY_HOST, ""));
        String deviceId = store.getString(KEY_LEGACY_DEVICE_ID, "");
        if (host.isEmpty()) return routes;

        store.edit().putString(KEY_LAST_HOST, host).apply();
        if (deviceId.isEmpty()) return routes;

        routes.add(RouteConfig.create(host, deviceId,
                store.getString(KEY_LEGACY_DEVICE_NAME, ""),
                store.getFloat(KEY_LEGACY_VOLUME, RouteConfig.DEFAULT_VOLUME)));
        return routes;
    }

    // ========== 上次填过的地址 ==========

    /** 上次填过的地址，用来给「新加一路」的输入框预填。没填过就是空串。 */
    public static String loadLastHost(Context context) {
        return store(context).getString(KEY_LAST_HOST, "");
    }

    /** 记下这次填的地址。它不影响已经在跑的任何一路。 */
    public static void saveLastHost(Context context, String host) {
        store(context).edit().putString(KEY_LAST_HOST, normalizeHost(host)).apply();
    }

    // ========== 音量 ==========

    /**
     * 把音量夹进 0..1。转发给 {@link RouteConfig}——音量的合法范围由「一路的配置」定义，
     * 这里再写一遍同样的规则只会多一处将来忘记同步的地方。
     */
    public static float clampVolume(float volume) {
        return RouteConfig.clampVolume(volume);
    }

    // ========== 外观 ==========

    /**
     * 外观模式。存的是「用户想要的」，与系统此刻的深浅色是两回事——
     * 选「跟随系统」时这里仍是 THEME_SYSTEM，由 Activity 交给 AppCompatDelegate 去解析。
     */
    public static int loadThemeMode(Context context) {
        int mode = store(context).getInt(KEY_THEME, THEME_SYSTEM);
        return (mode == THEME_LIGHT || mode == THEME_DARK) ? mode : THEME_SYSTEM;
    }

    /** 认不出的值一律当「跟随系统」：多半来自写坏的偏好，退回默认好过停在一个不存在的模式上。 */
    public static void saveThemeMode(Context context, int mode) {
        int safe = (mode == THEME_LIGHT || mode == THEME_DARK) ? mode : THEME_SYSTEM;
        store(context).edit().putInt(KEY_THEME, safe).apply();
    }

    // ========== 工具 ==========

    private static SharedPreferences store(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    /**
     * 把用户随手粘进来的任何东西剥成能直接拿去连的 host。
     *
     * {@code http://ip:port/xxx}、{@code https://ip/}、{@code ip:12570} 都行；
     * 去掉端口是因为音频 TCP 有自己独立的 12670+ 端口区间，跟 HTTP 端口是两回事。
     * 局域网实测只用 IPv4，IPv6 的方括号地址这里不处理。
     */
    public static String normalizeHost(String raw) {
        String host = raw == null ? "" : raw.trim();
        if (host.startsWith("http://")) host = host.substring("http://".length());
        else if (host.startsWith("https://")) host = host.substring("https://".length());
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon > 0) host = host.substring(0, colon);
        return host.trim();
    }
}
