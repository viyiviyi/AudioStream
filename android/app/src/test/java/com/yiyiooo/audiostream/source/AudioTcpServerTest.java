package com.yiyiooo.audiostream.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.yiyiooo.audiostream.protocol.AudioEncoding;
import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.protocol.AudioFraming;
import com.yiyiooo.audiostream.protocol.FrameKind;
import com.yiyiooo.audiostream.protocol.FrameReader;
import com.yiyiooo.audiostream.protocol.WaveFormatCodec;
import com.yiyiooo.audiostream.pull.PullSession;
import com.yiyiooo.audiostream.pull.PullState;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 来源端 TCP 服务的测试。
 *
 * 正常那条链路用**本工程自己的拉取方** {@link PullSession} 去连——它是照 C# 端
 * {@code NetAudioRedirector} 写的，让两边对话，等于拿一套独立实现来验另一套：
 * 格式字段的字节序、帧边界、命令时序只要有一处不对，这里就会以「拿不到格式」或「帧长不对」暴露出来。
 *
 * 拒绝那几条用手写的小客户端直连（只发命令读应答），因为要断言的是**协议里那一段原因字符串**，
 * 不该经过拉取方那层「翻成人话」的逻辑——那是另一个地方该测的事。
 *
 * 全部跑在本机回环的真 socket 上，音频来源是个产生方波的假源（{@link FakeSource}），
 * 所以不需要设备、不需要录音权限。
 */
public class AudioTcpServerTest {

    private static final String SERVER_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String PC_ID = "0123456789abcdef0123456789abcdef";
    private static final String MIC_ID = "mic:default";
    private static final String SERVER_NAME = "Android 手机";

    /** 一帧 20ms：48000Hz × 16 位 × 单声道 × 20ms。 */
    private static final int FRAME_BYTES = 1920;

    // ========== 假来源 ==========

    /**
     * 假麦克风：每 20ms 产出一帧，帧头 4 字节是小端序号，其余是方波。
     *
     * 序号是为了验「帧边界没有被吃掉」——音频是定长采样，少几个字节后面全部错位，
     * 光看「收到了字节」是看不出来的。缓冲刻意**复用同一块**，这样也顺带验到
     * 会话层有没有老老实实把数据拷走再排队。
     */
    private static final class FakeSource implements AudioSource {

        private final AudioFormat format = new AudioFormat(48000, 16, 1, AudioEncoding.PCM);
        private final List<Sink> sinks = new CopyOnWriteArrayList<Sink>();
        private final int frameBytes;

        private volatile Thread worker;
        private volatile boolean running;
        private int sequence;

        FakeSource(int frameBytes) {
            this.frameBytes = frameBytes;
        }

        @Override
        public AudioFormat format() {
            return format;
        }

        @Override
        public String describe() {
            return "测试用方波";
        }

        @Override
        public void addSink(Sink sink) {
            sinks.add(sink);
            startIfNeeded();
        }

        @Override
        public void removeSink(Sink sink) {
            sinks.remove(sink);
            if (sinks.isEmpty()) stop();
        }

        @Override
        public int sinkCount() {
            return sinks.size();
        }

        @Override
        public void close() {
            stop();
            sinks.clear();
        }

        private synchronized void startIfNeeded() {
            if (running) return;
            running = true;
            worker = new Thread(this::produce, "fake-source");
            worker.setDaemon(true);
            worker.start();
        }

        private synchronized void stop() {
            running = false;
            Thread thread = worker;
            worker = null;
            if (thread != null) thread.interrupt();
        }

        private void produce() {
            byte[] chunk = new byte[frameBytes];
            while (running) {
                int seq = sequence++;
                chunk[0] = (byte) (seq & 0xFF);
                chunk[1] = (byte) ((seq >> 8) & 0xFF);
                chunk[2] = (byte) ((seq >> 16) & 0xFF);
                chunk[3] = (byte) ((seq >> 24) & 0xFF);
                for (int i = 4; i < chunk.length; i++) {
                    chunk[i] = (byte) ((i & 1) == 0 ? 0x40 : 0xC0);
                }
                for (Sink sink : sinks) {
                    try {
                        sink.onAudio(chunk, 0, chunk.length);
                    } catch (Exception ignored) {
                        // 一个订阅者出问题不该带走另一个
                    }
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 假 provider：正常时给同一个源，也可以被设成固定失败，用来走拒绝那几条路。 */
    private static final class FakeProvider implements AudioSourceProvider {

        private final AudioSource source;
        private volatile SourceUnavailableException failure;
        /** 内录那一路。为 null 表示「用户还没点过内录授权」——手机上的默认状态。 */
        private volatile AudioSource capture;

        FakeProvider(AudioSource source) {
            this.source = source;
        }

        void failWith(String reason, String message) {
            failure = new SourceUnavailableException(reason, message);
        }

        /** 假装用户已经在系统弹框里点了同意。 */
        void grantCapture(AudioSource captureSource) {
            capture = captureSource;
        }

        @Override
        public AudioSource microphone() throws SourceUnavailableException {
            SourceUnavailableException failed = failure;
            if (failed != null) throw failed;
            return source;
        }

        @Override
        public AudioSource playbackCapture() throws SourceUnavailableException {
            SourceUnavailableException failed = failure;
            if (failed != null) throw failed;
            AudioSource granted = capture;
            if (granted == null) {
                throw new SourceUnavailableException(SourceUnavailableException.DENIED,
                        "手机还没有允许内录，请在手机上点「允许内录」，并在系统弹框里同意");
            }
            return granted;
        }
    }

    // ========== 拉取方记录 ==========

    private static final class Recorder implements PullSession.Listener {

        final CountDownLatch formatLatch = new CountDownLatch(1);
        final CountDownLatch audioLatch = new CountDownLatch(3);

        volatile AudioFormat format;
        volatile PullState lastState;
        volatile String lastDetail = "";

        final List<Integer> lengths = new CopyOnWriteArrayList<Integer>();
        final List<Integer> sequences = new CopyOnWriteArrayList<Integer>();

        @Override
        public void onState(PullState state, String detail) {
            lastState = state;
            lastDetail = detail == null ? "" : detail;
        }

        @Override
        public void onFormat(AudioFormat value) {
            format = value;
            formatLatch.countDown();
        }

        @Override
        public void onAudio(byte[] data, int offset, int length) {
            // data 是拉取方复用的缓冲：这里立刻把要的东西取出来，不留引用
            lengths.add(length);
            if (length >= 4) {
                sequences.add((data[offset] & 0xFF)
                        | ((data[offset + 1] & 0xFF) << 8)
                        | ((data[offset + 2] & 0xFF) << 16)
                        | ((data[offset + 3] & 0xFF) << 24));
            }
            audioLatch.countDown();
        }

        boolean awaitFormat(long ms) throws InterruptedException {
            return formatLatch.await(ms, TimeUnit.MILLISECONDS);
        }

        boolean awaitAudio(long ms) throws InterruptedException {
            return audioLatch.await(ms, TimeUnit.MILLISECONDS);
        }
    }

    // ========== 用例 ==========

    @Test
    public void 点单麦克风能拿到格式并收到连续的音频帧() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        PullSession pull = null;
        try {
            Recorder recorder = new Recorder();
            pull = new PullSession("127.0.0.1", port, MIC_ID, "手机麦克风", PC_ID, "测试电脑", recorder);
            pull.start();

            assertTrue("5 秒内没有拿到音频格式，状态：" + recorder.lastState + " " + recorder.lastDetail,
                    recorder.awaitFormat(5000));
            AudioFormat format = recorder.format;
            assertNotNull(format);
            assertEquals(48000, format.sampleRate);
            assertEquals(16, format.bitsPerSample);
            assertEquals(1, format.channels);
            assertEquals(AudioEncoding.PCM, format.encoding);

            assertTrue("5 秒内没有收到音频帧", recorder.awaitAudio(5000));

            // 每一帧都必须是完整的一帧：长度不对说明帧边界被吃掉了
            for (Integer length : recorder.lengths) {
                assertEquals("音频帧长度不对，帧边界被吃掉了", FRAME_BYTES, length.intValue());
            }
            // 序号必须严格递增：允许跳号（队列积压时丢最旧的），不允许乱序或重复
            List<Integer> sequences = new ArrayList<Integer>(recorder.sequences);
            assertTrue("收到的音频帧太少，验不出连续性：" + sequences.size(), sequences.size() >= 3);
            for (int i = 1; i < sequences.size(); i++) {
                assertTrue("音频帧序号乱了：" + sequences.get(i - 1) + " → " + sequences.get(i),
                        sequences.get(i) > sequences.get(i - 1));
            }

            assertEquals("一条会话只该挂一个订阅者", 1, source.sinkCount());
            assertEquals(1, server.sessionCount());
            // 来源侧要记得自己答应出去的是哪个格式：PC 端就是按它建播放器的
            assertEquals(48000, server.sessions().get(0).sentFormat().sampleRate);
        } finally {
            if (pull != null) pull.close();
            server.close();
            source.close();
        }
    }

    @Test
    public void 握手应答里的名字按CSharp口径转义() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        try {
            String reply = talk(port, "/Hello/" + PC_ID + "/测试电脑");
            assertHelloReply(reply);
        } finally {
            server.close();
            source.close();
        }
    }

    @Test
    public void 对端点自己时回Reject_self() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        try {
            // 机器号报成服务端自己的：这条流必然绕回本机成环
            String reply = talk(port, "/Hello/" + SERVER_ID + "/自己");
            assertEquals("/Reject/self", reply);
        } finally {
            server.close();
            source.close();
        }
    }

    @Test
    public void 点单手机没有的设备时回Reject_nodevice并说明原因() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        try {
            // default 现在归内录（手机正在放的声音），不再是「手机没有这类设备」，
            // 所以这里换一个真正不存在的设备号来走 nodevice
            String reply = talk(port,
                    "/Hello/" + PC_ID + "/测试电脑",
                    "/WaveFormat/speaker:0/对方扬声器");
            assertReject(reply, "nodevice");
            // 第 4 段要带上能看懂的原因（URL 转义过），并说清手机这边有什么
            String reason = unescape(reply);
            assertTrue("拒绝原因里没提到内录：" + reply, reason.contains("内录"));
            assertTrue("拒绝原因里没提到麦克风：" + reply, reason.contains("麦克风"));
        } finally {
            server.close();
            source.close();
        }
    }

    @Test
    public void 没录音权限时回Reject_denied() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        FakeProvider provider = new FakeProvider(source);
        provider.failWith(SourceUnavailableException.DENIED, "手机还没允许 AudioStream 录音");
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, provider, 0, 1);
        int port = server.start();
        try {
            String reply = talk(port,
                    "/Hello/" + PC_ID + "/测试电脑",
                    "/WaveFormat/" + MIC_ID + "/手机麦克风");
            assertReject(reply, "denied");
            assertTrue("没把「要用户去授权」这件事说清楚：" + reply, unescape(reply).contains("录音"));
        } finally {
            server.close();
            source.close();
        }
    }

    @Test
    public void 点单内录但没授权时回Reject_denied并指明去点允许内录() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        // FakeProvider 默认没内录授权，正是手机刚装好的状态
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        try {
            String reply = talk(port,
                    "/Hello/" + PC_ID + "/测试电脑",
                    "/WaveFormat/capture:default/手机内录");
            assertReject(reply, "denied");
            // 协议原因与麦克风那条同为 denied，但**提示语必须不同**：
            // 一个要去系统设置里给权限，一个要在本应用里点一下再在系统弹框里同意。
            // 提示语错了，用户就会去改错的地方
            assertTrue("没把「去点允许内录」说清楚：" + reply, unescape(reply).contains("允许内录"));
        } finally {
            server.close();
            source.close();
        }
    }

    @Test
    public void 授权后点单内录能拿到格式帧与音频并且不碰麦克风() throws Exception {
        FakeSource mic = new FakeSource(FRAME_BYTES);
        FakeSource capture = new FakeSource(FRAME_BYTES);
        FakeProvider provider = new FakeProvider(mic);
        provider.grantCapture(capture);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, provider, 0, 1);
        int port = server.start();
        PullSession pull = null;
        try {
            Recorder recorder = new Recorder();
            pull = new PullSession("127.0.0.1", port, "capture:default", "手机内录",
                    PC_ID, "测试电脑", recorder);
            pull.start();

            assertTrue("5 秒内没拿到内录的音频格式，状态：" + recorder.lastState + " " + recorder.lastDetail,
                    recorder.awaitFormat(5000));
            assertNotNull(recorder.format);
            assertEquals(48000, recorder.format.sampleRate);
            assertEquals(AudioEncoding.PCM, recorder.format.encoding);

            assertTrue("5 秒内没收到内录的音频帧", recorder.awaitAudio(5000));
            // 这条才是「点的是内录」的证据：拉取方挂在内录那一路，而不是顺手把麦克风给了它
            assertEquals("订阅者应当挂在内录那一路", 1, capture.sinkCount());
            assertEquals("麦克风那一路不该被拉起来", 0, mic.sinkCount());
        } finally {
            if (pull != null) pull.close();
            server.close();
            mic.close();
            capture.close();
        }
    }

    @Test
    public void 采集打不开时回Reject_unavailable() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        FakeProvider provider = new FakeProvider(source);
        provider.failWith(SourceUnavailableException.UNAVAILABLE, "麦克风被别的程序占着");
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, provider, 0, 1);
        int port = server.start();
        try {
            String reply = talk(port,
                    "/Hello/" + PC_ID + "/测试电脑",
                    "/WaveFormat/" + MIC_ID + "/手机麦克风");
            assertReject(reply, "unavailable");
        } finally {
            server.close();
            source.close();
        }
    }

    @Test
    public void 点单成功时回的是16字节的整数编码格式帧() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(4000);
            FrameReader reader = new FrameReader(socket.getInputStream());

            send(socket, "/Hello/" + PC_ID + "/测试电脑");
            assertTrue(reader.readNext());
            assertEquals(FrameKind.TEXT, reader.lastKind());
            assertHelloReply(reader.readText());

            send(socket, "/WaveFormat/" + MIC_ID + "/手机麦克风");
            assertTrue(reader.readNext());
            assertEquals("点单成功时回的必须是音频格式帧", FrameKind.WAVE_FORMAT, reader.lastKind());
            // 16 字节整数编码：落在 13~36 这一段里，PC 端才会走整数分支；
            // 少于 13 字节它不认，会静默退回自认的默认格式，那是最难查的一种「没声音」
            assertEquals(16, reader.lastCount());
            AudioFormat parsed = WaveFormatCodec.parse(reader.buffer(), reader.lastCount());
            assertNotNull(parsed);
            assertEquals(48000, parsed.sampleRate);
            assertEquals(16, parsed.bitsPerSample);
            assertEquals(1, parsed.channels);
            assertEquals(AudioEncoding.PCM, parsed.encoding);
        } finally {
            if (socket != null) socket.close();
            server.close();
            source.close();
        }
    }

    @Test
    public void 两台电脑同时拉麦克风时挂到同一路采集上() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        PullSession first = null;
        PullSession second = null;
        try {
            Recorder one = new Recorder();
            Recorder two = new Recorder();
            first = new PullSession("127.0.0.1", port, MIC_ID, "手机麦克风", PC_ID, "电脑一", one);
            second = new PullSession("127.0.0.1", port, MIC_ID, "手机麦克风",
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "电脑二", two);
            first.start();
            second.start();

            assertTrue("第一条会话没拿到音频", one.awaitAudio(5000));
            assertTrue("第二条会话没拿到音频", two.awaitAudio(5000));

            // 两个订阅者，但下面 provider 给的是同一个源实例——手机不该为每条连接各开一路
            // AudioRecord（同时开两路会互相打架），这才是「共享一路采集」的意思
            assertEquals("两条会话应当挂在同一个源上", 2, source.sinkCount());
            assertEquals(2, server.sessionCount());
        } finally {
            if (first != null) first.close();
            if (second != null) second.close();
            server.close();
            source.close();
        }
    }

    @Test
    public void 对端收工后服务端会把这路采集放掉() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        try {
            Recorder recorder = new Recorder();
            PullSession pull = new PullSession("127.0.0.1", port, MIC_ID, "手机麦克风", PC_ID, "测试电脑", recorder);
            pull.start();
            assertTrue("没拿到音频", recorder.awaitAudio(5000));
            assertEquals(1, source.sinkCount());

            // close 会发 /Pause 并断开
            pull.close();
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline
                    && (server.sessionCount() > 0 || source.sinkCount() > 0)) {
                Thread.sleep(50);
            }
            assertEquals("会话结束后应当没有残留会话", 0, server.sessionCount());
            assertEquals("没人听了就该把麦克风关掉", 0, source.sinkCount());
        } finally {
            server.close();
            source.close();
        }
    }

    @Test
    public void 认不出的命令被忽略而连接不断() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(4000);
            FrameReader reader = new FrameReader(socket.getInputStream());

            send(socket, "/Hello/" + PC_ID + "/测试电脑");
            assertTrue(reader.readNext());
            assertEquals(FrameKind.TEXT, reader.lastKind());
            assertHelloReply(reader.readText());

            // 一条将来版本才有的命令：服务端**不会**给它任何应答，这是刻意的
            send(socket, "/SomeFutureCommand/hello");

            // 紧接着正常点单。中间那条如果让流错位了（比如没按帧读掉），这里就拿不到格式帧
            send(socket, "/WaveFormat/" + MIC_ID + "/手机麦克风");
            assertTrue("认不出的命令之后连接断了", reader.readNext());
            assertEquals("认不出的命令把流搞错位了", FrameKind.WAVE_FORMAT, reader.lastKind());
        } finally {
            if (socket != null) socket.close();
            server.close();
            source.close();
        }
    }

    @Test
    public void 刚发过东西时Ping不回Pong免得和音频抢路() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(4000);
            FrameReader reader = new FrameReader(socket.getInputStream());

            send(socket, "/Hello/" + PC_ID + "/测试电脑");
            assertTrue(reader.readNext());
            assertHelloReply(reader.readText());

            // 握手应答本身就是「刚发过东西」，紧接着 Ping 按协议不该回 Pong：
            // 对端正等着格式帧，插一条 Pong 进去是白占一次发送
            send(socket, "/Ping");
            send(socket, "/WaveFormat/" + MIC_ID + "/手机麦克风");
            assertTrue(reader.readNext());
            assertEquals("紧接着 Ping 就回了 Pong，会和音频抢发送",
                    FrameKind.WAVE_FORMAT, reader.lastKind());
        } finally {
            if (socket != null) socket.close();
            server.close();
            source.close();
        }
    }

    @Test
    public void 空闲十秒后Ping回Pong() throws Exception {
        FakeSource source = new FakeSource(FRAME_BYTES);
        AudioTcpServer server = new AudioTcpServer(SERVER_ID, SERVER_NAME, new FakeProvider(source), 0, 1);
        int port = server.start();
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", port);
            // 这个用例要真的等 10 秒以上，读超时得比它宽（会话本身的命令超时是 20 秒）
            socket.setSoTimeout(20000);
            FrameReader reader = new FrameReader(socket.getInputStream());

            send(socket, "/Hello/" + PC_ID + "/测试电脑");
            assertTrue(reader.readNext());
            assertHelloReply(reader.readText());

            // 对端（PC 端）每 10 秒问一次心跳，为的是确认这条连接还活着。
            // 这 10 秒里我们什么都没发，所以这一次必须回 Pong，否则对端会以为对端已死。
            Thread.sleep(10500);
            send(socket, "/Ping");
            assertTrue("空闲十秒后 Ping 应当得到 Pong", reader.readNext());
            assertEquals(FrameKind.TEXT, reader.lastKind());
            assertEquals("/Pong", reader.readText());
        } finally {
            if (socket != null) socket.close();
            server.close();
            source.close();
        }
    }

    // ========== 小客户端与断言工具 ==========

    private static void send(Socket socket, String command) throws IOException {
        AudioFraming.writeFrame(socket.getOutputStream(), AudioFraming.encodeText(command));
    }

    /**
     * 手写的小客户端：按顺序发命令，每发一条读一条应答。
     *
     * @return 最后读到的**文本**应答；点单成功那种二进制格式帧不会覆盖它
     */
    private static String talk(int port, String... commands) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(4000);
        try {
            FrameReader reader = new FrameReader(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String last = null;
            for (String command : commands) {
                AudioFraming.writeFrame(out, AudioFraming.encodeText(command));
                if (!reader.readNext()) return last;
                if (reader.lastKind() == FrameKind.TEXT) {
                    last = reader.readText();
                    // 拒绝之后服务端会关连接，后面的命令不必再发
                    if (last.startsWith("/Reject/")) return last;
                }
            }
            return last;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 测试收尾
            }
        }
    }

    /**
     * 断言这是一条握手应答，并把对端名字按 C# 的口径解回来。
     *
     * 关键是那条「不能出现 '+'」：C# 端用 {@code Uri.UnescapeDataString} 解转义，它**不把**
     * '+' 当空格。若这里用了 {@code URLEncoder}（表单编码），空格会变成 '+'，对端解出来就是一个
     * 字面的加号——设备名里凭空多一个 +，现场极难看出是编码问题。
     */
    private static void assertHelloReply(String reply) {
        assertNotNull("没有收到握手应答", reply);
        assertTrue("握手应答不是回 Hello：" + reply, reply.startsWith("/Hello/" + SERVER_ID + "/"));
        assertFalse("转义里出现了 '+'，说明用的是表单编码而不是 Uri.EscapeDataString：" + reply,
                reply.contains("+"));
        String name = reply.substring(("/Hello/" + SERVER_ID + "/").length());
        assertEquals(SERVER_NAME, unescape(name));
    }

    private static void assertReject(String reply, String reason) {
        assertNotNull("没有收到拒绝", reply);
        assertTrue("拒绝原因不是 " + reason + "：" + reply, reply.startsWith("/Reject/" + reason));
    }

    /** 按 C# 端的口径解转义（{@code Uri.UnescapeDataString} 不把 '+' 当空格，测试里也不该当）。 */
    private static String unescape(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        } catch (IllegalArgumentException e) {
            // 转义不合法时 URLDecoder 会抛：原样返回，让断言的消息里看得见原始文本
            return text;
        }
    }
}
