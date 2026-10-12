package com.yiyiooo.audiostream.player;

import android.media.AudioTrack;

/**
 * {@link AudioSink} 在真机上的实现：一块 {@code AudioTrack}，流模式，写多少就播多少。
 *
 * 这里刻意不做任何缓冲与判断——缓冲在 {@link RingBuffer}，水位判定在 {@link PlaybackSession}。
 * 一旦把这层做厚，同一个问题就会有两处地方可以解释，查起来先得弄清是谁干的。
 *
 * 唯一必须在这里分派的事：浮点格式不能走 {@code write(byte[], …)}。
 */
public final class AudioTrackSink implements AudioSink {

    private final AudioTrack track;
    private final int bufferSizeBytes;

    /** 这条输出是不是浮点编码。它决定 {@link #write} 走哪个重载。 */
    private final boolean floatEncoding;

    private volatile boolean released;

    /** 这条输出的音量，0..1。默认满增益，用户没调过就不动它。 */
    private volatile float volume = 1f;

    /** 浮点输出时把字节搬进 float[] 用的缓冲：复用，不在每个音频块上分配。 */
    private float[] floatScratch = new float[0];

    AudioTrackSink(AudioTrack track, int bufferSizeBytes, boolean floatEncoding) {
        this.track = track;
        this.bufferSizeBytes = bufferSizeBytes;
        this.floatEncoding = floatEncoding;
    }

    @Override
    public int write(byte[] data, int offset, int length) {
        if (released || length <= 0) return 0;
        if (!floatEncoding) {
            // WRITE_BLOCKING：写不进去就等，别丢数据——丢了就是一声爆音，而等只是让缓冲多攒一点
            return track.write(data, offset, length, AudioTrack.WRITE_BLOCKING);
        }
        return writeFloats(data, offset, length);
    }

    /**
     * 浮点输出必须走 {@code write(float[], …)}。
     *
     * 对 {@code ENCODING_PCM_FLOAT} 调 {@code write(byte[], …)} 会直接返回
     * {@code ERROR_INVALID_OPERATION}(-3)，一个采样也写不进去；而现场看起来只是
     * 「连上了、格式也对，就是没声音」。PC 端默认扬声器环回正是 32 位浮点，
     * 也就是用户最可能选的那个来源。
     *
     * 那个重载返回的是**float 个数**，这里统一换算成字节数，与其它重载一个口径。
     */
    private int writeFloats(byte[] data, int offset, int length) {
        int floats = length / 4;
        if (floats <= 0) return 0;
        if (floatScratch.length < floats) floatScratch = new float[floats];
        for (int i = 0; i < floats; i++) {
            int at = offset + i * 4;
            // 线上就是小端 float，与 AudioTrack 要的同一个字节序
            int bits = (data[at] & 0xFF)
                    | ((data[at + 1] & 0xFF) << 8)
                    | ((data[at + 2] & 0xFF) << 16)
                    | ((data[at + 3] & 0xFF) << 24);
            floatScratch[i] = Float.intBitsToFloat(bits);
        }
        int written = track.write(floatScratch, 0, floats, AudioTrack.WRITE_BLOCKING);
        return written <= 0 ? written : written * 4;
    }

    @Override
    public void play() {
        if (released) return;
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            track.play();
        }
    }

    @Override
    public void release() {
        if (released) return;
        released = true;
        try {
            if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                track.stop();
            }
        } catch (IllegalStateException ignored) {
            // 已经自己停了，没什么可做的
        }
        try {
            track.flush();
        } catch (IllegalStateException ignored) {
        }
        track.release();
    }

    @Override
    public int bufferSizeBytes() {
        return bufferSizeBytes;
    }

    /**
     * 设置本应用输出的音量。
     *
     * 用 {@code AudioTrack.setVolume}（API 21+）而不是去动 AudioManager 的媒体音量：
     * 前者只作用于这一块输出，别人的音乐、系统提示音都不受影响，退出应用后也不用恢复什么。
     * 不用 {@code setStereoVolume}：界面上只有一根滑条，左右分开调没有意义。
     *
     * 值仍然记在字段里：声卡可能还没 play 或者正在被重建，等它可用了不会自动补上，
     * 而字段在 {@link #play()} 之后依然有效（AudioTrack 的音量设置在 play 前后都保留）。
     */
    @Override
    public void setVolume(float value) {
        float clamped = value < 0f ? 0f : (value > 1f ? 1f : value);
        this.volume = clamped;
        if (released) return;
        try {
            track.setVolume(clamped);
        } catch (IllegalStateException ignored) {
            // 声卡正在被释放：这条设置丢了也无所谓，调用方重建时会再设一次
        }
    }

    /** 当前音量，给测试与界面读取。 */
    float volume() {
        return volume;
    }
}
