package com.yiyiooo.audiostream.discovery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 问对端「你是谁」「你有哪些音频设备」。
 *
 * 走 PC 端配置页用的同两个接口（{@code /api/info}、{@code /api/devices}），返回体的形状与
 * C# 端 {@code HttpResult<T>} 一致：{@code {"Result":…,"Success":true}}。
 *
 * 两个必须记住的约束：
 * <ol>
 *   <li><b>不能在主线程调用</b>。Android 会把主线程上的网络访问直接判成
 *       {@code NetworkOnMainThreadException}，而且这个异常在界面上表现为「点了没反应」。</li>
 *   <li>明文 HTTP 需要 {@code usesCleartextTraffic}。Android 9 起默认禁止明文，
 *       漏掉这一步的表现是「连不上」，但报的却是安全策略而不是网络不可达。</li>
 * </ol>
 */
public final class PcDiscovery {

    /** PC 端 HTTP 的固定端口（C# 端 HttpServer 里写死 12570）。 */
    public static final int DEFAULT_HTTP_PORT = 12570;

    /** 连不上就是连不上，内网里通不通几毫秒就知道，不按系统默认的二十来秒干等。 */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /** 设备枚举要开声卡、读注册表，比 /info 慢，给它宽一点。PC 端自己的界面用的也是 6000。 */
    private static final int READ_TIMEOUT_MS = 6000;

    private final int httpPort;

    public PcDiscovery() {
        this(DEFAULT_HTTP_PORT);
    }

    public PcDiscovery(int httpPort) {
        this.httpPort = httpPort > 0 ? httpPort : DEFAULT_HTTP_PORT;
    }

    public int httpPort() {
        return httpPort;
    }

    /** 对端身份、名字、音频端口。 */
    public PcInfo fetchInfo(String host) throws DiscoveryException {
        JSONObject result = getObject(host, "/api/info");
        int httpPortFromPeer = result.optInt("HttpPort", httpPort);
        List<String> addresses = new ArrayList<String>();
        JSONArray array = result.optJSONArray("Addresses");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String address = array.optString(i, "");
                if (!address.isEmpty()) addresses.add(address);
            }
        }
        return new PcInfo(
                result.optString("MachineId", ""),
                result.optString("PcName", ""),
                result.optInt("TcpPort", 0),
                httpPortFromPeer,
                result.optString("Version", ""),
                addresses);
    }

    /** 对端的全部音频端点：输出在前、输入在后，各自标注是不是系统默认（顺序由对端定，这里不重排）。 */
    public List<DeviceInfo> fetchDevices(String host) throws DiscoveryException {
        JSONArray array = getArray(host, "/api/devices");
        List<DeviceInfo> devices = new ArrayList<DeviceInfo>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("ID", "");
            // 没设备号的条目对端也用不了（它拿这个号去开声卡），宁可在这里丢掉，
            // 也不要在点单之后收到一句莫名其妙的拒绝。
            if (id.isEmpty()) continue;
            devices.add(new DeviceInfo(
                    item.optString("Name", id),
                    id,
                    item.optBoolean("Default", false),
                    item.optString("Flow", DeviceInfo.FLOW_OUTPUT)));
        }
        return devices;
    }

    private JSONObject getObject(String host, String path) throws DiscoveryException {
        String body = httpGet(host, path);
        try {
            JSONObject root = new JSONObject(body);
            requireSuccess(root, host);
            JSONObject result = root.optJSONObject("Result");
            if (result == null) {
                throw new DiscoveryException("对方回的 " + path + " 里没有 Result，可能不是 AudioStream 的地址");
            }
            return result;
        } catch (JSONException e) {
            throw new DiscoveryException("对方回的 " + path + " 不是能看懂的 JSON（可能这个地址上不是 AudioStream）", e);
        }
    }

    private JSONArray getArray(String host, String path) throws DiscoveryException {
        String body = httpGet(host, path);
        try {
            JSONObject root = new JSONObject(body);
            requireSuccess(root, host);
            JSONArray result = root.optJSONArray("Result");
            if (result == null) {
                // 对端设备一枚都没有时 Result 可能是 null 而不是空数组，这不该算失败。
                if (root.isNull("Result")) return new JSONArray();
                throw new DiscoveryException("对方回的 " + path + " 里没有设备列表，可能不是 AudioStream 的地址");
            }
            return result;
        } catch (JSONException e) {
            throw new DiscoveryException("对方回的 " + path + " 不是能看懂的 JSON（可能这个地址上不是 AudioStream）", e);
        }
    }

    private void requireSuccess(JSONObject root, String host) throws DiscoveryException {
        if (root.optBoolean("Success", true)) return;
        String message = root.optString("Message", "");
        if (message.isEmpty()) {
            JSONObject error = root.optJSONObject("error");
            if (error != null) message = error.optString("Message", "");
        }
        throw new DiscoveryException("对方拒绝了这次查询" + (message.isEmpty() ? "" : "：" + message)
                + "（" + host + "）");
    }

    private String httpGet(String host, String path) throws DiscoveryException {
        String address = host == null ? "" : host.trim();
        if (address.isEmpty()) {
            throw new DiscoveryException("请先填对方电脑的 IP");
        }
        // 允许用户直接粘一个 http://ip:port 进来，省得他先自己去掉前缀
        if (address.startsWith("http://")) address = address.substring("http://".length());
        else if (address.startsWith("https://")) address = address.substring("https://".length());
        int slash = address.indexOf('/');
        if (slash >= 0) address = address.substring(0, slash);

        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + address + (address.indexOf(':') >= 0 ? "" : ":" + httpPort) + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setUseCaches(false);
            int code = connection.getResponseCode();
            String body = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code != HttpURLConnection.HTTP_OK) {
                throw new DiscoveryException("对方回了 HTTP " + code + describePeerError(body) + "（" + address + "）");
            }
            if (body.isEmpty()) {
                throw new DiscoveryException("对方没有任何回应（" + address + "）");
            }
            return body;
        } catch (MalformedURLException e) {
            throw new DiscoveryException("这个地址填得不对：" + address, e);
        } catch (UnknownHostException e) {
            throw new DiscoveryException("找不到这台机器：" + address, e);
        } catch (ConnectException e) {
            throw new DiscoveryException("连不上 " + address + ":" + httpPort
                    + "，请确认对方开着机、AudioStream 正在运行、两台设备在同一个网段", e);
        } catch (SocketTimeoutException e) {
            throw new DiscoveryException("连上了 " + address + " 但迟迟没有回应，可能对方正忙或网络不稳", e);
        } catch (IOException e) {
            throw new DiscoveryException("和 " + address + " 通信失败：" + e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 错误体里通常带一句对端自己的中文说明，能读出来就附上，比只报一个状态码有用。 */
    private static String describePeerError(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JSONObject root = new JSONObject(body);
            String message = root.optString("Message", "");
            if (message.isEmpty()) {
                JSONObject error = root.optJSONObject("error");
                if (error != null) message = error.optString("Message", "");
            }
            return message.isEmpty() ? "" : "：" + message;
        } catch (JSONException ignored) {
            return "";
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        try {
            ByteArrayOutputStream sink = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) sink.write(chunk, 0, read);
            return new String(sink.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
