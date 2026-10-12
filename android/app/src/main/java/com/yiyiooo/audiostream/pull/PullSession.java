package com.yiyiooo.audiostream.pull;

import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.protocol.AudioFraming;
import com.yiyiooo.audiostream.protocol.FrameKind;
import com.yiyiooo.audiostream.protocol.FrameReader;
import com.yiyiooo.audiostream.protocol.WaveFormatCodec;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条拉取会话：连上对端、报身份、点单、收音频。
 *
 * 与 C# 端 {@code NetAudioRedirector} 的流程逐条对齐，顺序不能换：
 * 连接（端口区间逐个试）→ /Hello 握手 → /WaveFormat 点单并收格式 → /Start → 收音频帧，
 * 期间每 10 秒 /Ping，退出时 /Pause。
 *
 * 这个类只管「把字节收下来」，不碰声卡：收到的 PCM 原样交给 {@link Listener#onAudio}，
 * 由播放那一层去缓冲与出声。这样协议层可以在 JVM 上用假服务端完整测，不必有设备。
 */
public final class PullSession implements Closeable {

    /** 对端音频端口的起点，与 C# 端 TcpServer.PortStart 一致。 */
    public static final int DEFAULT_TCP_PORT = 12670;

    /** 端口区间上界（不含）。C# 端被占用时从 12670 顺延到 12689。 */
    public static final int PORT_END = 12690;

    /** 单个端口的连接超时。同一个网段里通就是几毫秒，不通就是不通，不必按系统默认的二十来秒干等。 */
    private static final int CONNECT_TIMEOUT_MS = 400;

    /** 握手与点单阶段的读超时。 */
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;

    /** 心跳间隔，与 C# 端一致（第一次 1 秒后发，之后每 10 秒）。 */
    private static final long PING_FIRST_DELAY_MS = 1000;
    private static final long PING_INTERVAL_MS = 10000;

    /** 接收缓冲 1 MB、发送缓冲 128 KB，与 C# 端同一组值。 */
    private static final int RECEIVE_BUFFER = 1024 * 1024;
    private static final int SEND_BUFFER = 128 * 1024;

    /**
     * 会话把每一件事都告诉它。三个回调都在后台线程上被调用，
     * 实现者自己保证线程安全，并且**不要在里面做耗时的事**（音频回调直接决定丢包）。
     */
    public interface Listener {

        /** 状态变了。detail 是给人看的一句中文，失败时就是失败原因。 */
        void onState(PullState state, String detail);

        /** 点单成功，对端给的音频格式。 */
        void onFormat(AudioFormat format);

        /** 一段音频。data 是复用的缓冲，**出了这个回调就不要再引用**。 */
        void onAudio(byte[] data, int offset, int length);
    }

    private final String host;
    private final int tcpPort;
    private final String deviceId;
    private final String deviceName;
    private final String machineId;
    private final String machineName;
    private final Listener listener;

    private volatile boolean running;
    private volatile PullState state = PullState.IDLE;
    private volatile String stateDetail = PullState.IDLE.label();

    private Socket socket;
    private OutputStream output;
    private Thread readThread;
    private Thread pingThread;

    /** 对端身份，握手时拿到；显示「这条流来自哪台机器」用。 */
    private volatile String remoteMachineId;
    private volatile String remotePcName;

    public PullSession(String host, int tcpPort, String deviceId, String deviceName,
                       String machineId, String machineName, Listener listener) {
        this.host = host;
        this.tcpPort = tcpPort;
        this.deviceId = deviceId;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.machineId = machineId;
        this.machineName = machineName == null ? "" : machineName;
        this.listener = listener;
    }

    public PullState state() {
        return state;
    }

    public String stateDetail() {
        return stateDetail;
    }

    public String remoteMachineId() {
        return remoteMachineId;
    }

    public String remotePcName() {
        return remotePcName;
    }

    /** 起一条后台线程把整条会话跑完；本方法立刻返回。 */
    public void start() {
        if (running) return;
        running = true;
        readThread = new Thread(this::run, "pull-session");
        readThread.setDaemon(true);
        readThread.start();
    }

    private void run() {
        try {
            Socket sock = connectAnyPort();
            if (sock == null) {
                fail("连不上 " + host + " 的 " + portRange() + " 端口，对方可能没开机或没运行 AudioStream。");
                return;
            }
            socket = sock;
            output = sock.getOutputStream();
            // 按帧读的读取器和这里的 socket 同一个生命周期，一条会话一个实例（FrameReader 不是线程安全的）
            FrameReader reader = new FrameReader(sock.getInputStream());

            if (!running) {
                setState(PullState.STOPPED, PullState.STOPPED.label());
                return;
            }

            setState(PullState.HANDSHAKING, PullState.HANDSHAKING.label());
            String reply = handshake(reader);
            if (reply == null) return; // 已经在 handshake 里失败/停掉了

            setState(PullState.NEGOTIATING, PullState.NEGOTIATING.label());
            AudioFormat format = negotiateFormat(reader);
            if (format == null) return;

            // 音频一开动就是连续流，不能再让读操作因为「一时没数据」超时退出
            try {
                sock.setSoTimeout(0);
            } catch (IOException ignored) {
            }
            notifyFormat(format);

            sendText("/Start");
            setState(PullState.PLAYING, PullState.PLAYING.label());
            startPing();

            readAudioLoop(reader);
        } catch (Exception e) {
            if (running) {
                fail(describe(e));
            }
        } finally {
            cleanup();
        }
    }

    /** 返回对端回的握手文本（已经确认不是拒绝），失败或已停时返回 null。 */
    private String handshake(FrameReader reader) throws IOException {
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        // 报身份时名字要转义：里面可能有空格、中文、斜杠（对端用 Uri.UnescapeDataString 解回来）
        sendText("/Hello/" + machineId + "/" + RejectText.escapeDataString(machineName));
        FrameKind kind;
        int count;
        if (!reader.readNext()) {
            fail("刚连上就被断开了：对方可能正在退出。");
            return null;
        }
        kind = reader.lastKind();
        count = reader.lastCount();
        if (kind != FrameKind.TEXT) {
            fail("对端的握手应答不是文本帧，可能不是本程序，或者版本不一致。");
            return null;
        }
        String reply = reader.readText();
        if (reply.startsWith("/Reject/")) {
            fail(RejectText.describe(reply));
            return null;
        }
        if (reply.startsWith("/Hello/")) {
            // /Hello/<机器号>/<名字>：名字里可能带斜杠，所以切 4 段，第 4 段整段当名字
            // （与 C# 端 reply.Split('/', 4) 同一口径，段号也一致：2 是机器号、3 是名字）
            String[] parts = reply.split("/", 4);
            remoteMachineId = parts.length > 2 ? parts[2] : null;
            remotePcName = parts.length > 3 ? decodeQuietly(parts[3]) : null;
        } else if (!reply.isEmpty()) {
            // 认不出的应答按继续处理：对端可能是个只会回别的话的旧版本
            remotePcName = null;
        }
        return reply;
    }

    /** 点单并等对端给格式。失败返回 null（原因已经报给 listener 了）。 */
    private AudioFormat negotiateFormat(FrameReader reader) throws IOException {
        // 设备名**不转义**，与 C# 端 NetAudioRedirector 发出的完全一致：
        // 对端拿第 4 段直接去比对设备名，不解转义。这里跟着它的口径走，别自作聪明。
        sendText("/WaveFormat/" + deviceId + "/" + deviceName);
        if (!reader.readNext()) {
            fail("对端还没给出音频格式就把连接断开了。");
            return null;
        }
        FrameKind kind = reader.lastKind();
        int count = reader.lastCount();
        if (kind == FrameKind.TEXT) {
            String text = reader.readText().trim();
            if (text.startsWith("/Reject/")) {
                fail(RejectText.describe(text));
            } else {
                // 把一串字符当采样率解析下去，最后会拿着一个垃圾格式硬播，现场完全看不出原因
                fail("对端没有给出音频格式，回的是：" + text);
            }
            return null;
        }
        if (kind != FrameKind.WAVE_FORMAT || count <= 0) {
            fail("对端回的帧类型不对：期望音频格式，收到 " + (kind == null ? "未知类型" : kind.name()));
            return null;
        }
        AudioFormat format = WaveFormatCodec.parse(reader.buffer(), count);
        if (format == null) {
            fail("对端给的音频格式看不懂（" + count + " 字节负载）。");
            return null;
        }
        return format;
    }

    private void readAudioLoop(FrameReader reader) throws IOException {
        while (running) {
            if (!reader.readNext()) break;
            FrameKind kind = reader.lastKind();
            int count = reader.lastCount();
            if (kind == FrameKind.TEXT) {
                // /Pong 之类的应答，收到就说明连接还活着，内容不必处理
                continue;
            }
            if (kind != FrameKind.AUDIO || count <= 0) continue;
            listener.onAudio(reader.buffer(), 0, count);
        }
        if (running) {
            // 对方先挂了。这条不叫「错误」叫「断了」，但用户要看到的是同一件事：为什么没声音了
            fail("对端把连接断开了：对方程序退出、网线断了、或者它那边的设备被拔了。");
        } else {
            setState(PullState.STOPPED, PullState.STOPPED.label());
        }
    }

    /** 心跳：发不出去就说明连接已经没了，读循环马上也会发现，这里不必再刷一条日志。 */
    private void startPing() {
        pingThread = new Thread(() -> {
            try {
                Thread.sleep(PING_FIRST_DELAY_MS);
                while (running) {
                    try {
                        sendText("/Ping");
                    } catch (IOException e) {
                        return;
                    }
                    Thread.sleep(PING_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pull-ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    /**
     * 按端口区间逐个试。优先用对端自述的端口（它可能被占用后顺延过），不行再扫整个区间。
     */
    private Socket connectAnyPort() {
        for (int port : candidatePorts()) {
            if (!running) return null;
            Socket sock = null;
            try {
                sock = new Socket();
                sock.setTcpNoDelay(true);
                try {
                    sock.setReceiveBufferSize(RECEIVE_BUFFER);
                    sock.setSendBufferSize(SEND_BUFFER);
                } catch (IOException ignored) {
                    // 缓冲大小只是性能参数，设不上不该拦住连接
                }
                sock.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                return sock;
            } catch (Exception e) {
                if (sock != null) {
                    try {
                        sock.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private List<Integer> candidatePorts() {
        List<Integer> ports = new ArrayList<>();
        if (tcpPort > 0 && tcpPort < 65536) ports.add(tcpPort);
        for (int port = DEFAULT_TCP_PORT; port < PORT_END; port++) {
            if (!ports.contains(port)) ports.add(port);
        }
        return ports;
    }

    private String portRange() {
        return DEFAULT_TCP_PORT + "-" + (PORT_END - 1);
    }

    /** 文本命令。连接上只有握手、心跳与退场会写，所以直接同步写即可，不存在并发写。 */
    private synchronized void sendText(String text) throws IOException {
        OutputStream out = output;
        if (out == null) throw new IOException("连接已经关了");
        AudioFraming.writeFrame(out, AudioFraming.encodeText(text));
    }

    private static String decodeQuietly(String text) {
        try {
            return java.net.URLDecoder.decode(text, "UTF-8");
        } catch (Exception e) {
            // 名字只用于显示，解不出来就原样留着
            return text;
        }
    }

    private void notifyFormat(AudioFormat format) {
        try {
            listener.onFormat(format);
        } catch (Exception ignored) {
            // 播放那一层自己的问题，不该把协议层带下去
        }
    }

    private void setState(PullState next, String detail) {
        state = next;
        stateDetail = detail;
        try {
            listener.onState(next, detail);
        } catch (Exception ignored) {
        }
    }

    private void fail(String message) {
        if (!running && state.isTerminal()) return;
        running = false;
        setState(PullState.FAILED, message);
    }

    /** 把异常翻成能看懂的中文。 */
    private String describe(Exception e) {
        if (e instanceof UnknownHostException) {
            return "找不到主机 " + host + "：检查 IP 有没有写错。";
        }
        if (e instanceof ConnectException) {
            return "连不上 " + host + " 的 " + portRange() + " 端口，对方可能没开机或没运行 AudioStream。";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "对端 " + host + " 一直没有回应（超时）。检查对方是否开着、防火墙是否放行。";
        }
        String message = e.getMessage();
        return "连接出错：" + (message == null || message.isEmpty() ? e.getClass().getSimpleName() : message);
    }

    /** 主动收工：告诉对端一声，让它早点把采集放掉，然后断开。 */
    @Override
    public void close() {
        boolean wasRunning = running;
        running = false;
        try {
            if (wasRunning) sendText("/Pause");
        } catch (Exception ignored) {
            // 失败清理时连接本来就是坏的，不必再报
        }
        cleanup();
        if (state != PullState.FAILED) {
            setState(PullState.STOPPED, PullState.STOPPED.label());
        }
        Thread thread = pingThread;
        if (thread != null) thread.interrupt();
    }

    private void cleanup() {
        Socket sock = socket;
        socket = null;
        output = null;
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 供测试与界面判断「这条会话还活着吗」。 */
    public boolean isRunning() {
        return running;
    }
}
