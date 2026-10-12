package com.yiyiooo.audiostream.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 来源端总装的行为：端口、/api/info 与 /api/devices 的返回体、起来与停掉的对称性。
 *
 * 这里刻意用一个「一拿麦克风就报没权限」的假来源提供方：本步要验的是**服务能不能起来并正确回答**，
 * 采集本身是 Android 的事（见 MicrophoneSourceProviderTest 与真机联调）。
 * 顺带它也让「没有麦克风也能起服务」这件事被钉住——否则 PC 端连设备列表都看不到，
 * 用户根本不知道该去开权限。
 */
public class SourceServerTest {

    private static final String MACHINE_ID = "0123456789abcdef0123456789abcdef";

    private static final class StubProvider implements AudioSourceProvider {
        @Override
        public AudioSource microphone() throws SourceUnavailableException {
            throw new SourceUnavailableException(SourceUnavailableException.DENIED, "测试里没有麦克风");
        }
    }

    private static SourceServer startServer(int httpPort) {
        SourceServer server = new SourceServer(new StubProvider(), MACHINE_ID, "测试手机",
                "1.0.0-test", httpPort);
        assertNull("起服务不该失败", server.start());
        return server;
    }

    private static String get(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while (stream != null && (read = stream.read(chunk)) > 0) sink.write(chunk, 0, read);
            assertEquals("HTTP 状态码", 200, code);
            return new String(sink.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    @Test
    public void 起来之后两个端口都拿到实际值() {
        SourceServer server = startServer(0);
        try {
            assertTrue(server.isRunning());
            assertTrue("配置端口应已分配", server.httpPort() > 0);
            // 本机可能正跑着 PC 端 AudioStream（它占着 12670），所以只断言落在区间内，
            // 「报了哪个端口」由下面那条与实际监听一致的断言来保证
            assertTrue("音频口应落在 " + AudioTcpServer.PORT_START + "-" + (AudioTcpServer.PORT_END - 1)
                            + "，实际 " + server.tcpPort(),
                    server.tcpPort() >= AudioTcpServer.PORT_START
                            && server.tcpPort() < AudioTcpServer.PORT_END);
            assertEquals(0, server.sessionCount());
            assertNull(server.lastError());
        } finally {
            server.close();
        }
    }

    @Test
    public void info里报的音频端口与实际监听的一致() throws Exception {
        SourceServer server = startServer(0);
        try {
            String body = get(server.httpPort(), "/api/info");
            assertTrue("应带上机器号：" + body, body.contains("\"MachineId\":\"" + MACHINE_ID + "\""));
            assertTrue("名称应在：" + body, body.contains("\"PcName\":\"测试手机\""));
            // 这一条是这层最要紧的断言：12670 被占会顺延，报错端口的话 PC 端就连到一个空口上，
            // 现象是「设备列表能看到、一点连接就没声音」，最难查的那类
            assertTrue("TcpPort 必须等于实际监听的端口：" + body,
                    body.contains("\"TcpPort\":" + server.tcpPort() + ","));
            assertTrue("HttpPort 也要是实际值：" + body,
                    body.contains("\"HttpPort\":" + server.httpPort() + ","));
            assertTrue("版本应带上：" + body, body.contains("\"Version\":\"1.0.0-test\""));
            assertTrue("要有 Addresses 字段：" + body, body.contains("\"Addresses\":["));
            assertTrue("成功体形状：" + body, body.contains("\"Success\":true}"));
        } finally {
            server.close();
        }
    }

    @Test
    public void devices列出手机麦克风条目() throws Exception {
        SourceServer server = startServer(0);
        try {
            String body = get(server.httpPort(), "/api/devices");
            assertTrue(body.contains("\"ID\":\"default-input\""));
            assertTrue(body.contains("\"ID\":\"mic:default\""));
            assertTrue("Flow 必须是 input：" + body, body.contains("\"Flow\":\"input\""));
            assertTrue("跟随项要标成默认：" + body, body.contains("\"Default\":true"));
            assertTrue("成功体形状：" + body, body.contains("\"Success\":true}"));
        } finally {
            server.close();
        }
    }

    @Test
    public void close之后端口放掉且状态回到未开启() throws Exception {
        SourceServer server = startServer(0);
        int httpPort = server.httpPort();
        server.close();
        assertFalse(server.isRunning());
        assertEquals(0, server.httpPort());
        assertEquals(0, server.tcpPort());
        assertTrue("应该说明未开启：" + server.statusLine(), server.statusLine().contains("未开启"));
        // 端口真的放掉了才算停干净：能立刻原地再绑一次
        ServerSocket probe = new ServerSocket(httpPort);
        try {
            assertTrue(probe.isBound());
        } finally {
            probe.close();
        }
    }

    @Test
    public void 配置端口被占时给出中文原因并且不留着音频口() throws Exception {
        ServerSocket blocker = new ServerSocket(0);
        try {
            SourceServer server = new SourceServer(new StubProvider(), MACHINE_ID, "测试手机",
                    "1.0.0-test", blocker.getLocalPort());
            String error = server.start();
            assertNotNull("端口被占必须报错，不能装作起来了", error);
            assertTrue("原因要说人话：" + error, error.contains("配置接口端口"));
            assertFalse(server.isRunning());
            assertEquals("音频口不能留着", 0, server.tcpPort());
            server.close();
        } finally {
            blocker.close();
        }
    }
}
