package com.yiyiooo.audiostream.source;

/**
 * 手机本机能给出哪些来源。
 *
 * 单独抽出来是为了让会话层不必知道 Android 的 AudioRecord 与 MediaProjection：单元测试里换成
 * 生成正弦波的假源，就能在 JVM 上把「握手 → 点单 → 送音频 → 心跳」整条链路跑通，不必有设备。
 *
 * 多会话共用一个源：同一时刻有两台电脑拉同一个来源时，应当共享同一路采集
 * （手机同时开两个 AudioRecord 会互相打架），所以实现方要自己复用。
 */
public interface AudioSourceProvider {

    /**
     * 拿到（必要时建立）麦克风那一路。已经建好的直接返回同一份。
     *
     * @throws SourceUnavailableException 没权限是 {@code denied}，打不开是 {@code unavailable}
     */
    AudioSource microphone() throws SourceUnavailableException;

    /**
     * 拿到（必要时建立）内录那一路：手机上正在播放的声音。
     *
     * 与麦克风最大的不同是它需要用户先授权（系统弹框），所以实现方在没授权时必须抛
     * {@link SourceUnavailableException#DENIED}——那是「要用户去点一下」，不是「设备坏了」，
     * PC 端的提示语完全不同。
     *
     * @throws SourceUnavailableException 没授权是 {@code denied}，设备不支持或建不起来是 {@code unavailable}
     */
    AudioSource playbackCapture() throws SourceUnavailableException;
}
