package com.yiyiooo.audiostream.source;

import java.util.List;

/**
 * 拼 PC 端要读的那几段 JSON。
 *
 * 手写而不用 org.json：结构固定、字段就这几个，手写能一眼看出发出去的字节长什么样，
 * 也免得在 JVM 单元测试里再拉一个依赖。名字与版本号都可能带引号或反斜杠
 * （中文设备名很常见），所以一律走 {@link #escape}。
 *
 * 形状与 PC 端 {@code HttpResult<T>} 一致：成功是 {@code {"Result":…,"Success":true}}，
 * 失败是 {@code {"Success":false,"Code":…,"Message":…}}。
 */
public final class SourceJson {

    private SourceJson() {
    }

    /**
     * {@code /api/info} 的返回体：对端拿它认身份、拿 TcpPort 去连音频口。
     *
     * TcpPort 必须是**实际**监听到的端口：12670 被占用时会往后顺延，
     * 报错了端口对端就连到一个不存在的口上。
     */
    public static String info(String machineId, String pcName, int tcpPort, int httpPort,
                              String version, List<String> addresses) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"Result\":{");
        out.append("\"MachineId\":\"").append(escape(machineId)).append("\",");
        out.append("\"PcName\":\"").append(escape(pcName)).append("\",");
        out.append("\"TcpPort\":").append(tcpPort).append(',');
        out.append("\"HttpPort\":").append(httpPort).append(',');
        out.append("\"Version\":\"").append(escape(version)).append("\",");
        out.append("\"Addresses\":[");
        if (addresses != null) {
            for (int i = 0; i < addresses.size(); i++) {
                if (i > 0) out.append(',');
                out.append('"').append(escape(addresses.get(i))).append('"');
            }
        }
        out.append("]},\"Success\":true}");
        return out.toString();
    }

    /** {@code /api/devices} 的返回体。字段名与顺序对齐 PC 端自己的设备接口。 */
    public static String devices(List<SourceDevice> devices) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"Result\":[");
        if (devices != null) {
            for (int i = 0; i < devices.size(); i++) {
                if (i > 0) out.append(',');
                SourceDevice device = devices.get(i);
                out.append("{\"Name\":\"").append(escape(device.name())).append("\",");
                out.append("\"ID\":\"").append(escape(device.id())).append("\",");
                out.append("\"Default\":").append(device.isDefault()).append(',');
                out.append("\"Flow\":\"").append(escape(device.flow())).append("\"}");
            }
        }
        out.append("],\"Success\":true}");
        return out.toString();
    }

    /** 出错时 PC 端会把它读出来显示给人看。 */
    public static String error(int code, String message) {
        return "{\"Success\":false,\"Code\":" + code
                + ",\"Message\":\"" + escape(message) + "\"}";
    }

    /** JSON 字符串转义。非 ASCII 原样留着，整段按 UTF-8 发出去。 */
    public static String escape(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
