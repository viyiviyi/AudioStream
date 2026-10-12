package com.yiyiooo.audiostream.pull;

/**
 * 一条拉取此刻处在哪一步。
 *
 * 界面上要能回答的问题只有一个：「为什么没声音」。所以失败不是一个笼统的「连接失败」，
 * 而是分段的——连不上、握手被拒、点单被拒、格式看不懂、播着播着断了，用户能做的事完全不同。
 */
public enum PullState {

    /** 还没开始。 */
    IDLE("未连接"),

    /** 正在按端口区间逐个试。 */
    CONNECTING("正在连接"),

    /** 连上了，正在报身份、核对对端。 */
    HANDSHAKING("正在握手"),

    /** 正在点单并等对端给音频格式。 */
    NEGOTIATING("正在协商音频格式"),

    /** 音频正在往这边来。 */
    PLAYING("正在播放"),

    /** 自己停的（用户点了断开）。 */
    STOPPED("已停止"),

    /** 出错停的，原因在 detail 里。 */
    FAILED("连接失败");

    private final String label;

    PullState(String label) {
        this.label = label;
    }

    /** 中文短标签。 */
    public String label() {
        return label;
    }

    /** 是不是已经结束了（不会再自己往前走）。 */
    public boolean isTerminal() {
        return this == STOPPED || this == FAILED;
    }
}
