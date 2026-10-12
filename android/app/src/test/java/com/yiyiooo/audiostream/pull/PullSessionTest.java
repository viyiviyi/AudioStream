package com.yiyiooo.audiostream.pull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.protocol.AudioFraming;
import com.yiyiooo.audiostream.protocol.FrameKind;
import com.yiyiooo.audiostream.protocol.FrameReader;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 拉取会话的测试：对端是一个**本机的假 AudioStream 服务端**（真 ServerSocket、真字节），
 * 不是打桩过的对象。要验的是「按这套协议说话，到底能不能把音频对齐地拿下来」，
 * 以及每一条拒绝原因有没有被翻译成人话。
 */
public class PullSessionTest {

    private static final String PC_ID = "0123456789abcdef0123456789abcdef";
    private static final String DEV_ID = "device-1";
    private static final String DEV_NAME = "扬声器 (Realtek)";

    // ---------- 假对端 ----------

    /** 服务端脚本。抛异常即视为该用例失败（会在用例末尾报出来）。 */
    private interface Script {
        void run(Talk talk) throws Exception;
    }

    /** 脚本手里的那支笔：按帧读、按帧写。 */
    private static final class Talk {
        final Socket socket;
        final FrameReader reader;
        final OutputStream out;

        Talk(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.reader = new FrameReader(in);
            this.out = out;
        }

        /** 读一条文本命令；读到的不是文本帧时返回 {@code <类型名>}，方便断言看出实际情况。 */
        String readText() throws IOException {
            if (!reader.readNext()) return null;
            if (reader.lastKind() != FrameKind.TEXT) {
                return "<" + (reader.lastKind() == null ? "未知" : reader.lastKind().name()) + ">";
            }
            return reader.readText();
        }

        void sendText(String text) throws IOException {
            AudioFraming.writeFrame(out, AudioFraming.encodeText(text));
        }

        /**
         * 发一条音频格式帧。字节是**手写**的：[int32 采样率][int32 位深][int32 声道数][int32 编码]，
         * 全部**小端**——不用被测那个编码器来生成，免得自己验自己。
         *
         * 这里必须自己按小端移位写：第一版用 DataOutputStream.writeInt，那是大端，
         * 结果 48000 被解成一个负数而判格式无效，44100 又被解成一个非空的垃圾格式而让断言「通过」——
         * 两头都是假的，只有把小端写明白才能真的验到东西。
         */
        void sendFormat(int sampleRate, int bits, int channels, int encoding) throws IOException {
            byte[] payload = new byte[16];
            writeLittleEndian(payload, 0, sampleRate);
            writeLittleEndian(payload, 4, bits);
            writeLittleEndian(payload, 8, channels);
            writeLittleEndian(payload, 12, encoding);
            AudioFraming.writeFrame(out, AudioFraming.encodeWaveFormat(payload));
        }

        private static void writeLittleEndian(byte[] buffer, int offset, int value) {
            buffer[offset] = (byte) (value & 0xFF);
            buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
            buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
            buffer[offset + 3] = (byte) ((value >> 24) & 0xFF);
        }

        /** 发一段音频，可以按 chunkSize 切成碎块发——用来验「半包也不能错位」。 */
        void sendAudio(byte[] data, int chunkSize) throws IOException {
            if (chunkSize <= 0 || chunkSize >= data.length) {
                AudioFraming.writeFrame(out, AudioFraming.encode(FrameKind.AUDIO, data, 0, data.length));
                return;
            }
            // 直接写半条帧：帧头与负载都可能被切开
            byte[] frame = AudioFraming.encode(FrameKind.AUDIO, data, 0, data.length);
            int done = 0;
            while (done < frame.length) {
                int want = Math.min(chunkSize, frame.length - done);
                out.write(frame, done, want);
                out.flush();
                done += want;
            }
        }

        void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 一个只在 127.0.0.1 上监听、接受一条连接并跑脚本的假服务端。 */
    private static final class FakePc implements Closeable {
        private final ServerSocket server;
        private final Thread thread;
        private final AtomicReference<Throwable> scriptFailure = new AtomicReference<>();

        FakePc(final Script script) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setTcpNoDelay(true);
                    script.run(new Talk(socket, socket.getInputStream(), socket.getOutputStream()));
                } catch (Throwable t) {
                    scriptFailure.set(t);
                }
            }, "fake-pc");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        /** 用例末尾调用：把脚本里的异常抛出来，否则断言失败会被吞掉。 */
        void assertScriptOk() {
            Throwable t = scriptFailure.get();
            if (t != null) {
                throw new AssertionError("假服务端脚本出错：" + t, t);
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /** 收集状态与音频的 listener。 */
    private static final class Recorder implements PullSession.Listener {
        final List<PullState> states = Collections.synchronizedList(new ArrayList<>());
        final List<String> details = Collections.synchronizedList(new ArrayList<>());
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch terminal = new CountDownLatch(1);
        final CountDownLatch playing = new CountDownLatch(1);
        final AtomicReference<AudioFormat> format = new AtomicReference<>();

        @Override
        public void onState(PullState state, String detail) {
            states.add(state);
            details.add(detail);
            if (state == PullState.PLAYING) playing.countDown();
            if (state.isTerminal()) terminal.countDown();
        }

        @Override
        public void onFormat(AudioFormat value) {
            format.set(value);
        }

        @Override
        public void onAudio(byte[] data, int offset, int length) {
            audio.write(data, offset, length);
        }

        String lastDetail() {
            synchronized (details) {
                return details.isEmpty() ? "" : details.get(details.size() - 1);
            }
        }
    }

    private static PullSession start(FakePc pc, Recorder recorder) {
        PullSession session = new PullSession("127.0.0.1", pc.port(), DEV_ID, DEV_NAME,
                "fedcba9876543210fedcba9876543210", "我的手机", recorder);
        session.start();
        return session;
    }

    // ---------- 用例 ----------

    @Test
    public void 正常链路_握手点单收音频() throws Exception {
        final byte[] pcm = new byte[4000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 251);

        try (FakePc pc = new FakePc(talk -> {
            String hello = talk.readText();
            assertTrue("握手命令不对：" + hello, hello.startsWith("/Hello/fedcba9876543210fedcba9876543210/"));
            talk.sendText("/Hello/" + PC_ID + "/" + RejectText.escapeDataString("书房电脑"));
            String order = talk.readText();
            assertEquals("/WaveFormat/" + DEV_ID + "/" + DEV_NAME, order);
            talk.sendFormat(48000, 32, 2, 3);
            assertEquals("/Start", talk.readText());
            talk.sendAudio(pcm, 0);
            talk.sleep(300);
        })) {
            Recorder recorder = new Recorder();
            PullSession session = start(pc, recorder);
            assertTrue("一直没进播放状态", recorder.playing.await(5, TimeUnit.SECONDS));
            // 等音频到齐
            for (int i = 0; i < 50 && recorder.audio.size() < pcm.length; i++) Thread.sleep(20);

            AudioFormat format = recorder.format.get();
            assertNotNull("没收到音频格式", format);
            assertEquals(48000, format.sampleRate);
            assertEquals(32, format.bitsPerSample);
            assertEquals(2, format.channels);
            assertTrue("32 位编码 3 应当被认成浮点", format.isFloat());
            assertEquals("音频字节与原样不一致", pcm.length, recorder.audio.size());
            assertTrue(java.util.Arrays.equals(pcm, recorder.audio.toByteArray()));
            assertEquals("书房电脑", session.remotePcName());
            assertEquals(PC_ID, session.remoteMachineId());
            session.close();
            pc.assertScriptOk();
        }
    }

    @Test
    public void 音频帧被切成碎块也不会错位() throws Exception {
        final byte[] pcm = new byte[900];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i * 7);

        try (FakePc pc = new FakePc(talk -> {
            talk.readText();
            talk.sendText("/Hello/" + PC_ID + "/PC");
            talk.readText();
            talk.sendFormat(44100, 16, 2, 1);
            talk.readText();
            // 每帧切成 3 字节一段发：帧头会被切开，负载也会被切开
            talk.sendAudio(pcm, 3);
            talk.sleep(300);
        })) {
            Recorder recorder = new Recorder();
            PullSession session = start(pc, recorder);
            assertTrue(recorder.playing.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 50 && recorder.audio.size() < pcm.length; i++) Thread.sleep(20);
            assertTrue("碎块发送后音频对不上：" + recorder.audio.size(), java.util.Arrays.equals(pcm, recorder.audio.toByteArray()));
            assertTrue(!recorder.format.get().isFloat());
            session.close();
            pc.assertScriptOk();
        }
    }

    @Test
    public void 对端认出这是它自己() throws Exception {
        try (FakePc pc = new FakePc(talk -> {
            talk.readText();
            talk.sendText("/Reject/self");
            talk.sleep(200);
        })) {
            Recorder recorder = new Recorder();
            PullSession session = start(pc, recorder);
            assertTrue(recorder.terminal.await(5, TimeUnit.SECONDS));
            assertEquals(PullState.FAILED, recorder.states.get(recorder.states.size() - 1));
            assertTrue(recorder.lastDetail(), recorder.lastDetail().contains("认出这是它自己"));
            session.close();
            pc.assertScriptOk();
        }
    }

    @Test
    public void 点单被拒时把对端的原话带回来() throws Exception {
        try (FakePc pc = new FakePc(talk -> {
            talk.readText();
            talk.sendText("/Hello/" + PC_ID + "/PC");
            talk.readText();
            // 原因里带斜杠与中文，验的是「转义 + 只切前三段」这一对口径
            talk.sendText("/Reject/nodevice/" + RejectText.escapeDataString("本机此刻没有可用的默认输入设备/麦克风"));
            talk.sleep(200);
        })) {
            Recorder recorder = new Recorder();
            PullSession session = start(pc, recorder);
            assertTrue(recorder.terminal.await(5, TimeUnit.SECONDS));
            String detail = recorder.lastDetail();
            assertTrue(detail, detail.contains("本机此刻没有可用的默认输入设备/麦克风"));
            assertEquals(PullState.FAILED, recorder.states.get(recorder.states.size() - 1));
            session.close();
            pc.assertScriptOk();
        }
    }

    @Test
    public void 播着播着对端断开要说清是断了() throws Exception {
        try (FakePc pc = new FakePc(talk -> {
            talk.readText();
            talk.sendText("/Hello/" + PC_ID + "/PC");
            talk.readText();
            talk.sendFormat(48000, 16, 2, 1);
            talk.readText();
            talk.sendAudio(new byte[64], 0);
            talk.sleep(150);
            talk.socket.close();
        })) {
            Recorder recorder = new Recorder();
            PullSession session = start(pc, recorder);
            assertTrue(recorder.terminal.await(5, TimeUnit.SECONDS));
            assertEquals(PullState.FAILED, recorder.states.get(recorder.states.size() - 1));
            assertTrue(recorder.lastDetail(), recorder.lastDetail().contains("把连接断开了"));
            session.close();
            pc.assertScriptOk();
        }
    }

    @Test
    public void 自己断开要先发Pause再进已停止() throws Exception {
        final CountDownLatch pauseSeen = new CountDownLatch(1);
        final AtomicReference<String> pauseValue = new AtomicReference<>();

        try (FakePc pc = new FakePc(talk -> {
            talk.readText();
            talk.sendText("/Hello/" + PC_ID + "/PC");
            talk.readText();
            talk.sendFormat(48000, 16, 2, 1);
            talk.readText();
            talk.sendAudio(new byte[64], 0);
            // 一直等到客户端发 /Pause
            String line = talk.readText();
            pauseValue.set(line);
            pauseSeen.countDown();
            talk.sleep(100);
        })) {
            Recorder recorder = new Recorder();
            PullSession session = start(pc, recorder);
            assertTrue(recorder.playing.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);
            session.close();
            assertTrue("对端没收到 /Pause", pauseSeen.await(5, TimeUnit.SECONDS));
            assertEquals("/Pause", pauseValue.get());
            assertEquals(PullState.STOPPED, recorder.states.get(recorder.states.size() - 1));
            pc.assertScriptOk();
        }
    }

    @Test
    public void 对端一个端口都不通时给出能照着处理的话() throws Exception {
        // 若本机真的跑着 AudioStream（12670 有服务），这条用例的语义就不成立了，跳过而不是误报
        org.junit.Assume.assumeFalse("本机 12670 上有服务在跑，跳过「连不上」用例", listening(12670));
        Recorder recorder = new Recorder();
        PullSession session = new PullSession("127.0.0.1", 1, DEV_ID, DEV_NAME, "mobile", "手机", recorder);
        session.start();
        assertTrue(recorder.terminal.await(15, TimeUnit.SECONDS));
        assertEquals(PullState.FAILED, recorder.states.get(recorder.states.size() - 1));
        assertTrue(recorder.lastDetail(), recorder.lastDetail().contains("连不上"));
        assertTrue(recorder.lastDetail(), recorder.lastDetail().contains("12670-12689"));
    }

    @Test
    public void 转义规则与CSharp的EscapeDataString一致() {
        // 空格必须编成 %20 而不是 '+'：C# 的 UnescapeDataString 不把 '+' 当空格
        assertEquals("a%20b", RejectText.escapeDataString("a b"));
        assertEquals("A-Za-z0-9-._~", RejectText.escapeDataString("A-Za-z0-9-._~"));
        assertEquals("%E4%B9%A6%E6%88%BF", RejectText.escapeDataString("书房"));
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
