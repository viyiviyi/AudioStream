package com.yiyiooo.audiostream.player;

/**
 * 播放侧的几个刻度，集中在一处。
 *
 * 前三个与 PC 端网络播放同口径（目标水位 35ms、容量 120ms、起播攒到目标的一半），
 * 这样两端的听感延迟是一个量级；声卡缓冲那一条是安卓特有的：{@code AudioTrack} 自己也要有
 * 一段缓冲才不会每写一次就欠载，而它比 PC 端的 WASAPI 缓冲要粗。
 */
public final class PlaybackTuning {

    private PlaybackTuning() {
    }

    /**
     * 抖动缓冲的目标水位（毫秒）。端到端延迟主要由它决定：采集 10 + 水位 35 + 声卡若干。
     * 小于这个水位不起播，避免一开声就是一段欠载。
     */
    public static final int TARGET_BUFFER_MS = 35;

    /** 环形缓冲的容量（毫秒）。它是能扛住多大抖动的上限，不是目标水位。 */
    public static final int CAPACITY_MS = 120;

    /** 起播门槛：攒到目标水位的这个比例就开始出声。 */
    public static final double START_GATE_RATIO = 0.5;

    /**
     * 声卡缓冲（毫秒）。取 60ms：手机上这个值直接是「按下去到出声」的一部分，
     * 但比它再小就容易在系统忙时欠载，听起来是周期性的爆音。
     */
    public static final int SINK_BUFFER_MS = 60;

    /** 缓冲不足时先短等一下的时长（毫秒）。等不到才补帧，这样偶发抖动不会变成可听见的处理。 */
    public static final int SHORT_WAIT_MS = 5;
}
