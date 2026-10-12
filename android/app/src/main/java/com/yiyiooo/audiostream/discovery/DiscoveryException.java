package com.yiyiooo.audiostream.discovery;

/**
 * 发现对端失败。message 一律是能直接显示给用户的中文，
 * 因为这一类失败最常见的现场是「IP 填错了」，而「Connection refused」这种话帮不上忙。
 */
public class DiscoveryException extends Exception {

    public DiscoveryException(String message) {
        super(message);
    }

    public DiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
