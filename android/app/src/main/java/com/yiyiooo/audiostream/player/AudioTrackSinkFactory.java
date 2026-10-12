package com.yiyiooo.audiostream.player;

import android.media.AudioAttributes;
import android.media.AudioTrack;

import com.yiyiooo.audiostream.protocol.AudioFormat;

/**
 * 真机上的输出工厂：按对端给的格式建一块 {@code AudioTrack}。
 *
 * 建不起来时**必须抛错**，不能悄悄退回一个「能出声但不是这个格式」的东西——
 * 那会让「听不到」和「听起来不对」变成同一个现象，而它们要用户做的事完全不同。
 */
public final class AudioTrackSinkFactory implements AudioSinkFactory {

    @Override
    public AudioSink create(AudioFormat format) throws UnsupportedAudioException {
        int encoding = AudioTrackConfig.encodingOf(format);
        int channelMask = AudioTrackConfig.channelMaskOf(format);
        String description = AudioTrackConfig.describe(format);

        int minBuffer;
        try {
            minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding);
        } catch (Exception e) {
            throw new UnsupportedAudioException("系统不接受这个输出组合（" + description + "）：" + e.getMessage());
        }
        if (!AudioTrackConfig.isMinBufferUsable(minBuffer)) {
            throw new UnsupportedAudioException("这台手机不支持这样的输出（" + description + "）。");
        }

        int bufferBytes = AudioTrackConfig.bufferBytesFor(format, minBuffer);
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(format.sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            throw new UnsupportedAudioException("建音频输出失败（" + description + "）：" + e.getMessage());
        }

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new UnsupportedAudioException("音频输出没能初始化（" + description + "）。");
        }
        // 浮点输出必须走另一组 write 重载，这里把「是不是浮点」带下去（见 AudioTrackSink.write）
        return new AudioTrackSink(track, bufferBytes,
                encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT);
    }
}
