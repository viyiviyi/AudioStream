package com.yiyiooo.audiostream.player;

import android.media.AudioFormat;
import android.os.Build;

import com.yiyiooo.audiostream.protocol.AudioEncoding;

/**
 * 把对端给的音频格式，翻成 {@code AudioTrack} 要的那三个参数。
 *
 * 单独拆出来是为了能纯 JVM 测：这一段是「听不到声音」这类问题最常见的现场
 * （编码映射错了、声道掩码写反了），而它本来不需要任何设备就能验。
 */
public final class AudioTrackConfig {

    private AudioTrackConfig() {
    }

    /**
     * 位深 + 编码 → 安卓的编码常量。
     *
     * 只认三种：16 位整数、32 位浮点、以及安卓 12 起的 32 位整数。
     * 认不出就抛错——24 位、8 位、或某种我们不认识的子格式，硬按 16 位播出来是持续的噪声，
     * 那比一句「这台手机放不了这种格式」难查得多。
     */
    public static int encodingOf(com.yiyiooo.audiostream.protocol.AudioFormat format)
            throws AudioSinkFactory.UnsupportedAudioException {
        int bits = format.bitsPerSample;
        if (format.isFloat()) {
            if (bits == 32) return AudioFormat.ENCODING_PCM_FLOAT;
            throw new AudioSinkFactory.UnsupportedAudioException(
                    "对端给的是浮点音频，但只有 " + bits + " 位；这台手机只认 32 位浮点。");
        }
        // 不是浮点就必须是不压缩的 PCM：ADPCM 这类压缩格式在我们这边没有解码器，
        // 把它的字节当 PCM 播出来是持续的噪声，不如直接说放不了。
        if (format.encoding != AudioEncoding.PCM) {
            throw new AudioSinkFactory.UnsupportedAudioException(
                    "对端给的音频是 " + AudioEncoding.name(format.encoding)
                            + " 编码，本程序只传不压缩的 PCM，这台手机放不了。");
        }
        if (bits == 16) return AudioFormat.ENCODING_PCM_16BIT;
        if (bits == 32) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return AudioFormat.ENCODING_PCM_32BIT;
            throw new AudioSinkFactory.UnsupportedAudioException(
                    "对端给的是 32 位整数音频，这台手机（安卓 " + Build.VERSION.RELEASE + "）放不了；"
                            + "请在电脑那一端把来源换成 16 位或浮点的设备。");
        }
        throw new AudioSinkFactory.UnsupportedAudioException(
                "不认识的音频位深：" + bits + " 位（编码 " + AudioEncoding.name(format.encoding) + "）。");
    }

    /** 声道数 → 输出掩码。只认单声道与立体声：多声道要自己做下混，做不到就是没声音。 */
    public static int channelMaskOf(com.yiyiooo.audiostream.protocol.AudioFormat format)
            throws AudioSinkFactory.UnsupportedAudioException {
        switch (format.channels) {
            case 1:
                return AudioFormat.CHANNEL_OUT_MONO;
            case 2:
                return AudioFormat.CHANNEL_OUT_STEREO;
            default:
                throw new AudioSinkFactory.UnsupportedAudioException(
                        "对端给的是 " + format.channels + " 声道，这台手机只会放单声道与立体声。");
        }
    }

    /**
     * 声卡缓冲取多大：不小于系统给的下限，也不小于 {@link PlaybackTuning#SINK_BUFFER_MS} 对应的字节数。
     * 还必须取整到帧的整数倍，否则 {@code AudioTrack} 会直接抛参数异常。
     */
    public static int bufferBytesFor(com.yiyiooo.audiostream.protocol.AudioFormat format, int minBufferBytes) {
        int wanted = (int) (format.bytesPerSecond() * PlaybackTuning.SINK_BUFFER_MS / 1000L);
        int size = Math.max(minBufferBytes, wanted);
        int frame = format.blockAlign();
        if (frame > 0) size = ((size + frame - 1) / frame) * frame;
        return Math.max(size, frame);
    }

    /** 给人看的一句话，出错信息与界面状态都用它。 */
    public static String describe(com.yiyiooo.audiostream.protocol.AudioFormat format) {
        return format.describe();
    }

    /** 系统不给这个组合时的兜底判断（{@code getMinBufferSize} 返回负数）。 */
    public static boolean isMinBufferUsable(int minBufferBytes) {
        return minBufferBytes > 0;
    }
}
