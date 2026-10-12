package com.yiyiooo.audiostream;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 播放端的偏好，落盘到 SharedPreferences，只存「用户填过、下次还想接着用」的两样东西：
 * 上次连的 PC 地址、上次选的来源设备（设备号 + 设备名）。
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
