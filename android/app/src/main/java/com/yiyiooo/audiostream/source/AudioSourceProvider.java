package com.yiyiooo.audiostream.source;

/**
 * 拿麦克风那一路来源。
 *
 * 单独抽出来是为了让会话层不必知道 Android 的 AudioRecord：单元测试里换成一个生成正弦波的假源，
 * 就能在 JVM 上把「握手 → 点单 → 送音频 → 心跳」整条链路跑通，不必有设备。
 *
 * 多会话共用一个源：同一时刻有两台电脑拉同一个麦克风时，应当共享同一路采集
 * （手机同时开两个 AudioRecord 会互相打架），所以实现方要自己复用。
 */
public interface AudioSourceProvider {

    /**
     * 拿到（必要时建立）麦克风来源。已经建好的直接返回同一份。
     *
     * @throws SourceUnavailableException 没权限是 {@code denied}，打不开是 {@code unavailable}
     */
    AudioSource microphone() throws SourceUnavailableException;
}
