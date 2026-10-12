package com.yiyiooo.audiostream.pull;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * 把对端的拒绝原因翻成用户能照着处理的话。
 *
 * 与 C# 端 {@code NetAudioRedirector.RejectText} 逐条对齐：拒绝是「此刻不行」而不是「永远不行」，
 * 所以每一条都要说清是什么占住了、该怎么办。笼统一句「连接失败」等于没说。
 */
public final class RejectText {

    private RejectText() {
    }

    /**
     * @param reply 对端回的整条文本，形如 {@code /Reject/nodevice/<转义过的原因>}
     * @return 直接可以显示给用户的中文
     */
    public static String describe(String reply) {
        if (reply == null) reply = "";
        if (reply.startsWith("/Reject/self")) {
            return "对端认出这是它自己。如果填的是本机地址，请把 IP 留空，改成本机设备之间流转。";
        }
        if (reply.startsWith("/Reject/loop")) {
            return "对端拒绝：那个设备上此刻正播着从别处拉来的网络音频，再采一遍会绕成回路。等它播完或换个设备再试。";
        }
        if (reply.startsWith("/Reject/nodevice")) {
            String reason = reasonOf(reply);
            if (reason != null && !reason.isEmpty()) {
                return "对端拒绝：" + reason + "。请让对方确认那个默认设备在，或者改选一个具体设备。";
            }
            return "对端拒绝：对端此刻没有可用的默认设备。请让对方确认那个默认设备在，或者改选一个具体设备。";
        }
        if (reply.startsWith("/Reject/unavailable")) {
            String reason = reasonOf(reply);
            if (reason != null && !reason.isEmpty()) {
                return "对端拒绝：" + reason + "。请让对方换一块设备，或者关掉占用它的程序。";
            }
            return "对端拒绝：那块设备此刻打不开，可能被别的程序独占，或者驱动不支持共享模式采集。";
        }
        if (reply.startsWith("/Reject/denied")) {
            return "对端拒绝：本机没有被允许拉取那个设备。请让对方在它的配置页「拉取授权」里放行，或者换个设备。";
        }
        if (reply.startsWith("/Reject/timeout")) {
            return "对端把这次拉取挂起来等人确认，但一直没人确认，等超时了。请让对方在它机器上点一下批准，然后再试。";
        }
        if (reply.startsWith("/Reject/")) {
            return "对端拒绝了这条拉取（" + reply + "）。";
        }
        return null;
    }

    /**
     * 从 {@code /Reject/xxx/原因} 这种应答里取回对端捎回的具体原因，没带就返回空串。
     *
     * 只按前三段切开：原因里本身可能带斜杠，多切会把原因切碎。
     */
    public static String reasonOf(String reply) {
        if (reply == null) return "";
        String[] segments = reply.split("/", 4);
        if (segments.length < 4) return "";
        try {
            // C# 侧写的是 Uri.EscapeDataString，这里必须用 URLDecoder 的 UTF-8 解回来。
            // 注意：不能对整条 reply 用 URLDecoder——它是按 '+' 当空格的表单解码，
            // 而 EscapeDataString 编出来的空格是 %20，两者只在解码这一段上对得上。
            return URLDecoder.decode(segments[3], "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return segments[3];
        } catch (IllegalArgumentException e) {
            // 转义不合法（对端不是本程序、或者原因里本来就有 %）：原样留着比抛异常有用
            return segments[3];
        }
    }

    /**
     * 把一段文本按 C# 的 {@code Uri.EscapeDataString} 规则转义。
     *
     * 必须是同一套规则：对端用 {@code Uri.UnescapeDataString} 解，而它**不把 '+' 当空格**，
     * 所以不能用 {@code URLEncoder}（它会把空格编成 '+'，对端解出来就是一个加号）。
     * 非保留字符按 RFC 3986 只留 {@code A-Za-z0-9-._~}。
     */
    public static String escapeDataString(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 8);
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            bytes = text.getBytes();
        }
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
