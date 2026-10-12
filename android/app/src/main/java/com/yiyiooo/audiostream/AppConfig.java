package com.yiyiooo.audiostream;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用偏好，落盘到 SharedPreferences，存「用户选过、下次还想接着用」的几样东西：
 * 上次连的 PC 地址、上次选的来源设备（设备号 + 设备名）、播放输出音量、外观模式。
 *
 * 本机显示名**不在这里**——那是 hitmachine 身份的一部分，归 {@link MachineIdentity} 管，
 * 改名不换 MachineId 的约束也由它保证。这里只记「连到哪、点了哪个设备」。
 *
 * 地址容错集中在 {@link #normalizeHost(String)}：允许用户直接粘一个
 * {@code http://ip:port/xxx} 进来，这里把它剥成光秃秃的 host，好给 TCP 连接用。
 */
public final class AppConfig {

    private static final String STORE = "audiostream-prefs";
    private static final String KEY_HOST = "host";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_THEME = "theme_mode";

    /** 输出音量的默认值：满增益。用户没调过就按原始音量放，不自作主张压低。 */
    public static final float DEFAULT_VOLUME = 1f;

    /** 外观：跟随系统 / 强制浅色 / 强制深色。数值会落盘，定下来就不要再改。 */
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private final String host;
    private final String deviceId;
    private final String deviceName;

    private AppConfig(String host, String deviceId, String deviceName) {
        this.host = host == null ? "" : host;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.deviceName = deviceName == null ? "" : deviceName;
    }

    /** 读当前存着的偏好。没存过就是空串，绝不抛错。 */
    public static AppConfig load(Context context) {
        SharedPreferences store = store(context);
        return new AppConfig(
                store.getString(KEY_HOST, ""),
                store.getString(KEY_DEVICE_ID, ""),
                store.getString(KEY_DEVICE_NAME, ""));
    }

    /** 记下 PC 地址，来源设备选择保留不动。 */
    public static AppConfig saveHost(Context context, String host) {
        AppConfig current = load(context);
        store(context).edit().putString(KEY_HOST, normalizeHost(host)).apply();
        return new AppConfig(normalizeHost(host), current.deviceId, current.deviceName);
    }

    /** 记下上次选的来源设备，PC 地址保留不动。 */
    public static AppConfig saveDevice(Context context, String deviceId, String deviceName) {
        AppConfig current = load(context);
        store(context).edit()
                .putString(KEY_DEVICE_ID, deviceId == null ? "" : deviceId)
                .putString(KEY_DEVICE_NAME, deviceName == null ? "" : deviceName)
                .apply();
        return new AppConfig(current.host,
                deviceId == null ? "" : deviceId,
                deviceName == null ? "" : deviceName);
    }

    /** 上一次填的 PC 地址（纯 host，已剥掉 scheme/端口/路径）。 */
    public String host() {
        return host;
    }

    /** 上一次选的来源设备号。空串表示用户还没选过。 */
    public String deviceId() {
        return deviceId;
    }

    /** 上一次选的来源设备名。 */
    public String deviceName() {
        return deviceName;
    }

    /**
     * 上次调的输出音量（0..1）。
     *
     * 单独一组存取、不并进上面那个值对象：音量与「连到哪、点了哪个设备」没有关系，
     * 为了多存一个数就把那三样东西的构造与三个工厂方法全带上一个音量参数，不划算。
     */
    public static float loadVolume(Context context) {
        return clampVolume(store(context).getFloat(KEY_VOLUME, DEFAULT_VOLUME));
    }

    /** 记下输出音量。越界的值在这里就夹住，免得坏值一路带进声卡。 */
    public static void saveVolume(Context context, float volume) {
        store(context).edit().putFloat(KEY_VOLUME, clampVolume(volume)).apply();
    }

    /**
     * 把音量夹进 0..1。
     * NaN 也当默认值处理：它多半来自早期版本写坏的偏好，夹成 0 会让用户以为程序坏了，夹成 1 更安全。
     */
    public static float clampVolume(float volume) {
        if (Float.isNaN(volume)) return DEFAULT_VOLUME;
        if (volume < 0f) return 0f;
        if (volume > 1f) return 1f;
        return volume;
    }

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
