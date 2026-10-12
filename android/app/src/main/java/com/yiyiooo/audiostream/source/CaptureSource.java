package com.yiyiooo.audiostream.source;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.util.Log;

import com.yiyiooo.audiostream.protocol.AudioEncoding;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 手机的内录：把手机上正在播放的声音采下来。
 *
 * 用的不是麦克风那套（{@code AudioRecord.Builder().setAudioSource(...)}），而是
 * {@link AudioPlaybackCaptureConfiguration}：它录的是**系统混音里被允许捕获的那部分**，
 * 而不是听筒/麦克风听到的环境音。
 *
 * 三个由此而来的、与麦克风不同的性质，都必须清楚：
 * <ul>
 *   <li><b>采不到不代表坏了。</b>Android 只允许捕获 usage 是媒体/游戏的音频，且要应用自己没声明
 *       禁止被捕获。系统提示音、闹钟、通话、以及声明了 {@code ALLOW_CAPTURE_BY_NONE} 的应用
 *       （不少国产播放器与所有金融类应用）都不在里面。所以「内录是静音」很可能是对方真的没在放
 *       允许被捕获的东西，不是本程序的问题。</li>
 *   <li><b>只能 16 位整数，且不需要录音权限。</b>API 29 起内录只支持 ENCODING_PCM_16BIT；
 *       换来的授权凭据本身就已经覆盖了采集权，所以这条路上不查 RECORD_AUDIO。</li>
 *   <li><b>格式由我们定，播放端跟着走。</b>内录没有「设备原生格式」这回事，采样率与声道数
 *       由这里给，对端按这个格式播。</li>
 * </ul>
 *
 * 与 {@link MicrophoneSource} 一样，AudioRecord 在构造时就建好并检查 state：
 * 点单那一刻就要把格式回给对端，那时建不起来还来得及拒绝。
 */
public final class CaptureSource implements AudioSource {

    private static final String TAG = "AudioStream";

    /** 内录的采样率候选。与麦克风同率，对端的重采样最少。 */
    private static final int[] CANDIDATE_RATES = {48000, 44100};

    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BITS = 16;

    /** 优先立体声：内录的内容（音乐、视频）绝大多数是立体声，塌成单声道会明显损失。 */
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO;
    private static final int CHANNELS = 2;

    private static final int CHUNK_MS = 20;

    private final com.yiyiooo.audiostream.protocol.AudioFormat format;
    private final int chunkBytes;
    private final AudioRecord record;

    private final List<Sink> sinks = new CopyOnWriteArrayList<Sink>();
    private final Object lock = new Object();

    private volatile boolean capturing;
    private Thread worker;
    private volatile String failure;

    /**
     * @param projection 已经换出来的内录通道（来自 {@link CaptureGrant#projection}）。
     *                   <b>不</b>由本类负责 stop——谁建的谁关，见 {@code DeviceSourceProvider}。
     */
    public CaptureSource(MediaProjection projection) throws SourceUnavailableException {
        int rate = pickSampleRate();
        if (rate <= 0) {
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "这台设备的内录既不支持 48000Hz 也不支持 44100Hz 的立体声");
        }
        this.format = new com.yiyiooo.audiostream.protocol.AudioFormat(
                rate, BITS, CHANNELS, AudioEncoding.PCM);
        this.chunkBytes = rate * (BITS / 8) * CHANNELS * CHUNK_MS / 1000;
        this.record = open(projection, rate);
    }

    private static int pickSampleRate() {
        for (int rate : CANDIDATE_RATES) {
            if (AudioRecord.getMinBufferSize(rate, CHANNEL_MASK, ENCODING) > 0) return rate;
        }
        return 0;
    }

    private static AudioRecord open(MediaProjection projection, int rate) throws SourceUnavailableException {
        int minBuffer = AudioRecord.getMinBufferSize(rate, CHANNEL_MASK, ENCODING);
        if (minBuffer <= 0) {
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "这台设备的内录不支持 " + rate + "Hz 立体声");
        }
        // 与麦克风同样的理由：系统给的最小值是「刚好不溢出」，采集线程被调度延迟就丢样本，
        // 听感是偶尔的咔哒声。给到 200ms 换稳定。
        int bufferBytes = Math.max(minBuffer, rate * (BITS / 8) * CHANNELS / 5);

        AudioRecord record;
        try {
            AudioPlaybackCaptureConfiguration capture =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            // 只匹配「媒体」与「游戏」：这是 Android 允许捕获、且用户真正想流转的两类。
                            // USAGE_UNKNOWN 也加上——不少播放器没设 usage，不加就一点声音都采不到。
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            record = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(rate)
                            .setChannelMask(CHANNEL_MASK)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(capture)
                    .build();
        } catch (Exception e) {
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "打不开内录：" + reason(e), e);
        }

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "内录初始化失败，可能系统刚把授权收回去了，请在手机上重新点一次「允许内录」");
        }
        return record;
    }

    // ========== AudioSource ==========

    @Override
    public com.yiyiooo.audiostream.protocol.AudioFormat format() {
        return format;
    }

    @Override
    public String describe() {
        return "手机内录（" + format.describe() + "）";
    }

    @Override
    public void addSink(Sink sink) {
        if (sink == null) return;
        sinks.add(sink);
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
            worker = new Thread(this::captureLoop, "capture-loop");
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
        stopRecordingQuietly();
        if (thread != null) thread.interrupt();
    }

    private void captureLoop() {
        byte[] chunk = new byte[chunkBytes];
        try {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                failure = "内录没能开始，请在手机上重新点一次「允许内录」";
                Log.w(TAG, failure);
                return;
            }
            Log.d(TAG, "内录开始采集：" + format.describe() + "，每次 " + chunkBytes + " 字节");

            while (capturing) {
                int read = record.read(chunk, 0, chunk.length);
                if (read <= 0) {
                    if (read < 0) {
                        failure = "内录采集出错（错误码 " + read + "）";
                        Log.w(TAG, failure);
                        break;
                    }
                    continue;
                }
                for (Sink sink : sinks) {
                    try {
                        sink.onAudio(chunk, 0, read);
                    } catch (Exception e) {
                        Log.w(TAG, "订阅者处理音频出错", e);
                    }
                }
            }
        } catch (IllegalStateException e) {
            Log.d(TAG, "内录采集结束：" + reason(e));
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
