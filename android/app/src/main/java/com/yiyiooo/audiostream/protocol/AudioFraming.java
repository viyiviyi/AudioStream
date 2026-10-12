package com.yiyiooo.audiostream.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 帧的编解码。帧格式：[4 字节小端长度][1 字节类型][负载]，长度**包含**类型字节。
 *
 * 与 C# 端的 {@code AudioStream.AudioServer.AudioFraming} 逐字节一致——两端不能互通的话，
 * 「连上了但没有声音」这种问题在现场是看不出来的，所以这里的每个常量都能在 C# 那边找到对应的一行。
 */
public final class AudioFraming {

    /** 帧头：4 字节长度 + 1 字节类型。 */
    public static final int HEADER_SIZE = 5;

    /**
     * 单帧负载上限。这是个防护值：连接对端若不是本程序（或者内存数据坏了），
     * 长度字段可能是任意数，不拦住就会照着它去分配一大块内存。
     * 正常音频帧在几 KB 量级，取 1MB 已经宽裕得多。
     */
    public static final int MAX_PAYLOAD = 1024 * 1024;

    /** 读帧时负载缓冲的默认容量，与 C# 端 FrameReader 的默认值一致。 */
    public static final int DEFAULT_PAYLOAD_CAPACITY = 128 * 1024;

    private AudioFraming() {
    }

    /** 把一条消息编码成完整帧字节。 */
    public static byte[] encode(FrameKind kind, byte[] payload, int offset, int count) {
        if (count < 0) count = 0;
        byte[] frame = new byte[HEADER_SIZE + count];
        int length = count + 1; // 长度含类型字节
        frame[0] = (byte) (length & 0xFF);
        frame[1] = (byte) ((length >> 8) & 0xFF);
        frame[2] = (byte) ((length >> 16) & 0xFF);
        frame[3] = (byte) ((length >> 24) & 0xFF);
        frame[4] = (byte) kind.code();
        if (count > 0 && payload != null) {
            System.arraycopy(payload, offset, frame, HEADER_SIZE, count);
        }
        return frame;
    }

    public static byte[] encodeText(String text) {
        byte[] bytes = utf8(text == null ? "" : text);
        return encode(FrameKind.TEXT, bytes, 0, bytes.length);
    }

    public static byte[] encodeWaveFormat(byte[] payload) {
        return encode(FrameKind.WAVE_FORMAT, payload, 0, payload == null ? 0 : payload.length);
    }

    /**
     * 一次读满 count 个字节。TCP 的一次 read 只保证「至少 1 字节」，
     * 「读一次就当读全了」在跨网段时会拿到半条数据。
     *
     * @return false 表示对端已经关闭或流不可用，此时读到的字节数已经确定不足。
     */
    public static boolean readExact(InputStream stream, byte[] buffer, int offset, int count) throws IOException {
        if (count <= 0) return true;
        int done = 0;
        while (done < count) {
            int read = stream.read(buffer, offset + done, count - done);
            if (read <= 0) return false;
            done += read;
        }
        return true;
    }

    /** 一次把整帧写出去。写这条连接的一定是同一个线程（见 OutgoingQueue），所以不做额外同步。 */
    public static void writeFrame(OutputStream stream, byte[] frame) throws IOException {
        stream.write(frame, 0, frame.length);
        stream.flush();
    }

    /** UTF-8 编码。所有文本命令都是 UTF-8，与 C# 端的 Encoding.UTF8 一致。 */
    public static byte[] utf8(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // Android 与 JVM 必然支持 UTF-8，走不到这里；真走到了也不该悄悄换编码。
            throw new IllegalStateException("UTF-8 不受支持", e);
        }
    }

    /** UTF-8 解码。 */
    public static String fromUtf8(byte[] bytes, int offset, int count) {
        try {
            return new String(bytes, offset, count, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不受支持", e);
        }
    }
}
