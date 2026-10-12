package com.yiyiooo.audiostream;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.yiyiooo.audiostream.player.AudioTrackSinkFactory;
import com.yiyiooo.audiostream.player.PlaybackSession;
import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.pull.PullSession;
import com.yiyiooo.audiostream.pull.PullState;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 真机联调：从一台真实运行的 PC 端拉音频，并把它送进本机声卡。
 *
 * 为什么单独有这一份：播放端算不算成功，只有「真的连上对端、真的收到音频、真的写进声卡」才算数。
 * JVM 上的单元测试用的是假对端，证明不了这件事；而走界面要求设备能正常画窗口——图形栈不好的
 * 设备（模拟器尤其）上界面可能根本起不来，联调就卡在点不动按钮上，与音频链路本身无关。
 * 这里不起任何 Activity，没有窗口，也就不受绘制影响。
 *
 * 参数（am instrument -e 传，缺省值对应本机联调那台 PC）：
 *   host     对端 IP，默认 192.168.56.1
 *   seconds  观察时长，默认 12
 *
 * 跑法：
 *   gradle :app:connectedDebugAndroidTest
 * 指定对端：
 *   gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.host=192.168.1.10
 */
@RunWith(AndroidJUnit4.class)
public class PullPlaybackIntegrationTest {

    private static final String TAG = "AudioStream.E2E";

    /** PC 端支持的跟随项：拉取方点它，对端解析成「它此刻的系统默认输出设备」（扬声器环回）。 */
    private static final String DEFAULT_OUTPUT_ID = "default";
    private static final String DEFAULT_OUTPUT_NAME = "系统默认输出设备";

    @Test
    public void pullDefaultOutputAndWriteToSoundCard() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("host") == null ? "192.168.56.1" : args.getString("host");
        int seconds = args.getString("seconds") == null
                ? 12 : Integer.parseInt(args.getString("seconds"));
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Log.i(TAG, "对端 " + host + "，观察 " + seconds + " 秒");

        final CountDownLatch failedEarly = new CountDownLatch(1);
        final AtomicReference<PlaybackSession> playbackRef = new AtomicReference<PlaybackSession>();
        final AtomicReference<PullSession> sessionRef = new AtomicReference<PullSession>();
        final AtomicReference<String> failure = new AtomicReference<String>();

        // 会话本身有自己的线程；这里再起一条只是为了让 AudioTrack/网络的建立过程与
        // 观察窗口分开，失败能立刻叫醒等待方，而不是干等满整个窗口。
        Thread starter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PlaybackSession playback = new PlaybackSession(
                            new AudioTrackSinkFactory(), new PlaybackSession.Listener() {
                        @Override
                        public void onState(PullState state, String detail) {
                            Log.i(TAG, "状态 " + state + "：" + detail);
                            if (state == PullState.FAILED) {
                                failure.set(detail);
                                failedEarly.countDown();
                            }
                        }

                        @Override
                        public void onPlaybackFailed(String reason) {
                            Log.e(TAG, "播放建不起来：" + reason);
                            failure.set(reason);
                            failedEarly.countDown();
                        }
                    });
                    playbackRef.set(playback);

                    MachineIdentity identity = MachineIdentity.load(context);
                    PullSession session = new PullSession(host, 0,
                            DEFAULT_OUTPUT_ID, DEFAULT_OUTPUT_NAME,
                            identity.id(), identity.name(), playback);
                    sessionRef.set(session);
                    session.start();
                } catch (Throwable t) {
                    Log.e(TAG, "起会话就失败了", t);
                    failure.set(String.valueOf(t));
                    failedEarly.countDown();
                }
            }
        }, "e2e-pull");
        starter.setDaemon(true);
        starter.start();

        failedEarly.await(seconds, TimeUnit.SECONDS);

        PlaybackSession playback = playbackRef.get();
        if (playback == null) {
            fail("会话没建起来：" + failure.get());
            return;
        }

        AudioFormat format = playback.format();
        Log.i(TAG, "结果：格式=" + (format == null ? "（没协商出来）" : format.describe())
                + "　写进声卡=" + playback.writtenBytes() + " 字节"
                + "　欠载=" + playback.underruns()
                + "　丢弃=" + playback.droppedBytes()
                + "　缓冲=" + playback.bufferedMs() + "ms");

        // 先收工，别把连接和声卡留在那儿
        PullSession session = sessionRef.get();
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        playback.close();

        if (failure.get() != null) {
            fail("对端或播放失败：" + failure.get());
        }
        assertNotNull("没有协商出音频格式，说明点单没成功", format);
        assertTrue("一个字节都没写进声卡", playback.writtenBytes() > 0);
    }

    /**
     * 收到的到底是不是「有内容的音频」。
     *
     * 上一条用例只证明「有字节流过来并写进了声卡」，这一条绕过声卡直接看负载：PC 那一端此刻
     * 正在放声音时，环回采到的样本不该全是 0。它区分的是「声音真的传过来了」与「传过来一片静音」。
     *
     * 前提是 PC 那边真有声音在放，否则环回本来就是静音——那种情况是环境不满足，不是程序坏了。
     */
    @Test
    public void pulledAudioCarriesRealSignal() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("host") == null ? "192.168.56.1" : args.getString("host");
        int seconds = args.getString("seconds") == null
                ? 8 : Integer.parseInt(args.getString("seconds"));
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        final AtomicLong received = new AtomicLong();
        final AtomicLong nonZero = new AtomicLong();
        final AtomicReference<PullSession> sessionRef = new AtomicReference<PullSession>();
        final AtomicReference<String> failure = new AtomicReference<String>();

        Thread starter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    MachineIdentity identity = MachineIdentity.load(context);
                    PullSession session = new PullSession(host, 0,
                            DEFAULT_OUTPUT_ID, DEFAULT_OUTPUT_NAME,
                            identity.id(), identity.name(), new PullSession.Listener() {
                        @Override
                        public void onState(PullState state, String detail) {
                            Log.i(TAG, "状态 " + state + "：" + detail);
                            if (state == PullState.FAILED) failure.set(detail);
                        }

                        @Override
                        public void onFormat(AudioFormat format) {
                            Log.i(TAG, "格式 " + format.describe());
                        }

                        @Override
                        public void onAudio(byte[] data, int offset, int length) {
                            received.addAndGet(length);
                            for (int i = offset; i < offset + length; i++) {
                                if (data[i] != 0) nonZero.incrementAndGet();
                            }
                        }
                    });
                    sessionRef.set(session);
                    session.start();
                } catch (Throwable t) {
                    failure.set(String.valueOf(t));
                }
            }
        }, "e2e-signal");
        starter.setDaemon(true);
        starter.start();

        Thread.sleep(seconds * 1000L);

        PullSession session = sessionRef.get();
        long total = received.get();
        long nz = nonZero.get();
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }

        Log.i(TAG, "收到 " + total + " 字节，非零 " + nz
                + (total > 0 ? "（" + (nz * 100 / total) + "%）" : ""));

        if (failure.get() != null) {
            fail("拉取失败：" + failure.get());
        }
        assertTrue("一个字节都没收到", total > 0);
        // 这条的前提是「PC 那边此刻真的有声音在放」——那是环境状态，不是被测代码的行为：
        // 没在放的时候环回采到的本来就该是静音。前提不成立时跳过，不要把它误报成失败
        //（与 PullSessionTest 里「本机 12670 有服务就跳过」同一种处理）。
        org.junit.Assume.assumeTrue(
                "PC 那边此刻没有声音在放（采到的全是静音），这条用例的前提不成立", nz > 0);
    }
}
