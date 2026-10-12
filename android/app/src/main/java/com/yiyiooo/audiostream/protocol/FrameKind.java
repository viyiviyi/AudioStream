package com.yiyiooo.audiostream.protocol;

/**
 * 一条连接上跑的消息类型。
 *
 * 之所以要有类型和长度：TCP 是字节流，没有消息边界。C# 端原先靠「一次读到的包不超过 32 字节
 * 那它就是心跳」这种长度猜测来区分控制包和音频，一旦内核把一个小包和后面的音频并在一次读里、
 * 或者把一帧音频拆成两次读，这个猜测就会吃掉真正的音频字节——音频是定长采样，少几个字节
 * 后面全部错位，听起来就是持续的杂音。数值与 C# 端 {@code FrameKind} 逐条对齐，不能改。
 */
public enum FrameKind {
    /** 采集到的 PCM 原始字节，收到就直接写进播放缓冲。 */
    AUDIO(1),

    /** UTF-8 文本命令（/Hello、/Start、/Ping 之类），负载里不带换行。 */
    TEXT(2),

    /** 音频格式描述（二进制），点单之后由被拉取侧回一条。 */
    WAVE_FORMAT(3);

    private final int code;

    FrameKind(int code) {
        this.code = code;
    }

    /** 帧头上那一个字节的数值。 */
    public int code() {
        return code;
    }

    /**
     * 认不出的类型值原样返回 null。
     * C# 端拿到未知类型是继续往下走（当头部的长度是对的，位置就不会错），
     * 这里也只是不认得而已，不算流坏了。
     */
    public static FrameKind fromCode(int code) {
        for (FrameKind kind : values()) {
            if (kind.code == code) return kind;
        }
        return null;
    }
}
