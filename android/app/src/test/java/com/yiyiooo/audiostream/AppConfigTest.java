package com.yiyiooo.audiostream;

import android.content.Context;
import android.content.SharedPreferences;

import com.yiyiooo.audiostream.route.RouteConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 偏好的落盘：一组路由的往返、从单路版本升级过来的迁移、外观模式。
 *
 * 走 Robolectric：org.json 与 SharedPreferences 都是平台类，纯 JVM 单元测试里它们只是空壳
 * （returnDefaultValues 让方法返回 null 而不报错），断言会全部失真却依然是绿的。
 */
@RunWith(RobolectricTestRunner.class)
public class AppConfigTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // 每个用例从干净的偏好开始：Robolectric 会在用例之间重用应用实例
        context.getSharedPreferences("audiostream-prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("audiostream-prefs", Context.MODE_PRIVATE);
    }

    // ========== 路由列表 ==========

    @Test
    public void 路由列表落盘再读回来一致() {
        List<RouteConfig> routes = Arrays.asList(
                RouteConfig.create("192.168.1.10", "dev-a", "扬声器", 0.4f),
                RouteConfig.create("192.168.1.20", "default", "系统默认输出设备", 0f));

        AppConfig.saveRoutes(context, routes);
        List<RouteConfig> restored = AppConfig.loadRoutes(context);

        assertEquals(2, restored.size());
        assertEquals(routes.get(0), restored.get(0));
        assertEquals(routes.get(1), restored.get(1));
        // 0 是用户认真调出来的静音，不能与「没存过」混为一谈
        assertEquals(0f, restored.get(1).volume(), 0.0001f);
    }

    @Test
    public void 两路可以来自不同的电脑() {
        // 「同时拉多个源」的核心：地址存在每一路自己身上，而不是全局一个
        AppConfig.saveRoutes(context, Arrays.asList(
                RouteConfig.create("10.0.0.1", "a", "A 机扬声器", 1f),
                RouteConfig.create("10.0.0.2", "b", "B 机麦克风", 1f)));

        List<RouteConfig> restored = AppConfig.loadRoutes(context);
        assertEquals("10.0.0.1", restored.get(0).host());
        assertEquals("10.0.0.2", restored.get(1).host());
    }

    @Test
    public void 一路都不剩是正常状态而不是错误() {
        AppConfig.saveRoutes(context, new ArrayList<RouteConfig>());
        assertTrue(AppConfig.loadRoutes(context).isEmpty());
    }

    @Test
    public void 偏好被写坏时按空列表处理而不是抛错() {
        // 一个打不开的应用，比让用户重配一次严重得多
        prefs().edit().putString("routes", "{这不是 JSON 数组").commit();
        assertTrue(AppConfig.loadRoutes(context).isEmpty());
    }

    @Test
    public void 列表里的坏条目被跳过而好的留下() {
        prefs().edit().putString("routes",
                "[{\"host\":\"1.2.3.4\",\"deviceId\":\"ok\"},{\"deviceId\":\"缺地址\"},{\"host\":\"只有地址\"}]")
                .commit();
        List<RouteConfig> routes = AppConfig.loadRoutes(context);
        assertEquals(1, routes.size());
        assertEquals("1.2.3.4", routes.get(0).host());
    }

    // ========== 从单路版本迁移 ==========

    @Test
    public void 旧版本配好的那一路会被搬过来() {
        // 用户升级完不该发现自己配好的那一路不见了，还得凭记忆重填
        prefs().edit()
                .putString("host", "192.168.5.5")
                .putString("device_id", "legacy-device")
                .putString("device_name", "旧扬声器")
                .putFloat("volume", 0.25f)
                .commit();

        List<RouteConfig> routes = AppConfig.loadRoutes(context);

        assertEquals(1, routes.size());
        assertEquals("192.168.5.5", routes.get(0).host());
        assertEquals("legacy-device", routes.get(0).deviceId());
        assertEquals("旧扬声器", routes.get(0).deviceName());
        assertEquals(0.25f, routes.get(0).volume(), 0.0001f);
    }

    @Test
    public void 迁移只发生一次_删掉之后不会被旧键复活() {
        prefs().edit()
                .putString("host", "192.168.5.5")
                .putString("device_id", "legacy-device")
                .commit();

        assertEquals(1, AppConfig.loadRoutes(context).size());

        // 用户把迁移出来的那一路删了
        AppConfig.saveRoutes(context, new ArrayList<RouteConfig>());
        assertTrue(AppConfig.loadRoutes(context).isEmpty());

        // 旧键若还留着，这一路会自己长回来
        assertNull("旧的设备号键应该已经被清掉", prefs().getString("device_id", null));
        assertNull("旧的音量键应该已经被清掉", prefs().contains("volume") ? "still there" : null);
    }

    @Test
    public void 旧地址即使没配过设备也被留作预填() {
        prefs().edit().putString("host", "http://192.168.7.7:12570/x").commit();

        assertTrue("没有设备号就不该造出一路", AppConfig.loadRoutes(context).isEmpty());
        assertEquals("但地址该留着，用户新加一路时不用重敲", "192.168.7.7",
                AppConfig.loadLastHost(context));
    }

    @Test
    public void 没有旧配置时就是空列表() {
        assertTrue(AppConfig.loadRoutes(context).isEmpty());
    }

    // ========== 上次填过的地址 ==========

    @Test
    public void 上次填的地址会被剥干净再存() {
        AppConfig.saveLastHost(context, "  http://10.1.2.3:9999/some/path  ");
        assertEquals("10.1.2.3", AppConfig.loadLastHost(context));
    }

    // ========== 外观 ==========

    @Test
    public void 外观模式落盘并读回() {
        AppConfig.saveThemeMode(context, AppConfig.THEME_DARK);
        assertEquals(AppConfig.THEME_DARK, AppConfig.loadThemeMode(context));

        AppConfig.saveThemeMode(context, AppConfig.THEME_LIGHT);
        assertEquals(AppConfig.THEME_LIGHT, AppConfig.loadThemeMode(context));
    }

    @Test
    public void 认不出的外观模式退回跟随系统() {
        AppConfig.saveThemeMode(context, 99);
        assertEquals(AppConfig.THEME_SYSTEM, AppConfig.loadThemeMode(context));

        // 直接塞一个坏值进去（模拟被外部改坏的偏好）
        prefs().edit().putInt("theme_mode", -7).commit();
        assertEquals(AppConfig.THEME_SYSTEM, AppConfig.loadThemeMode(context));
    }

    // ========== 音量夹取（转发给 RouteConfig 的那条规则） ==========

    @Test
    public void 音量夹取与一路的配置保持一致() {
        assertEquals(0f, AppConfig.clampVolume(-1f), 0.0001f);
        assertEquals(1f, AppConfig.clampVolume(5f), 0.0001f);
        assertEquals(0.5f, AppConfig.clampVolume(0.5f), 0.0001f);
        assertEquals(RouteConfig.DEFAULT_VOLUME, AppConfig.clampVolume(Float.NaN), 0.0001f);
    }
}
