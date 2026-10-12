package com.yiyiooo.audiostream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.pull.PullSession;
import com.yiyiooo.audiostream.pull.PullState;
import com.yiyiooo.audiostream.source.DeviceSourceProvider;
import com.yiyiooo.audiostream.source.SourceServer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在真机上把来源端整条链路跑通：起服务（真麦克风、真端口）→ 用本工程自己的拉取方
 * （与 PC 端 NetAudioRedirector 同口径写的 PullSession）把它拉下来。
 *
 * 为什么值得这么绕：JVM 上验不到的两件事只有在这里能暴露——
 * 一是 AudioRecord 在这台设备上到底能不能按协商的格式采到东西；
 * 二是真机上 12570/12670 能不能绑上、/api/info 报的端口是不是真的那个。
 *
 * 跑之前先授权（测试自己也会断言这件事，免得失败时报一堆看不懂的现象）：
 *   adb shell pm grant com.yiyiooo.audiostream android.permission.RECORD_AUDIO
 */
@RunWith(AndroidJUnit4.class)
public class SourceServerDeviceTest {

    /**
     * 录音权限由测试自己授（走 UiAutomation，不依赖外部的 adb shell pm grant）。
     * 不加这一条的话，第一次跑得通、重装一次就变成「没有权限」，
     * 而失败信息跟来源端本身毫无关系。
     */
    @Rule
    public GrantPermissionRule recordAudio =
            GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    private static final String MACHINE_ID = "ffffffffffffffffffffffffffffffff";
    private static final String PULLER_ID = "11111111111111111111111111111111";

    /** 拉多久。够看清「一直在来」即可，不必长到让一次联调变成几分钟。 */
    private static final int PULL_SECONDS = 5;

    private static String httpGet(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int read;
            while (stream != null && (read = stream.read(chunk)) > 0) sink.write(chunk, 0, read);
            assertEquals("HTTP 状态码（" + path + "）", 200, code);
            return new String(sink.toByteArray(), "UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    public void 起来源端并让本机拉取方真的拉下一路麦克风() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        assertEquals("先把录音权限授上：adb shell pm grant "
                        + context.getPackageName() + " android.permission.RECORD_AUDIO",
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));

        DeviceSourceProvider provider = new DeviceSourceProvider(context);
        SourceServer server = new SourceServer(provider, MACHINE_ID, "测试手机", "1.0.0-test",
                SourceServer.HTTP_PORT);
        String error = server.start();
        assertNull("来源端应该能起来：" + error, error);

        try {
            // 1) HTTP 两个路由：PC 端配置页问的就是这两个
            String info = httpGet(server.httpPort(), "/api/info");
            System.out.println("[来源端] /api/info = " + info);
            assertTrue("/api/info 里的 TcpPort 必须与实际监听一致：" + info,
                    info.contains("\"TcpPort\":" + server.tcpPort() + ","));
            assertTrue("机器号应在里面：" + info, info.contains("\"MachineId\":\"" + MACHINE_ID + "\""));

            String devices = httpGet(server.httpPort(), "/api/devices");
            System.out.println("[来源端] /api/devices = " + devices);
            assertTrue("设备清单要有麦克风：" + devices, devices.contains("\"ID\":\"mic:default\""));
            assertTrue("要标成输入：" + devices, devices.contains("\"Flow\":\"input\""));
            // 内录：手机现在也提供「它正在放的声音」这一类（输出类），
            // 与 PC 端那边的输出设备（环回）是同一个位置，PC 端配置页按 Flow 分组显示
            assertTrue("设备清单要有内录：" + devices, devices.contains("\"ID\":\"capture:default\""));
            assertTrue("设备清单要有内录的跟随项：" + devices, devices.contains("\"ID\":\"default\""));
            assertTrue("内录要标成输出：" + devices, devices.contains("\"Flow\":\"output\""));

            // 2) TCP：用工程自己的拉取方真的拉一路下来
            final AtomicLong totalBytes = new AtomicLong();
            final AtomicLong nonZeroBytes = new AtomicLong();
            final AtomicLong frames = new AtomicLong();
            final AtomicReference<AudioFormat> format = new AtomicReference<>();
            final AtomicReference<PullState> lastState = new AtomicReference<>(PullState.IDLE);
            final CountDownLatch firstAudio = new CountDownLatch(1);

            PullSession pull = new PullSession("127.0.0.1", server.tcpPort(), "mic:default", "手机麦克风",
                    PULLER_ID, "测试拉取端", new PullSession.Listener() {
                @Override
                public void onState(PullState state, String detail) {
                    lastState.set(state);
                    System.out.println("[拉取方] 状态 " + state + "：" + detail);
                }

                @Override
                public void onFormat(AudioFormat value) {
                    format.set(value);
                }

                @Override
                public void onAudio(byte[] data, int offset, int length) {
                    frames.incrementAndGet();
                    totalBytes.addAndGet(length);
                    long nz = 0;
                    for (int i = offset; i < offset + length; i++) {
                        if (data[i] != 0) nz++;
                    }
                    nonZeroBytes.addAndGet(nz);
                    firstAudio.countDown();
                }
            });

            pull.start();
            try {
                assertTrue("5 秒内该收到第一段音频（当前状态 " + lastState.get() + "）",
                        firstAudio.await(5, TimeUnit.SECONDS));
                Thread.sleep(PULL_SECONDS * 1000L);
            } finally {
                pull.close();
            }

            AudioFormat got = format.get();
            System.out.println("[来源端] 协商格式 = " + (got == null ? "null" : got.describe()));
            assertTrue("应该协商出音频格式", got != null);
            assertEquals("采样率", 48000, got.sampleRate);
            assertEquals("位深", 16, got.bitsPerSample);
            assertEquals("声道数", 1, got.channels);

            long bytes = totalBytes.get();
            long expected = (long) got.bytesPerSecond() * PULL_SECONDS;
            System.out.println("[来源端] 收到 " + bytes + " 字节 / " + PULL_SECONDS + " 秒（理论 "
                    + expected + "），帧数 " + frames.get()
                    + "，非零字节 " + nonZeroBytes.get()
                    + "（" + (bytes == 0 ? 0 : nonZeroBytes.get() * 100 / bytes) + "%）");

            // 只断言「一直在来、量级对」：这台虚拟机没有真实麦克风输入时采到的会是静音，
            // 那是环境的事实，不该让「链路是通的」这条结论跟着一起挂掉
            assertTrue("收到的字节数应当接近理论值（" + bytes + " vs " + expected + "）",
                    bytes > expected / 2);
        } finally {
            server.close();
            provider.release();
        }
    }

    /**
     * 内录在真机上的**未授权**行为——也就是手机刚装好、用户还没点过任何授权时的状态。
     *
     * 为什么单独验这一条：内录的完整端到端（真的把手机正在放的声音采下来）需要用户在系统弹框里
     * 点「同意」，而自动化点不了那个框。但「没授权时给 PC 端的拒绝对不对、原因能不能照着做」
     * 完全不需要授权，而且它正是用户第一次尝试内录时唯一会走到的路径——
     * 这条路要是哑了，用户看到的就是「选了个设备，然后就没了」。
     */
    @Test
    public void 没授权内录时点单会拿到可照做的拒绝原因() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeviceSourceProvider provider = new DeviceSourceProvider(context);
        assertTrue("这条用例的前提是「本次进程里没点过内录授权」，现在却是已授权",
                !provider.hasCaptureGrant());

        SourceServer server = new SourceServer(provider, MACHINE_ID, "测试手机", "1.0.0-test",
                SourceServer.HTTP_PORT);
        String error = server.start();
        assertNull("来源端应该能起来：" + error, error);

        try {
            // 用最朴素的客户端直连，读协议里那两段原始字符串：拒绝原因要能被 C# 端原样解码后
            // 显示给用户，所以这里不经过任何「翻成人话」的层
            java.net.Socket socket = new java.net.Socket("127.0.0.1", server.tcpPort());
            socket.setSoTimeout(5000);
            try {
                java.io.OutputStream out = socket.getOutputStream();
                com.yiyiooo.audiostream.protocol.FrameReader reader =
                        new com.yiyiooo.audiostream.protocol.FrameReader(socket.getInputStream());

                sendText(out, "/Hello/" + PULLER_ID + "/测试拉取端");
                assertTrue("握手应当有应答", reader.readNext());
                System.out.println("[内录] 握手应答 = " + reader.readText());

                sendText(out, "/WaveFormat/capture:default/手机内录");
                assertTrue("点单内录应当有应答（不能默默断开）", reader.readNext());
                String reply = reader.readText();
                System.out.println("[内录] 点单应答 = " + reply);

                assertTrue("没授权内录时应当是 denied，实际：" + reply,
                        reply.startsWith("/Reject/denied/"));
                String reason = java.net.URLDecoder.decode(
                        reply.substring("/Reject/denied/".length()), "UTF-8");
                System.out.println("[内录] 给用户看的原因 = " + reason);
                assertTrue("原因要告诉用户去点「允许内录」，实际：" + reason,
                        reason.contains("允许内录"));
            } finally {
                socket.close();
            }
        } finally {
            server.close();
            provider.release();
        }
    }

    private static void sendText(java.io.OutputStream out, String text) throws Exception {
        byte[] frame = com.yiyiooo.audiostream.protocol.AudioFraming.encodeText(text);
        out.write(frame, 0, frame.length);
        out.flush();
    }
}
