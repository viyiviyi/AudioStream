package com.yiyiooo.audiostream.source;

/**
 * 这一路来源此刻给不了，同时带着「该怎么告诉对端」的协议原因。
 *
 * 之所以不直接抛 IOException：协议里的拒绝原因是分档的（{@code denied} / {@code unavailable} /
 * {@code nodevice} / {@code loop}），PC 端要靠它决定怎么提示用户，不能一律当成「连不上」。
 */
public class SourceUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    /** 协议里的拒绝原因之一。 */
    public static final String DENIED = "denied";
    public static final String UNAVAILABLE = "unavailable";

    private final String reason;

    public SourceUnavailableException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SourceUnavailableException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** 写进 {@code /Reject/<原因>} 那一段的原因。 */
    public String reason() {
        return reason;
    }
}
