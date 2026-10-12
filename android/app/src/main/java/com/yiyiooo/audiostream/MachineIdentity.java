package com.yiyiooo.audiostream;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

/**
 * 本机在音频流转网络里的身份，与 PC 端的 MachineIdentity.cs 一一对应。
 *
 * 之所以要持久化、而不是每次启动随机生成：对端要靠它认人。拉取方在握手时报上自己的身份，
 * 被拉的一侧据此判断「这是不是我自己」——如果是自己，这路流必然绕回本机形成环路，
 * 必须在握手阶段就拒绝掉。身份每次启动都变的话，这条判断就失效了。
 *
 * 名字（PcName）只给人看，判定只认 id，重名无所谓。
 */
public final class MachineIdentity {

    private static final String STORE = "audiostream-identity";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";

    private final String id;
    private final String name;

    private MachineIdentity(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** 读本机身份，第一次运行时生成并落盘。 */
    public static MachineIdentity load(Context context) {
        SharedPreferences store = context.getApplicationContext()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE);
        String id = store.getString(KEY_ID, null);
        String name = store.getString(KEY_NAME, null);
        if (id == null || id.trim().isEmpty()) {
            // 与 PC 端同规格：32 位十六进制（Guid.ToString("N")）
            id = UUID.randomUUID().toString().replace("-", "");
        }
        if (name == null || name.trim().isEmpty()) {
            name = defaultName();
        }
        SharedPreferences.Editor editor = store.edit();
        editor.putString(KEY_ID, id);
        editor.putString(KEY_NAME, name);
        editor.apply();
        return new MachineIdentity(id.trim(), name.trim());
    }

    /** 把名字改掉。身份号不变——对端的授权表与路由表记的都是号。 */
    public MachineIdentity rename(Context context, String newName) {
        String value = newName == null ? "" : newName.trim();
        if (value.isEmpty()) value = defaultName();
        context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit().putString(KEY_NAME, value).apply();
        return new MachineIdentity(id, value);
    }

    /** 默认名字就是手机型号，好让 PC 端的列表里一眼看得出是哪台手机。 */
    private static String defaultName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        return model.isEmpty() ? "Android 手机" : "Android " + model;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }
}
