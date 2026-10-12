package com.yiyiooo.audiostream.source;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 手机本机来源的提供者：两路——内录（手机上正在放的声音）与麦克风。
 *
 * 每一路都只建一份、多台电脑共用。复用不是省事，是必须：同时开两个 AudioRecord 抢同一个来源，
 * 安卓上后开的那个要么拿不到数据、要么把先开的挤掉，两台电脑都会听到断续的声音。
 *
 * 失败判定放在这里而不是界面按下按钮的那一刻：真正需要采集的是「对端点单」这件事，
 * 把判定放在它旁边，才不会出现「界面上说可以了、对端却被拒」这种两处不一致。
 *
 * 两路的授权来源不同，这是最容易搞混的一点：
 * <ul>
 *   <li>麦克风走运行时权限 RECORD_AUDIO（{@code requestPermissions} 那套）。</li>
 *   <li>内录走系统弹框（{@code MediaProjection}），一次性、不能存盘、进程死了就没了。
 *       所以内录没有「永久允许」这个状态，{@link #hasCaptureGrant()} 只代表本次进程里点过。</li>
 * </ul>
 */
public final class DeviceSourceProvider implements AudioSourceProvider {

    private static final String TAG = "AudioStream";

    private final Context context;
    private final Object lock = new Object();

    private MicrophoneSource microphone;
    private CaptureSource capture;
    private CaptureGrant grant;

    /** 授权变化时通知界面刷新那一行状态。 */
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();

    public DeviceSourceProvider(Context context) {
        // 存 Application 上下文：来源端跑在前台服务里，比任何 Activity 都活得久
        this.context = context.getApplicationContext();
    }

    // ========== AudioSourceProvider ==========

    @Override
    public AudioSource microphone() throws SourceUnavailableException {
        if (!hasRecordPermission()) {
            throw new SourceUnavailableException(SourceUnavailableException.DENIED,
                    "手机还没允许 AudioStream 录音，请在手机上打开 AudioStream 并点「允许录音」");
        }
        synchronized (lock) {
            if (microphone != null) return microphone;
            MicrophoneSource source = new MicrophoneSource();
            microphone = source;
            Log.d(TAG, "麦克风来源已建立：" + source.format().describe());
            return source;
        }
    }

    @Override
    public AudioSource playbackCapture() throws SourceUnavailableException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // AudioPlaybackCapture 是 Android 10(API 29) 才有的。低版本上不是「打不开」，
            // 是这台设备根本没有这个能力，说清楚免得用户反复去点授权
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "这台手机的 Android 版本是 " + Build.VERSION.RELEASE
                            + "，内录需要 Android 10 及以上");
        }
        CaptureGrant current = grant;
        if (current == null || !current.isUsable()) {
            throw new SourceUnavailableException(SourceUnavailableException.DENIED,
                    "手机还没有允许内录，请在手机上点「允许内录」，并在系统弹框里同意");
        }
        synchronized (lock) {
            if (capture != null) return capture;
            // 换 MediaProjection 是消耗性的，由 grant 自己缓存；这里只是来取
            CaptureSource source = new CaptureSource(current.projection(context));
            capture = source;
            Log.d(TAG, "内录来源已建立：" + source.format().describe());
            return source;
        }
    }

    // ========== 授权与状态 ==========

    /** 界面上拿到的内录授权交到这里。会把上一次的内录通道交还系统。 */
    public void setGrant(CaptureGrant value) {
        CaptureSource oldCapture;
        CaptureGrant old;
        synchronized (lock) {
            oldCapture = capture;
            capture = null;
            old = grant;
            grant = value;
        }
        if (oldCapture != null) oldCapture.close();
        if (old != null) old.release();
        notifyChanged();
    }

    /** 本次进程里用户点过内录授权没有。注意它不代表「以后也一直有效」。 */
    public boolean hasCaptureGrant() {
        CaptureGrant current = grant;
        return current != null && current.isUsable();
    }

    /** 内录通道此刻是不是已经真的打开了（换出过 MediaProjection）。 */
    public boolean isCaptureOpen() {
        CaptureGrant current = grant;
        return current != null && current.isOpen();
    }

    public boolean hasRecordPermission() {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 已经建好的那一路；还没建就是 null。给界面显示状态用。 */
    public MicrophoneSource currentMicrophone() {
        synchronized (lock) {
            return microphone;
        }
    }

    public CaptureSource currentCapture() {
        synchronized (lock) {
            return capture;
        }
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception ignored) {
                // 界面自己的问题不该影响授权状态
            }
        }
    }

    /**
     * 停掉两路采集，但**保留**内录授权。
     *
     * 用户关掉来源端再打开时，不该被要求重新点一次系统弹框——那是系统级弹窗，
     * 每多弹一次都在消耗用户的耐心。授权只在服务销毁（{@link #release()}）时才交还。
     */
    public void stopSources() {
        MicrophoneSource mic;
        CaptureSource cap;
        synchronized (lock) {
            mic = microphone;
            microphone = null;
            cap = capture;
            capture = null;
        }
        if (mic != null) mic.close();
        if (cap != null) cap.close();
    }

    /** 收工：两路都关掉，内录通道也交还系统（下次要重新授权）。 */
    public void release() {
        stopSources();
        CaptureGrant current;
        synchronized (lock) {
            current = grant;
            grant = null;
        }
        if (current != null) current.release();
    }
}
