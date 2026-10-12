package com.yiyiooo.audiostream.player;

import android.util.Log;

import com.yiyiooo.audiostream.pull.PullSession;
import com.yiyiooo.audiostream.pull.PullState;
import com.yiyiooo.audiostream.protocol.AudioFormat;

import java.io.Closeable;

/**
 * 把一条拉取会话收到的音频接到声卡上。
 *
 * 它同时是 {@link PullSession.Listener}：网络那边每收到一段 PCM 就丢进环形缓冲（不阻塞），
 * 另起一条写线程按声卡的节拍从缓冲里取。两边分开是必须的——网络一抖就跑去等声卡，
 * 等回来时 TCP 接收窗口已经满了，丢的是整包。
 *
 * 三条判定与 PC 端同口径：攒到目标水位的一半才起播、缓冲不够先短等一下再补、补的时候
 * **重复上一帧**而不是补静音（静音在听感上是一段突兀的空白，重复上一帧几乎听不出来）。
 */
public final class PlaybackSession implements PullSession.Listener, Closeable {

    private static final String TAG = "AudioStream";

    /** 上层要看的：状态变化、以及「这种格式这台机器放不了」这种硬失败。 */
    public interface Listener {

        void onState(PullState state, String detail);

        /** 音频输出建不起来（格式放不了）。收到它就该把整条拉取停掉，而不是继续收数据往黑洞里写。 */
        void onPlaybackFailed(String reason);
    }

    private final AudioSinkFactory sinkFactory;
    private final Listener listener;

    private volatile AudioFormat format;
    private volatile AudioSink sink;
    private volatile RingBuffer buffer;
    private volatile boolean running;
    private volatile boolean started;
    private Thread writer;

    /** 输出音量，0..1。存在这一层而不是只写给 sink：重连会换一块声卡，新声卡不认识旧的那块。 */
    private volatile float volume = 1f;

    private volatile String lastError;
    private volatile long underruns;
    private volatile long repeatedBytes;
    private volatile long writtenBytes;

    public PlaybackSession(AudioSinkFactory sinkFactory, Listener listener) {
        this.sinkFactory = sinkFactory;
        this.listener = listener;
    }

    // ---------- PullSession.Listener ----------

    @Override
    public void onState(PullState state, String detail) {
        if (state == PullState.PLAYING) {
            startWriter();
        }
        listener.onState(state, detail);
    }

    @Override
    public void onFormat(AudioFormat value) {
        // 重连会再来一次：先把上一块声卡与缓冲收干净，否则每次重连都漏一个 AudioTrack
        releaseSink();
        this.format = value;
        int capacity = (int) (value.bytesPerSecond() * PlaybackTuning.CAPACITY_MS / 1000L);
        this.buffer = new RingBuffer(capacity, value.blockAlign());
        try {
            this.sink = sinkFactory.create(value);
            // 重连会重建声卡：不在这里补一次，用户调过的音量会悄悄退回默认，
            // 而他只会觉得「断了重连之后声音突然变大了」
            this.sink.setVolume(volume);
        } catch (AudioSinkFactory.UnsupportedAudioException e) {
            // 自己判定的「这种格式这台机器放不了」：消息本来就是写给用户看的，原样报上去
            lastError = e.getMessage();
            Log.w(TAG, lastError);
            listener.onPlaybackFailed(lastError);
        } catch (Throwable t) {
            // 建声卡可能抛出的不只是我们自己定义的那一种：厂商 ROM 上的 AudioTrack 会抛
            // 各种没见过的东西。也兜住并报出去——逃出去的话异常会死在协议层的 catch 里，
            // 现场就只剩「格式明明协商对了，就是没声音」，无从查起。
            String reason = "建音频输出失败（" + value.describe() + "）：" + t;
            lastError = reason;
            Log.e(TAG, reason, t);
            listener.onPlaybackFailed(reason);
        }
    }

    @Override
    public void onAudio(byte[] data, int offset, int length) {
        RingBuffer ring = buffer;
        if (ring == null) return;
        // 只入队，绝不在这里等声卡
        ring.write(data, offset, length);
    }

    // ---------- 写线程 ----------

    private synchronized void startWriter() {
        if (running) return;
        if (sink == null || buffer == null) return;
        running = true;
        started = false;
        writer = new Thread(this::pump, "playback-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private void pump() {
        AudioSink out = sink;
        RingBuffer ring = buffer;
        AudioFormat fmt = format;
        if (out == null || ring == null || fmt == null) {
            Log.w(TAG, "写线程没起来：声卡=" + (out == null ? "无" : "有")
                    + " 缓冲=" + (ring == null ? "无" : "有")
                    + " 格式=" + (fmt == null ? "无" : "有"));
            return;
        }

        int frameSize = fmt.blockAlign();
        // 每次取 20ms，取整到帧。太碎会让 write 调用过密，太整又会把缓冲抽干
        int chunk = Math.max(frameSize, (int) (fmt.bytesPerSecond() * 20L / 1000L));
        chunk = (chunk / frameSize) * frameSize;
        byte[] block = new byte[chunk];
        byte[] lastFrame = new byte[frameSize];
        boolean hasLastFrame = false;

        while (running) {
            if (!started) {
                // 起播门槛：攒到目标水位的一半再开声，避免一开声就是一段欠载
                if (ring.availableMs(fmt.bytesPerSecond()) < PlaybackTuning.TARGET_BUFFER_MS * PlaybackTuning.START_GATE_RATIO) {
                    sleep(PlaybackTuning.SHORT_WAIT_MS);
                    continue;
                }
                out.play();
                started = true;
                Log.i(TAG, "攒够 " + ring.availableMs(fmt.bytesPerSecond()) + "ms，开始出声");
            }

            int want = Math.min(block.length, ring.capacity());
            want = (want / frameSize) * frameSize;
            if (want <= 0) {
                sleep(PlaybackTuning.SHORT_WAIT_MS);
                continue;
            }

            int got = 0;
            boolean waited = false;
            while (got < want && running) {
                int n = ring.read(block, got, want - got);
                if (n <= 0) {
                    if (waited) break; // 短等过了还是没有，别在这里卡死
                    sleep(PlaybackTuning.SHORT_WAIT_MS);
                    waited = true;
                    continue;
                }
                // 记住这一段尾巴上的最后一帧，欠载时用它顶上
                if (n >= frameSize) {
                    System.arraycopy(block, got + n - frameSize, lastFrame, 0, frameSize);
                    hasLastFrame = true;
                }
                got += n;
            }

            if (got < want) {
                if (!hasLastFrame) {
                    // 一段音频都还没收到过，没有可重复的东西，只能接着等
                    sleep(PlaybackTuning.SHORT_WAIT_MS);
                    continue;
                }
                // 欠载：把剩下的用上一帧填满。补静音在听感上是一段空白，重复上一帧几乎听不出来；
                // 而什么都不写会让声卡自己的缓冲被抽干，那是一次硬件级的爆音，比重复难听得多。
                for (int at = got; at < want; at += frameSize) {
                    System.arraycopy(lastFrame, 0, block, at, frameSize);
                }
                repeatedBytes += want - got;
                underruns++;
                got = want;
            }

            int written = out.write(block, 0, got);
            if (writtenBytes == 0) {
                // 第一次写声卡的结果单独记一条：写不进去时返回值往往是 0 或负数，
                // 紧接着循环就会退出，不留痕迹的话现场只剩「就是没声音」。
                Log.i(TAG, "第一次写声卡：要写 " + got + " 字节，实际写入 " + written);
            }
            writtenBytes += Math.max(0, written);
            if (written < got) {
                // 声卡不收了（正在关，或者写失败），退出
                Log.w(TAG, "声卡只收下 " + written + "/" + got + " 字节，写线程退出");
                break;
            }
        }
        Log.i(TAG, "写线程结束，累计写入 " + writtenBytes + " 字节");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 状态与计数（给界面看） ----------

    /**
     * 设置输出音量（0..1）。
     *
     * 值存在这一层而不是只写给 sink：重连会换一块新的声卡，新声卡不认识旧的那块，
     * 重建时由 {@link #onFormat} 把它补上去。
     */
    public void setVolume(float value) {
        float clamped = value < 0f ? 0f : (value > 1f ? 1f : value);
        this.volume = clamped;
        AudioSink out = sink;
        if (out != null) {
            try {
                out.setVolume(clamped);
            } catch (Exception ignored) {
                // 声卡正在关：这条设置不生效也无妨，下一次重建时会带上
            }
        }
    }

    /** 当前输出音量，0..1。 */
    public float volume() {
        return volume;
    }

    /** 当前音频格式，还没协商出来时为 null。 */
    public AudioFormat format() {
        return format;
    }

    /** 此刻缓冲里有多少毫秒的音频。太小说明快欠载了，太大说明网络在猛冲。 */
    public int bufferedMs() {
        RingBuffer ring = buffer;
        AudioFormat fmt = format;
        if (ring == null || fmt == null) return 0;
        return ring.availableMs(fmt.bytesPerSecond());
    }

    /** 欠载补帧的次数。它不为零不代表坏了，一直涨才是。 */
    public long underruns() {
        return underruns;
    }

    /** 因为缓冲满而被丢掉的字节数（丢的是最旧的）。 */
    public long droppedBytes() {
        RingBuffer ring = buffer;
        return ring == null ? 0 : ring.droppedBytes();
    }

    public long writtenBytes() {
        return writtenBytes;
    }

    public boolean isPlaying() {
        return running && started;
    }

    /** 硬失败原因（格式放不了），没有则为 null。 */
    public String lastError() {
        return lastError;
    }

    /** 给同包的测试看「到底建起来没有」。 */
    AudioSink sink() {
        return sink;
    }

    @Override
    public void close() {
        running = false;
        Thread thread = writer;
        if (thread != null) thread.interrupt();
        releaseSink();
    }

    private synchronized void releaseSink() {
        AudioSink out = sink;
        sink = null;
        if (out != null) {
            try {
                out.release();
            } catch (Exception ignored) {
                // 关声卡出错没什么可做的
            }
        }
    }
}
