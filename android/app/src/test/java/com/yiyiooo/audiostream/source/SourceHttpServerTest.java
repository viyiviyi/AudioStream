package com.yiyiooo.audiostream.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * HTTP 那两个接口的实测：起一个真的服务器、用真的 HTTP 客户端去拉。
 *
 * 不起 Android、不需要设备——这两个路由是 PC 端配置页的全部依赖，
 * 字段名错一个字母它就不认，所以这里逐个字段断言，不看「大概对」。
 */
public class SourceHttpServerTest {

    private SourceHttpServer server;
    private int port;
    private int lastCode;

    @Before
    public void setUp() throws Exception {
        server = new SourceHttpServer(0, new SourceHttpServer.Content() {
            @Override
            public String infoJson() {
                return SourceJson.info("a1b2c3d4e5f60718293a4b5c6d7e8f90", "Android Phone",
                        12671, 12570, "1.0.0", Arrays.asList("192.168.56.102", "10.0.3.16"));
            }

            @Override
            public String devicesJson() {
                return SourceJson.devices(Arrays.asList(
                        new SourceDevice(SourceDevice.ID_DEFAULT_INPUT, "手机麦克风（跟随系统默认）",
                                true, SourceDevice.FLOW_INPUT),
                        new SourceDevice("mic:default", "手机麦克风", false, SourceDevice.FLOW_INPUT)));
            }
        });
        port = server.start();
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void info返回体的字段与PC端HttpResult一致() throws Exception {
        String body = get("/api/info");

        assertEquals(200, lastCode);
        assertTrue(body, body.startsWith("{\"Result\":{"));
        assertTrue(body, body.endsWith("\"Success\":true}"));
        assertTrue(body, body.contains("\"MachineId\":\"a1b2c3d4e5f60718293a4b5c6d7e8f90\""));
        assertTrue(body, body.contains("\"PcName\":\"Android Phone\""));
        // 实际监听的端口要如实报出去：12670 被占时会顺延，报错了对端就连不上
        assertTrue(body, body.contains("\"TcpPort\":12671"));
        assertTrue(body, body.contains("\"HttpPort\":12570"));
        assertTrue(body, body.contains("\"Version\":\"1.0.0\""));
        assertTrue(body, body.contains("\"Addresses\":[\"192.168.56.102\",\"10.0.3.16\"]"));
    }

    @Test
    public void devices返回体带中文且四个字段齐全() throws Exception {
        String body = get("/api/devices");

        assertEquals(200, lastCode);
        // 中文必须原样出来：整段按 UTF-8 发，Content-Type 里也声明了 charset
        assertTrue(body, body.contains("\"Name\":\"手机麦克风（跟随系统默认）\""));
        assertTrue(body, body.contains("\"ID\":\"default-input\""));
        assertTrue(body, body.contains("\"Default\":true"));
        assertTrue(body, body.contains("\"Flow\":\"input\""));
        assertTrue(body, body.contains("\"ID\":\"mic:default\""));
        assertTrue(body, body.contains("\"Default\":false"));
    }

    @Test
    public void 不认识的路由回404而不是装作成功() throws Exception {
        String body = get("/api/nope");

        assertEquals(404, lastCode);
        assertTrue(body, body.contains("\"Success\":false"));
        assertTrue(body, body.contains("\"Code\":404"));
    }

    @Test
    public void 尾斜杠与查询串都当同一个路由() throws Exception {
        assertTrue(get("/api/info/"), lastCode == 200);
        assertTrue(get("/api/devices?x=1"), lastCode == 200);
    }

    @Test
    public void 非GET方法被明确拒绝() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/api/info").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.getOutputStream().close();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);

        assertEquals(405, connection.getResponseCode());
        connection.disconnect();
    }

    @Test
    public void 转义不会让名字里的引号跑出字符串() {
        assertEquals("a\\\"b", SourceJson.escape("a\"b"));
        assertEquals("a\\\\b", SourceJson.escape("a\\b"));
        assertEquals("中文", SourceJson.escape("中文"));
    }

    private String get(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);

        int code = connection.getResponseCode();
        lastCode = code;
        InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) sink.write(chunk, 0, read);
            in.close();
        }
        connection.disconnect();
        return new String(sink.toByteArray(), StandardCharsets.UTF_8);
    }
}
