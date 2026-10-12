package com.yiyiooo.audiostream.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;

import com.yiyiooo.audiostream.protocol.AudioEncoding;
import com.yiyiooo.audiostream.protocol.AudioFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * 麦克风来源的提供者。用 Robolectric 在 JVM 上跑，AudioRecord 是它的 shadow 实现——
 * 这里验的不是「采到的声音好不好」，而是三条在真机上出错就很难查的事：
 * 没权限时协议原因是 {@code denied}、格式报得对不对、多台电脑来拉时是不是同一份。
 *
 * 真实采集质量只能靠真机联调（任务 t-mv55g2ub9），JVM 上验不了。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MicrophoneSourceProviderTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static void grant(boolean allowed) {
        Application application = RuntimeEnvironment.getApplication();
        if (allowed) {
            Shadows.shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO);
        } else {
            Shadows.shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO);
        }
    }

    @Test
    public void 没有录音权限时报_denied_而不是_unavailable() {
        grant(false);
        MicrophoneSourceProvider provider = new MicrophoneSourceProvider(app());

        try {
            provider.microphone();
            fail("没权限时不该建出麦克风来源");
        } catch (SourceUnavailableException e) {
            // 这两个原因在协议上是分开的：denied 是「用户没放行」，unavailable 是「设备打不开」。
            // PC 端要靠它决定提示用户去授权还是去查设备，报错了用户就会去改错的地方。
            assertEquals(SourceUnavailableException.DENIED, e.reason());
            assertNotNull("原因里要有能照着做的一句话", e.getMessage());
        }
    }

    @Test
    public void 有权限时给出_48kHz_16位单声道_PCM() throws Exception {
        grant(true);
        MicrophoneSourceProvider provider = new MicrophoneSourceProvider(app());

        AudioSource source = provider.microphone();
        AudioFormat format = source.format();

        // Robolectric 的 shadow 对几个候选采样率都返回正值，所以这里走的是首选 48000
        assertEquals(48000, format.sampleRate);
        assertEquals(16, format.bitsPerSample);
        assertEquals(1, format.channels);
        assertEquals(AudioEncoding.PCM, format.encoding);
        // 手机端报给 PC 端的格式必须是 16 位整数编码：PC 端拿 4 字节整数编码的负载才认，
        // 认不出就会退回它自己的默认格式（48k/32bit/浮点），然后拿着一个和采集不一样的格式硬播
        // 一帧 = 一个采样点 × 1 个声道 = 2 字节（不是位深 16：位深是 16 位，帧宽是 16/8）
        assertEquals(2, format.blockAlign());
        assertEquals(96000, format.bytesPerSecond());
    }

    @Test
    public void 多台电脑来拉时共用同一路采集() throws Exception {
        grant(true);
        MicrophoneSourceProvider provider = new MicrophoneSourceProvider(app());

        AudioSource first = provider.microphone();
        AudioSource second = provider.microphone();

        // 同时开两个 AudioRecord 抢同一个麦克风，安卓上后开的会把先开的挤掉，
        // 两台电脑都听到断续的声音——所以这里必须是同一个实例
        assertSame(first, second);
    }

    @Test
    public void release_之后重新建一个() throws Exception {
        grant(true);
        MicrophoneSourceProvider provider = new MicrophoneSourceProvider(app());

        AudioSource first = provider.microphone();
        provider.release();
        AudioSource second = provider.microphone();

        assertNotSame("release 之后应当是新的一份，而不是把已释放的旧实例又发出去", first, second);
        assertEquals(48000, second.format().sampleRate);
    }

    @Test
    public void 订阅者走光之后采集停下_再有人来还能重新开始() throws Exception {
        grant(true);
        MicrophoneSourceProvider provider = new MicrophoneSourceProvider(app());
        AudioSource source = provider.microphone();

        final int[] got = {0};
        AudioSource.Sink sink = new AudioSource.Sink() {
            @Override
            public void onAudio(byte[] data, int offset, int length) {
                got[0]++;
            }
        };

        assertEquals(0, source.sinkCount());
        source.addSink(sink);
        assertEquals(1, source.sinkCount());
        source.addSink(sink);
        assertEquals("同一个订阅者加两次就是两个订阅者，采集不该被算成停了", 2, source.sinkCount());

        source.removeSink(sink);
        assertEquals(1, source.sinkCount());
        source.removeSink(sink);
        assertEquals(0, source.sinkCount());

        // 全部退订之后再进来一个：不该因为「刚才停过」就再也起不来
        source.addSink(sink);
        assertEquals(1, source.sinkCount());
        source.removeSink(sink);
        assertEquals(0, source.sinkCount());
    }

    @Test
    public void 没权限时不谎报有权限() {
        grant(false);
        MicrophoneSourceProvider provider = new MicrophoneSourceProvider(app());
        assertEquals(false, provider.hasRecordPermission());

        grant(true);
        assertEquals(true, provider.hasRecordPermission());
    }
}
