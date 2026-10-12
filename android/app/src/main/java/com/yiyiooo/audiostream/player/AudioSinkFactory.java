package com.yiyiooo.audiostream.player;

import com.yiyiooo.audiostream.protocol.AudioFormat;

/** 按对端给的格式造一个输出。测试里换成假的。 */
public interface AudioSinkFactory {

    /**
     * 建一个能放这种格式的输出。
     *
     * @throws UnsupportedAudioException 这台设备放不了这种格式（位深/声道数/编码不认识）。
     */
    AudioSink create(AudioFormat format) throws UnsupportedAudioException;

    /** 放不了时带原因抛这个，界面直接显示原因，不要静默退回一个「能播但不是这个格式」的东西。 */
    class UnsupportedAudioException extends Exception {
        public UnsupportedAudioException(String message) {
            super(message);
        }
    }
}
