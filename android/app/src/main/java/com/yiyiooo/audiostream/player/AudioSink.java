package com.yiyiooo.audiostream.player;

/**
 * 「把 PCM 送到声卡」这唯一一件事的接口。
 *
 * 抽出这一层不是为了将来换实现，而是为了能测：{@link PlaybackSession} 里那些判断
 * （水位、起播门槛、欠载补帧、溢出丢帧）不该只能在真机上调，它们在 JVM 上用假 sink 就能验。
 */
public interface AudioSink {

    /**
     * 写一段 PCM。阻塞直到写完或声卡不再接受（停止时）。
     *
     * @return 实际写进去的字节数；小于 length 说明这条输出已经停了。
     */
    int write(byte[] data, int offset, int length);

    /** 开始出声。 */
    void play();

    /** 停止并释放。可以重复调用。 */
    void release();

    /** 声卡自己的缓冲有多少字节。它决定了「我们这边缓一下」的上限。 */
    int bufferSizeBytes();
}
