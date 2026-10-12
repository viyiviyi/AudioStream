package com.yiyiooo.audiostream.protocol;

/**
 * 一路音频的格式：采样率、位深、声道数，加上编码。
 *
 * 与 CSCore 的 WaveFormat / WaveFormatExtensible 对应，但只保留 Android 端真的用得着的字段：
 * 采样率、位深、声道数、编码（整数），以及 extensible 时那个子格式 GUID。
 * BlockAlign 与 BytesPerSecond 都由这几个值算出来，不单独存——两端各存一份则会不一致。
 */
public final class AudioFormat {

    /** 采样率，Hz。 */
    public final int sampleRate;

    /** 每个采样的位数，例如 16、32。 */
    public final int bitsPerSample;

    /** 声道数。 */
    public final int channels;

    /** {@link AudioEncoding} 里的整数编码。 */
    public final int encoding;

    /**
     * extensible 格式的子格式 GUID；不是 extensible 或者编码是 PCM/浮点时为空串。
     * C# 端只在「既不是 PCM 也不是浮点」时才把 GUID 写进负载，所以这个字段平时都是空的。
     */
    public final String subFormatGuid;

    public AudioFormat(int sampleRate, int bitsPerSample, int channels, int encoding) {
        this(sampleRate, bitsPerSample, channels, encoding, "");
    }

    public AudioFormat(int sampleRate, int bitsPerSample, int channels, int encoding, String subFormatGuid) {
        this.sampleRate = sampleRate;
        this.bitsPerSample = bitsPerSample;
        this.channels = channels;
        this.encoding = encoding;
        this.subFormatGuid = subFormatGuid == null ? "" : subFormatGuid;
    }

    /** 每个采样帧的字节数（左右声道各 2 字节就是 4）。 */
    public int blockAlign() {
        return Math.max(1, bitsPerSample / 8) * Math.max(1, channels);
    }

    /** 每秒字节数，用于算缓冲容量。 */
    public int bytesPerSecond() {
        return blockAlign() * sampleRate;
    }

    public boolean isFloat() {
        if (encoding == AudioEncoding.IEEE_FLOAT) return true;
        // 两边都要归一化再比：C# 写的是带横线的 Guid.ToString()，而常量里存的是不带横线的形式，
        // 直接比字符串会永远不相等——那会让「子格式是浮点」的格式被当成未知编码。
        return AudioEncoding.normalizeGuid(AudioEncoding.SUBTYPE_IEEE_FLOAT)
                .equals(AudioEncoding.normalizeGuid(subFormatGuid));
    }

    /**
     * 这个格式是不是「任何一台安卓都放得出来」的那两类：16 位不压缩整数、32 位浮点。
     *
     * 32 位整数 PCM 不在这里判：它要安卓 12 以上才收，是**与机型有关**的判断，
     * 所以那条留给 {@code AudioTrackConfig.encodingOf} 去按 API 级别决定。
     * 压缩编码（ADPCM 之类）没有解码器，一律不算可播。
     */
    public boolean isPlayable() {
        if (isFloat()) return bitsPerSample == 32;
        return encoding == AudioEncoding.PCM && bitsPerSample == 16;
    }

    /** 界面上说明用的一句话。 */
    public String describe() {
        return sampleRate + "Hz " + bitsPerSample + "位 " + (channels == 1 ? "单声道" : channels + "声道")
                + " " + AudioEncoding.name(encoding);
    }

    @Override
    public String toString() {
        return "AudioFormat{" + describe() + (subFormatGuid.isEmpty() ? "" : " " + subFormatGuid) + "}";
    }
}
