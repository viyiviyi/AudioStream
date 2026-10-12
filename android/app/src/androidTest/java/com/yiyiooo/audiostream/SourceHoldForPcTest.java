package com.yiyiooo.audiostream;

import static org.junit.Assert.assertNull;

import android.Manifest;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.yiyiooo.audiostream.source.LocalAddresses;
import com.yiyiooo.audiostream.source.DeviceSourceProvider;
import com.yiyiooo.audiostream.source.SourceServer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 联调用的「保持器」：把来源端开起来并维持一段时间，好让 PC 端在那边真的连过来。
 *
 * 为什么不走界面：这台联调机的图形栈（VirtualBox 里的 Android-x86，gralloc.ranchu + host GL）
 * 会成片地卡死——screencap 超时 30 秒、System UI 自己 ANR，界面根本画不出来。
 * 而来源端要验的东西（HTTP 设备接口、TCP 音频服务、麦克风采集）全都不经过屏幕，
 * 所以直接把它开起来更接近事实，也免得把「设备画不出图」误当成「来源端不行」。
 *
 * 默认只保持 3 秒，这样它夹在正常的 connectedDebugAndroidTest 里几乎不占时间；
 * 联调时用参数拉长：
 *   adb shell am instrument -w -e class com.yiyiooo.audiostream.SourceHoldForPcTest \
 *       -e holdSeconds 300 com.yiyiooo.audiostream.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class SourceHoldForPcTest {

    @Rule
    public GrantPermissionRule recordAudio =
            GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    @Test
    public void 保持来源端开启以便从PC端来拉() throws Exception {
        long holdSeconds = 3;
        String arg = InstrumentationRegistry.getArguments().getString("holdSeconds");
        if (arg != null && !arg.isEmpty()) {
            holdSeconds = Long.parseLong(arg);
        }

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeviceSourceProvider provider = new DeviceSourceProvider(context);
        SourceServer server = new SourceServer(provider, "aabbccddeeff00112233445566778899",
                "安卓手机", "1.0.0", SourceServer.HTTP_PORT);
        String error = server.start();
        assertNull("来源端应该能起来：" + error, error);

        System.out.println("[保持] 来源端已开启：配置 " + server.httpPort() + "，音频 " + server.tcpPort()
                + "，地址 " + LocalAddresses.list() + "，保持 " + holdSeconds + " 秒");

        try {
            long deadline = System.currentTimeMillis() + holdSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2000);
                System.out.println("[保持] " + server.statusLine()
                        + "　累计被连 " + server.totalSessions() + " 次");
            }
        } finally {
            System.out.println("[保持] 收工，累计被连 " + server.totalSessions() + " 次");
            server.close();
            provider.release();
        }
    }
}
