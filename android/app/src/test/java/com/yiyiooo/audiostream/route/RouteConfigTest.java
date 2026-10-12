package com.yiyiooo.audiostream.route;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 一路配置的往返与去重规则。
 *
 * 这些断言护着的是「重启之后配置还在」以及「同一块设备不会变成两路」这两件事——
 * 它们坏了都不会崩，只会让用户看到重复的卡片或丢失的配置。
 *
 * 走 Robolectric 而不是纯 JVM：org.json 是平台自带的类，纯 JVM 单元测试里它只是一层空壳
 * （工程开了 returnDefaultValues，方法返回 null 而不报错），那样这组「往返是否一致」的断言
 * 会因为 JSONObject 恒为 null 而全部失真——测试还是绿的，只是什么都没验。
 */
@RunWith(RobolectricTestRunner.class)
public class RouteConfigTest {

    private static final String HOST = "192.168.1.10";
    private static final String DEVICE = "01d5c8a4-3f2b-4e6a-9c7d-8f1e2a3b4c5d";

    @Test
    public void id由地址与设备号决定而不是随机() {
        RouteConfig first = RouteConfig.create(HOST, DEVICE, "扬声器", 1f);
        RouteConfig again = RouteConfig.create(HOST, DEVICE, "扬声器", 0.5f);
        // 音量不同也算同一路：id 标识的是「哪台电脑的哪块设备」，不是「哪一次配置」
        assertEquals(first.id(), again.id());

        RouteConfig otherDevice = RouteConfig.create(HOST, DEVICE + "x", "扬声器", 1f);
        assertNotEquals(first.id(), otherDevice.id());
    }

    @Test
    public void 拼接键不会因为字段里含分隔符而撞车() {
        // 设备号与主机名里都可能出现 \ | : . 这些常见分隔符，用它们拼会让不同的组合撞成同一个键
        assertNotEquals(RouteConfig.key("a", "b|c"), RouteConfig.key("a|b", "c"));
        assertEquals(RouteConfig.key("a", "b|c"), RouteConfig.key("a", "b|c"));
    }

    @Test
    public void 落盘再读回来完全一致() throws Exception {
        RouteConfig original = RouteConfig.create(HOST, DEVICE, "扬声器 (Realtek)", 0.35f);
        RouteConfig restored = RouteConfig.fromJson(new JSONObject(original.toJson().toString()));

        assertEquals(original, restored);
        assertEquals(original.volume(), restored.volume(), 0.0001f);
        assertEquals("扬声器 (Realtek)", restored.deviceName());
    }

    @Test
    public void 缺地址或缺设备号的记录会被丢掉() throws Exception {
        // 留着它只会在界面上多一张永远连不上的卡片，用户还得自己删
        assertNull(RouteConfig.fromJson(new JSONObject("{\"deviceId\":\"x\"}")));
        assertNull(RouteConfig.fromJson(new JSONObject("{\"host\":\"1.2.3.4\"}")));
        assertNull(RouteConfig.fromJson(new JSONObject("{}")));
    }

    @Test
    public void 落盘里的越界音量会被夹回来() throws Exception {
        RouteConfig tooLoud = RouteConfig.fromJson(new JSONObject(
                "{\"host\":\"1.2.3.4\",\"deviceId\":\"x\",\"volume\":9.9}"));
        assertEquals(1f, tooLoud.volume(), 0.0001f);

        RouteConfig negative = RouteConfig.fromJson(new JSONObject(
                "{\"host\":\"1.2.3.4\",\"deviceId\":\"x\",\"volume\":-3}"));
        assertEquals(0f, negative.volume(), 0.0001f);
    }

    @Test
    public void 静音与满增益不会被当成没设过() throws Exception {
        // 0 是用户认真调出来的静音，不能与「没存过」混为一谈
        RouteConfig muted = RouteConfig.create(HOST, DEVICE, "扬声器", 0f);
        assertEquals(0f, RouteConfig.fromJson(muted.toJson()).volume(), 0.0001f);

        RouteConfig full = RouteConfig.create(HOST, DEVICE, "扬声器", 1f);
        assertEquals(1f, RouteConfig.fromJson(full.toJson()).volume(), 0.0001f);
    }

    @Test
    public void NaN被当成默认值而不是静音() {
        assertEquals(RouteConfig.DEFAULT_VOLUME, RouteConfig.clampVolume(Float.NaN), 0.0001f);
    }

    @Test
    public void 改音量是产生新配置而不是原地改() {
        RouteConfig original = RouteConfig.create(HOST, DEVICE, "扬声器", 1f);
        RouteConfig quieter = original.withVolume(0.2f);

        assertEquals(0.2f, quieter.volume(), 0.0001f);
        assertEquals("原对象不该被改动", 1f, original.volume(), 0.0001f);
        assertEquals("除了音量，其余字段原样保留", original.id(), quieter.id());
    }

    @Test
    public void 设备名缺失时标签退回设备号而不是留空() {
        RouteConfig noName = RouteConfig.create(HOST, DEVICE, "", 1f);
        assertTrue(noName.sourceLabel().contains(DEVICE.substring(0, 8)));
        assertFalse(noName.sourceLabel().endsWith("· "));
    }
}
