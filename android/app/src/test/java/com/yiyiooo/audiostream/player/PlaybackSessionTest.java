package com.yiyiooo.audiostream.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.yiyiooo.audiostream.pull.PullState;
import com.yiyiooo.audiostream.protocol.AudioFormat;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放会话的判断：水位、起播门槛、欠载补帧、溢出丢帧。
 *
 * 声卡是假的（{@link FakeSink} 只把写进来的字节收着），所以这些判断能在 JVM 上精确验。
 * 「真的出声」这一步假 sink 验不了，那只能在模拟器或真机上听，见 android\README.md 的验证边界。
 */
public class PlaybackSessionTest {

    /** 48000Hz / 16 位 / 立体声，每秒 192000 字节，一帧 4 字节。 */
    private static final AudioFormat FORMAT_48K_16_2 =
            new AudioFormat(48000, 16, 2, 1);

    private static final int BYTES_PER_MS = 192;

    private static byte[] ramp(int length, int seed) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = (byte) ((seed + i) & 0xFF);
        return data;
    }

    private static final class FakeSink implements AudioSink {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        volatile boolean playing;
        volatile boolean released;
        /** 最后一次被设置的音量，以及被设过多少次——用来验「重建声卡后会补设」。 */
        volatile float volume = 1f;
        volatile int volumeSetCount;

        @Override
        public int write(byte[] data, int offset, int length) {
            if (released || length <= 0) return 0;
            written.write(data, offset, length);
            return length;
        }

        @Override
        public void play() {
            playing = true;
        }

        @Override
        public void release() {
            released = true;
        }

        @Override
        public int bufferSizeBytes() {
            return 3840;
        }

        @Override
        public void setVolume(float value) {
            volume = value;
            volumeSetCount++;
        }
    }

    private static final class FakeFactory implements AudioSinkFactory {
        final FakeSink sink = new FakeSink();
        String failWith;
        int created;

        @Override
        public AudioSink create(AudioFormat format) throws UnsupportedAudioException {
            created++;
            if (failWith != null) throw new UnsupportedAudioException(failWith);
            return sink;
        }
    }

    private static final class Recorder implements PlaybackSession.Listener {
        final List<PullState> states = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        @Override
        public void onState(PullState state, String detail) {
            states.add(state);
        }

        @Override
        public void onPlaybackFailed(String reason) {
            failures.add(reason);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void 攒到目标水位的一半才起播() {
        FakeFactory factory = new FakeFactory();
        Recorder recorder = new Recorder();
        PlaybackSession session = new PlaybackSession(factory, recorder);
        session.onFormat(FORMAT_48K_16_2);
        session.onState(PullState.PLAYING, "正在播放");

        // 先给 10ms：门槛是目标水位 35ms 的一半，也就是 17.5ms，不够
        session.onAudio(ramp(10 * BYTES_PER_MS, 1), 0, 10 * BYTES_PER_MS);
        sleep(150);
        assertTrue("水位不够就不该起播", !factory.sink.playing);
        assertEquals(0, factory.sink.written.size());

        // 再给 40ms，越过门槛
        session.onAudio(ramp(40 * BYTES_PER_MS, 2), 0, 40 * BYTES_PER_MS);
        long deadline = System.currentTimeMillis() + 2000;
        while (!factory.sink.playing && System.currentTimeMillis() < deadline) sleep(10);
        assertTrue("攒够了却没起播", factory.sink.playing);
        session.close();
    }

    @Test
    public void 重连重建声卡时会补上已设置的音量() {
        FakeFactory factory = new FakeFactory();
        PlaybackSession session = new PlaybackSession(factory, new Recorder());
        session.onFormat(FORMAT_48K_16_2);

        session.setVolume(0.35f);
        assertEquals("设置后应立刻作用到声卡", 0.35f, factory.sink.volume, 0.0001f);

        // 重连会重建声卡。把 sink 上的值打回默认，再协商一次格式，看旧音量有没有被补上——
        // 不补的话，用户会觉得「断了重连之后声音突然变大了」，而界面上滑条还停在原位。
        factory.sink.volume = 1f;
        int setCountBefore = factory.sink.volumeSetCount;
        session.onFormat(FORMAT_48K_16_2);

        assertEquals("重建声卡后必须把旧音量补上", 0.35f, factory.sink.volume, 0.0001f);
        assertTrue("重建后应当重新设过一次", factory.sink.volumeSetCount > setCountBefore);
        session.close();
    }

    @Test
    public void 音量超出范围会被夹回来() {
        FakeFactory factory = new FakeFactory();
        PlaybackSession session = new PlaybackSession(factory, new Recorder());
        session.onFormat(FORMAT_48K_16_2);

        session.setVolume(5f);
        assertEquals(1f, session.volume(), 0.0001f);
        session.setVolume(-2f);
        assertEquals(0f, session.volume(), 0.0001f);
        session.close();
    }

    @Test
    public void 欠载时重复上一帧而不是补静音() {
        FakeFactory factory = new FakeFactory();
        PlaybackSession session = new PlaybackSession(factory, new Recorder());
        session.onFormat(FORMAT_48K_16_2);
        session.onState(PullState.PLAYING, "正在播放");

        int fed = 40 * BYTES_PER_MS;
        byte[] audio = ramp(fed, 7);
        session.onAudio(audio, 0, fed);

        long deadline = System.currentTimeMillis() + 2000;
        while (!factory.sink.playing && System.currentTimeMillis() < deadline) sleep(10);
        assertTrue(factory.sink.playing);

        // 起播后不再喂数据，写线程应当把缓冲抽干、然后重复上一帧顶上去
        deadline = System.currentTimeMillis() + 3000;
        while (session.underruns() == 0 && System.currentTimeMillis() < deadline) sleep(10);
        assertTrue("一直没欠载？", session.underruns() > 0);

        byte[] out = factory.sink.written.toByteArray();
        assertTrue("欠载后应当继续写（重复上一帧），而不是停在那里：" + out.length, out.length > fed);
        // 超过已喂数据的部分，必须是「上一帧」的重复
        byte[] lastFrame = new byte[4];
        System.arraycopy(audio, fed - 4, lastFrame, 0, 4);
        byte[] repeated = new byte[4];
        System.arraycopy(out, fed, repeated, 0, 4);
        assertArrayEquals("欠载补的应当是上一帧，不是静音", lastFrame, repeated);
        assertTrue("补的不该是静音", repeated[0] != 0 || repeated[1] != 0 || repeated[2] != 0 || repeated[3] != 0);
        session.close();
    }

    @Test
    public void 缓冲装不下就丢最旧的并记下来() {
        FakeFactory factory = new FakeFactory();
        PlaybackSession session = new PlaybackSession(factory, new Recorder());
        session.onFormat(FORMAT_48K_16_2);

        // 不调 PLAYING（写线程不启动），纯粹往缓冲里灌 300ms，容量只有 120ms
        session.onAudio(ramp(300 * BYTES_PER_MS, 3), 0, 300 * BYTES_PER_MS);
        assertTrue("溢出没记账：" + session.droppedBytes(), session.droppedBytes() > 0);
        assertEquals(PlaybackTuning.CAPACITY_MS, session.bufferedMs());
        session.close();
    }

    @Test
    public void 格式放不了要把原因报上来() {
        FakeFactory factory = new FakeFactory();
        factory.failWith = "对端给的是 6 声道，这台手机只会放单声道与立体声。";
        Recorder recorder = new Recorder();
        PlaybackSession session = new PlaybackSession(factory, recorder);

        session.onFormat(new AudioFormat(48000, 16, 6, 1));
        assertEquals(1, recorder.failures.size());
        assertEquals(factory.failWith, recorder.failures.get(0));
        assertEquals(factory.failWith, session.lastError());
        assertNull("建不起来就不该有输出", session.sink());
        session.close();
    }

    @Test
    public void 重连时旧声卡要先放掉() {
        FakeFactory factory = new FakeFactory();
        Recorder recorder = new Recorder();
        PlaybackSession session = new PlaybackSession(factory, recorder);

        session.onFormat(FORMAT_48K_16_2);
        FakeSink first = factory.sink;
        assertNotNull(first);
        // 第二次协商（重连）会换一块声卡
        session.onFormat(FORMAT_48K_16_2);
        assertTrue("旧的输出没被释放", first.released);
        assertEquals(2, factory.created);
        session.close();
    }

    @Test
    public void 关掉之后释放声卡且不再写() {
        FakeFactory factory = new FakeFactory();
        PlaybackSession session = new PlaybackSession(factory, new Recorder());
        session.onFormat(FORMAT_48K_16_2);
        session.onState(PullState.PLAYING, "正在播放");
        session.onAudio(ramp(40 * BYTES_PER_MS, 5), 0, 40 * BYTES_PER_MS);

        long deadline = System.currentTimeMillis() + 2000;
        while (!factory.sink.playing && System.currentTimeMillis() < deadline) sleep(10);
        session.close();
        assertTrue(factory.sink.released);
        // 写线程可能正好卡在一次 write 里，等它彻底停下来再取样
        sleep(100);
        int afterClose = factory.sink.written.size();
        sleep(150);
        assertEquals("关掉之后不该再写", afterClose, factory.sink.written.size());
        assertTrue(!session.isPlaying());
    }
}
