package com.yiyiooo.audiostream.player;

/**
 * 播放侧的环形字节缓冲。
 *
 * 它只管「存字节、取字节」，不管什么时候该出声、欠载了怎么办——那些是
 * {@link PlaybackSession} 的事。这样拆开是因为环本身能纯粹地测（溢出、对齐、容量），
 * 而水位判定要与声卡节拍一起测。
 *
 * 两条口径：
 * <ul>
 *   <li><b>按帧对齐</b>：读写都以整数个采样帧为单位。半帧写进声卡，后面所有采样都会左右错位，
 *       听感是持续的杂音。</li>
 *   <li><b>写满丢最旧的</b>：网络一阵猛冲时宁可丢掉最老的一段，也不能整批丢新到的——
 *       丢新到的那批等于把整个缓冲连内容一起清空，听起来就是一段突兀的静音。</li>
 * </ul>
 */
public final class RingBuffer {

    private final byte[] data;
    /** 一个采样帧的字节数（声道数 × 位深 / 8）。读写都按它的整数倍进行。 */
    private final int frameSize;

    private int readIndex;
    private int size;

    private long droppedBytes;
    private long writtenBytes;
    private long readBytes;

    public RingBuffer(int capacityBytes, int frameSize) {
        if (frameSize <= 0) throw new IllegalArgumentException("frameSize 必须为正：" + frameSize);
        if (capacityBytes < frameSize) capacityBytes = frameSize;
        // 容量取整到帧的整数倍，免得尾部永远填不满、看起来像一直欠载
        this.data = new byte[(capacityBytes / frameSize) * frameSize];
        this.frameSize = frameSize;
    }

    public int frameSize() {
        return frameSize;
    }

    public int capacity() {
        return data.length;
    }

    /** 已经攒在缓冲里的字节数。 */
    public int available() {
        return size;
    }

    public int free() {
        return data.length - size;
    }

    /** 缓冲里有多少毫秒的音频。 */
    public int availableMs(int bytesPerSecond) {
        if (bytesPerSecond <= 0) return 0;
        return (int) (size * 1000L / bytesPerSecond);
    }

    /** 溢出丢掉的字节数。 */
    public long droppedBytes() {
        return droppedBytes;
    }

    public long writtenBytes() {
        return writtenBytes;
    }

    public long readBytes() {
        return readBytes;
    }

    public void clear() {
        readIndex = 0;
        size = 0;
    }

    /**
     * 写入一段音频。装不下时丢**最旧的**那些字节（丢的粒度也是整帧），
     * 并把丢掉的字节数记下来：界面上「丢了多少」比「有没有丢过」有用得多。
     */
    public void write(byte[] src, int offset, int length) {
        if (src == null || length <= 0) return;
        if (offset < 0 || offset + length > src.length) {
            throw new IndexOutOfBoundsException("越界写入：offset=" + offset + " length=" + length);
        }
        int remaining = length;
        int from = offset;

        if (remaining > data.length) {
            // 一次塞进来比整个环还多：只能留住最后能装下的那一段
            int skip = remaining - (data.length / frameSize) * frameSize;
            droppedBytes += skip;
            from += skip;
            remaining -= skip;
        }

        if (remaining > free()) {
            int need = remaining - free();
            // 丢最旧的要整帧丢
            int toDrop = ((need + frameSize - 1) / frameSize) * frameSize;
            if (toDrop > size) toDrop = (size / frameSize) * frameSize;
            discardOldest(toDrop);
            droppedBytes += toDrop;
        }

        int writeIndex = (readIndex + size) % data.length;
        int first = Math.min(remaining, data.length - writeIndex);
        System.arraycopy(src, from, data, writeIndex, first);
        if (remaining > first) {
            System.arraycopy(src, from + first, data, 0, remaining - first);
        }
        size += remaining;
        writtenBytes += length;
    }

    /**
     * 取一段音频，最多 {@code maxLength} 字节，且只取整帧。
     * 返回实际取到的字节数；不足一帧时返回 0（这时候不该往声卡写任何东西）。
     */
    public int read(byte[] dst, int offset, int maxLength) {
        int aligned = (Math.min(maxLength, size) / frameSize) * frameSize;
        if (aligned <= 0) return 0;
        int first = Math.min(aligned, data.length - readIndex);
        System.arraycopy(data, readIndex, dst, offset, first);
        if (aligned > first) {
            System.arraycopy(data, 0, dst, offset + first, aligned - first);
        }
        readIndex = (readIndex + aligned) % data.length;
        size -= aligned;
        readBytes += aligned;
        return aligned;
    }

    private void discardOldest(int bytes) {
        if (bytes <= 0) return;
        readIndex = (readIndex + bytes) % data.length;
        size -= bytes;
    }
}
