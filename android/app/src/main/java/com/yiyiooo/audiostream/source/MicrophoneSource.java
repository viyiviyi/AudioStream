package com.yiyiooo.audiostream.source;

import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.yiyiooo.audiostream.protocol.AudioEncoding;
import com.yiyiooo.audiostream.protocol.AudioFormat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 手机的麦克风。
 *
 * 采集参数刻意用 **16 位整数 PCM**，不用浮点：手机麦克风的原生格式就是 16 位整数，
 * 走浮点要在驱动层多做一次转换，而 PC 端拿到什么格式就播什么格式，这里没有理由多绕一圈。
 * 采样率优先 48000（与 PC 端默认扬声器环回同率，跨设备时对端的重采样最少），
 * 不支持就退 44100——少数手机的麦克风只认这一个。
 *
 * AudioRecord 在**构造时**就建好并检查 state，而不是等到真的开录：
 * 点单（/WaveFormat）那一刻就要把格式回给对端，那时候建不起来还来得及回 /Reject/unavailable；
 * 等 /Start 之后才发现建不起来，对端只会看到「连上了、格式也协商了，就是没声音」。
 */
public final class MicrophoneSource implements AudioSource {

    private static final String TAG = "AudioStream";

    /** 先试 48000，不行退 44100。其它采样率在手机上不值得再试。 */
    private static final int[] CANDIDATE_RATES = {48000, 44100};

    private static final int CHANNEL_MASK = android.media.AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = android.media.AudioFormat.ENCODING_PCM_16BIT;
    private static final int BITS = 16;
    private static final int CHANNELS = 1;

    /**
     * 每次读 20ms。与 PC 端一帧的节拍一致（那边按 20ms 从缓冲取一次），
     * 也让采集线程每 20ms 才醒一次，不必空转。
     */
    private static final int CHUNK_MS = 20;

    private final AudioFormat format;
    private final int chunkBytes;
    private final AudioRecord record;

    /** 订阅者。读线程每帧遍历一次，用写时复制避免在音频路径上加锁。 */
    private final List<Sink> sinks = new CopyOnWriteArrayList<Sink>();

    private final Object lock = new Object();

    private volatile boolean capturing;
    private Thread worker;
    private volatile String failure;

    public MicrophoneSource() throws SourceUnavailableException {
        int rate = pickSampleRate();
        if (rate <= 0) {
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "这台手机的麦克风既不支持 48000Hz 也不支持 44100Hz 的单声道采集");
        }
        this.format = new AudioFormat(rate, BITS, CHANNELS, AudioEncoding.PCM);
        this.chunkBytes = rate * (BITS / 8) * CHANNELS * CHUNK_MS / 1000;
        this.record = open(rate);
    }

    /** 返回 0 表示两个候选采样率都不支持。 */
    private static int pickSampleRate() {
        for (int rate : CANDIDATE_RATES) {
            if (AudioRecord.getMinBufferSize(rate, CHANNEL_MASK, ENCODING) > 0) return rate;
        }
        return 0;
    }

    private static AudioRecord open(int rate) throws SourceUnavailableException {
        int minBuffer = AudioRecord.getMinBufferSize(rate, CHANNEL_MASK, ENCODING);
        if (minBuffer <= 0) {
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "这台手机的麦克风不支持 " + rate + "Hz 采集");
        }
        // 系统给的最小值是「刚好不溢出」的下限，通常只有几十毫秒。采集线程偶发被调度延迟
        // （手机在忙着别的）就会丢样本，听感是偶尔的咔哒声。给到 200ms 换稳定，代价只是这点内存。
        int bufferBytes = Math.max(minBuffer, rate * (BITS / 8) * CHANNELS / 5);

        AudioRecord record;
        try {
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(rate)
                            .setChannelMask(CHANNEL_MASK)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
        } catch (Exception e) {
            // 参数不被这台设备接受时 Builder 会抛（老设备上还有 UnsupportedOperationException）
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "打不开手机麦克风：" + reason(e), e);
        }

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "手机麦克风初始化失败，可能被别的应用占着");
        }
        return record;
    }

    // ========== AudioSource ==========

    @Override
    public AudioFormat format() {
        return format;
    }

    @Override
    public String describe() {
        return "手机麦克风（" + format.describe() + "）";
    }

    @Override
    public void addSink(Sink sink) {
        if (sink == null) return;
        sinks.add(sink);
        // 第一个订阅者才真的开麦克风：没人听的时候开着，状态栏的录音标志会一直亮着
        if (sinks.size() == 1) startCapture();
    }

    @Override
    public void removeSink(Sink sink) {
        if (sink == null) return;
        sinks.remove(sink);
        if (sinks.isEmpty()) stopCapture();
    }

    @Override
    public int sinkCount() {
        return sinks.size();
    }

    /** 采集线程最近一次失败的原因；正常时为 null。 */
    public String failure() {
        return failure;
    }

    @Override
    public void close() {
        sinks.clear();
        stopCapture();
        try {
            record.release();
        } catch (Exception ignored) {
            // 释放失败没什么可做的
        }
    }

    // ========== 采集 ==========

    private void startCapture() {
        synchronized (lock) {
            if (capturing) return;
            capturing = true;
            failure = null;
            worker = new Thread(this::captureLoop, "mic-capture");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void stopCapture() {
        Thread thread;
        synchronized (lock) {
            if (!capturing) return;
            capturing = false;
            thread = worker;
            worker = null;
        }
        // stop() 会让阻塞在 read 上的采集线程返回，随后它自己退出；
        // 没在录的时候调 stop 会抛 IllegalStateException，那是正常情况
        stopRecordingQuietly();
        if (thread != null) thread.interrupt();
    }

    private void captureLoop() {
        byte[] chunk = new byte[chunkBytes];
        try {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                failure = "麦克风没能开始录音，可能被别的应用占着";
                Log.w(TAG, failure);
                return;
            }
            Log.d(TAG, "麦克风开始采集：" + format.describe() + "，每次 " + chunkBytes + " 字节");

            while (capturing) {
                int read = record.read(chunk, 0, chunk.length);
                if (read <= 0) {
                    if (read < 0) {
                        // 负数是 AudioRecord 的错误码，再读下去也是一样的结果
                        failure = "麦克风采集出错（错误码 " + read + "）";
                        Log.w(TAG, failure);
                        break;
                    }
                    continue;
                }
                for (Sink sink : sinks) {
                    try {
                        sink.onAudio(chunk, 0, read);
                    } catch (Exception e) {
                        // 一个订阅者出问题（比如 socket 写失败）不该让整路采集停掉：
                        // 别的电脑可能还在这路上听着
                        Log.w(TAG, "订阅者处理音频出错", e);
                    }
                }
            }
        } catch (IllegalStateException e) {
            // stop/release 与正在进行的 read 撞上，属于正常收尾
            Log.d(TAG, "麦克风采集结束：" + reason(e));
        } finally {
            stopRecordingQuietly();
        }
    }

    private void stopRecordingQuietly() {
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
            // 没在录
        }
    }

    private static String reason(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
