package com.yiyiooo.audiostream.source;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * 麦克风来源的提供者：手机端唯一的一路来源，多台电脑来拉时共用同一份。
 *
 * 复用是必须的，不是省事：同时开两个 AudioRecord 抢同一个麦克风，安卓上后开的那个
 * 要么拿不到数据、要么把先开的那个挤掉，两台电脑都会听到断续的声音。
 *
 * 权限在这里查，而不是在界面按下按钮的那一刻：真正需要录音的是「对端点单」这件事，
 * 把判定放在它旁边，才不会出现「界面上说可以了、对端却被拒」这种两处不一致。
 */
public final class MicrophoneSourceProvider implements AudioSourceProvider {

    private static final String TAG = "AudioStream";

    private final Context context;
    private final Object lock = new Object();

    private MicrophoneSource current;

    public MicrophoneSourceProvider(Context context) {
        // 存 Application 上下文：来源端跑在前台服务里，比任何 Activity 都活得久
        this.context = context.getApplicationContext();
    }

    @Override
    public AudioSource microphone() throws SourceUnavailableException {
        if (!hasRecordPermission()) {
            throw new SourceUnavailableException(SourceUnavailableException.DENIED,
                    "手机还没允许 AudioStream 录音，请在手机上打开 AudioStream 并点「允许录音」");
        }
        synchronized (lock) {
            if (current != null) return current;
            MicrophoneSource source = new MicrophoneSource();
            current = source;
            Log.d(TAG, "麦克风来源已建立：" + source.format().describe());
            return source;
        }
    }

    public boolean hasRecordPermission() {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 已经建好的那一路；还没建就是 null。给界面显示状态用。 */
    public MicrophoneSource current() {
        synchronized (lock) {
            return current;
        }
    }

    /** 关掉当前这一路。下次 {@link #microphone()} 会重新建一个。 */
    public void release() {
        MicrophoneSource source;
        synchronized (lock) {
            source = current;
            current = null;
        }
        if (source != null) source.close();
    }
}
