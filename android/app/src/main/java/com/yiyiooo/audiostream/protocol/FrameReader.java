package com.yiyiooo.audiostream.protocol;

import java.io.IOException;
import java.io.InputStream;

/**
 * 按帧从流里读消息，负载复用同一块缓冲，音频路径上不产生垃圾。
 *
 * 不是线程安全的：一条连接一个实例，只在它自己的读线程上用。
 * 行为与 C# 端的 {@code FrameReader} 一一对应，包括那两条不显眼但很要紧的约定：
 * 比缓冲大的帧要整帧读掉再丢弃（保住流的位置），长度字段不可信时直接判流结束（别把垃圾当音频）。
 */
public final class FrameReader {

    private final InputStream stream;
    private final byte[] header = new byte[AudioFraming.HEADER_SIZE];
    private final byte[] payload;

    private FrameKind lastKind;
    private int lastCount;

    public FrameReader(InputStream stream) {
        this(stream, AudioFraming.DEFAULT_PAYLOAD_CAPACITY);
    }

    public FrameReader(InputStream stream, int payloadCapacity) {
        if (stream == null) throw new IllegalArgumentException("stream 不能为空");
        this.stream = stream;
        this.payload = new byte[payloadCapacity > 0 ? payloadCapacity : AudioFraming.DEFAULT_PAYLOAD_CAPACITY];
    }

    /** 负载缓冲。读到帧后，负载就在 buffer()[0 .. count)。 */
    public byte[] buffer() {
        return payload;
    }

    /** 上一条读到的消息类型；读帧失败时保持上一次的值。 */
    public FrameKind lastKind() {
        return lastKind;
    }

    /** 上一条读到的负载长度；读帧失败时为 0。 */
    public int lastCount() {
        return lastCount;
    }

    /**
     * 读下一条消息。
     *
     * @return false 表示连接结束或流已经不可信（这时只能断开重连）。
     * 帧比缓冲大时返回 true 但 {@link #lastCount()} 为 0——整帧已经被读掉，流的位置仍然是对的。
     */
    public boolean readNext() throws IOException {
        lastKind = null;
        lastCount = 0;

        if (!AudioFraming.readExact(stream, header, 0, AudioFraming.HEADER_SIZE)) return false;

        int length = (header[0] & 0xFF)
                | ((header[1] & 0xFF) << 8)
                | ((header[2] & 0xFF) << 16)
                | ((header[3] & 0xFF) << 24);
        int payloadLength = length - 1; // 长度含类型字节
        if (payloadLength < 0 || payloadLength > AudioFraming.MAX_PAYLOAD) {
            // 站在流的中间，长度字段已经不可信，再读下去只会把垃圾当音频
            return false;
        }
        lastKind = FrameKind.fromCode(header[4] & 0xFF);

        if (payloadLength == 0) return true; // 空负载，缓冲里没有东西

        if (payloadLength > payload.length) {
            // 缓冲装不下：分段读掉并丢弃，保证流的位置对得上，只是这一帧内容不要了
            byte[] skip = new byte[Math.min(8192, payloadLength)];
            int left = payloadLength;
            while (left > 0) {
                int want = Math.min(skip.length, left);
                int read = stream.read(skip, 0, want);
                if (read <= 0) return false;
                left -= read;
            }
            return true;
        }

        if (!AudioFraming.readExact(stream, payload, 0, payloadLength)) return false;
        lastCount = payloadLength;
        return true;
    }

    /** 把缓冲里的负载当成 UTF-8 文本取出来。 */
    public String readText() {
        if (lastCount <= 0) return "";
        return AudioFraming.fromUtf8(payload, 0, lastCount);
    }
}
