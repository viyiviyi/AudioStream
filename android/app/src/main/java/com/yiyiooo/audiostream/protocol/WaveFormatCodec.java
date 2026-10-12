package com.yiyiooo.audiostream.protocol;

import java.nio.charset.StandardCharsets;

/**
 * 音频格式负载的编解码，与 C# 端 TcpServer.SendWaveFormat / NetAudioRedirector.GetWaveFormatExtensible
 * **逐字节一致**。负载布局（全部小端）：
 *
 * <pre>
 * [int32 采样率][int32 位深][int32 声道数] + 其后二选一：
 *     4 字节 int32 编码            —— 扩展格式的子格式是 PCM(1) 或 IEEE 浮点(3) 时，或本来就不是扩展格式
 *     36 字节 ASCII GUID           —— 扩展格式的其它子格式，写的是 .NET Guid.ToString() 的结果
 * </pre>
 *
 * 所以实际会遇到的负载长度是 16（12+4）或 48（12+36）。
 * 读的一侧要**同时**认这两种：C# 是以「负载长度 &gt; 36 走 GUID 分支、&gt; 12 走整数分支」来分的，
 * 这里按同样的阈值分，再对边界长度做防御（16 字节以下读不出 4 个 int32）。
 */
public final class WaveFormatCodec {

    /** 12 字节：采样率、位深、声道数。 */
    public static final int FIXED_PART = 12;

    /** 36 字节：GUID 的 ASCII 形式。 */
    public static final int GUID_PART = 36;

    /** 整数编码时的负载长度。 */
    public static final int INT_ENCODING_LENGTH = FIXED_PART + 4;

    /** GUID 时的负载长度。 */
    public static final int GUID_ENCODING_LENGTH = FIXED_PART + GUID_PART;

    /**
     * 对端给了认不出的格式时，C# 端会退回「48kHz 32 位浮点双声道」。
     * 这里把这个值留着做参照，但不自动采用：静默退回去的结果是拿着一个陌生格式硬播，
     * 现象是一点声音都没有而日志里一切正常，不如把问题摆到界面上。
     */
    public static final AudioFormat FALLBACK_FLOAT_48K_STEREO =
            new AudioFormat(48000, 32, 2, AudioEncoding.IEEE_FLOAT, AudioEncoding.SUBTYPE_IEEE_FLOAT);

    private WaveFormatCodec() {
    }

    /**
     * 解析负载。认不出来返回 null（长度不够、或者数值明显不合理），由调用方决定是报错还是重试。
     *
     * @param payload 负载缓冲，长度至少为 {@code length}
     * @param length  负载实际字节数
     */
    public static AudioFormat parse(byte[] payload, int length) {
        if (payload == null || length < INT_ENCODING_LENGTH) return null;

        int sampleRate = readInt32(payload, 0);
        int bitsPerSample = readInt32(payload, 4);
        int channels = readInt32(payload, 8);

        // 数值明显不合理就不往下走：采样率 0 或声道 0 交给 AudioTrack 只会得到一句更难懂的异常
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null;

        int encoding;
        String subFormatGuid = "";
        if (length >= GUID_ENCODING_LENGTH) {
            // 与 C# 一样取前 36 字节当 GUID 文本
            String text = new String(payload, FIXED_PART, GUID_PART, StandardCharsets.US_ASCII).trim();
            subFormatGuid = AudioEncoding.canonicalGuid(text);
            String normalized = AudioEncoding.normalizeGuid(text);
            if (AudioEncoding.normalizeGuid(AudioEncoding.SUBTYPE_PCM).equals(normalized)) {
                encoding = AudioEncoding.PCM;
            } else if (AudioEncoding.normalizeGuid(AudioEncoding.SUBTYPE_IEEE_FLOAT).equals(normalized)) {
                encoding = AudioEncoding.IEEE_FLOAT;
            } else {
                // 认不出的子格式：原样留给上层，别假装它是 PCM
                encoding = AudioEncoding.UNKNOWN;
            }
        } else {
            encoding = readInt32(payload, FIXED_PART);
        }
        return new AudioFormat(sampleRate, bitsPerSample, channels, encoding, subFormatGuid);
    }

    /**
     * 编码负载，与 C# 端写出来的字节一致：子格式是 PCM/浮点时写整数编码，
     * 其它扩展子格式写 36 字符 GUID，非扩展格式写整数编码。
     */
    public static byte[] encode(AudioFormat format) {
        boolean guidBranch = format.subFormatGuid != null && !format.subFormatGuid.isEmpty()
                && format.encoding != AudioEncoding.PCM
                && format.encoding != AudioEncoding.IEEE_FLOAT;
        int length = guidBranch ? GUID_ENCODING_LENGTH : INT_ENCODING_LENGTH;
        byte[] payload = new byte[length];
        writeInt32(payload, 0, format.sampleRate);
        writeInt32(payload, 4, format.bitsPerSample);
        writeInt32(payload, 8, format.channels);
        if (guidBranch) {
            byte[] text = AudioEncoding.canonicalGuid(format.subFormatGuid).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(text, 0, payload, FIXED_PART, Math.min(text.length, GUID_PART));
        } else {
            writeInt32(payload, FIXED_PART, format.encoding);
        }
        return payload;
    }

    /** 小端读 int32。 */
    public static int readInt32(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }

    /** 小端写 int32。 */
    public static void writeInt32(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
