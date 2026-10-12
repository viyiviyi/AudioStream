package com.yiyiooo.audiostream.source;

import android.util.Log;

import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.protocol.AudioFraming;
import com.yiyiooo.audiostream.protocol.FrameKind;
import com.yiyiooo.audiostream.protocol.FrameReader;
import com.yiyiooo.audiostream.protocol.WaveFormatCodec;
import com.yiyiooo.audiostream.pull.RejectText;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.util.ArrayDeque;

/**
 * 被拉取侧的一条会话：PC 端连上来之后，从 /Hello 握手到 /Pause 收工的全过程。
 *
 * 与 C# 端 {@code TcpServer} 的处理顺序逐条对齐：/Hello（认出是自己就拒）→ /WaveFormat 点单并回格式帧
 * → /Start 开始送音频 → 期间应答 /Ping → /Pause 收工。少一步或者换一步，对端就会停在某个中间状态，
 * 表现是「连上了但没声音」。
 *
 * 两条线程，各管一头：
 * <ul>
 *   <li>读线程（{@link #run}）：只解读到的命令，写完应答就回去继续读，不在里面做任何耗时的事。</li>
 *   <li>发送线程（{@link #sendLoop}）：只从队列里取音频帧往 socket 写。采集回调因此永远不会被
 *       网络堵住——对方一时收不动，积压的是这条队列，丢的也只是最旧的音频帧。</li>
 * </ul>
 * 控制帧（握手、格式、拒绝、Pong）一律同步写：它们必须真的发出去，特别是拒绝——
 * 先发出去再关连接，对端才能看到原因，否则它只看到「连接被重置」。
 */
public final class SourceSession implements Runnable, AudioSource.Sink, Closeable {

    private static final String TAG = "AudioStream";

    /** 对端超过这个时间没发过任何命令就判掉线。PC 端每 10 秒一次 /Ping，20 秒足够宽裕。 */
    static final int COMMAND_TIMEOUT_MS = 20000;

    /** 距上次向对端发送超过这个时间，/Ping 才回 /Pong；正发着音频的时候别去挤发送权。 */
    private static final long PONG_QUIET_MS = 10000;

    /**
     * 一条会话允许积压的音频字节数。音频是连续的，对端一时收不动时，积压超过这个量就丢最旧的：
     * 宁可让对端听到一段缺口，也不能让队列一直长下去（那等于把延迟越堆越高）。
     */
    static final int AUDIO_QUEUE_LIMIT = 64 * 1024;

    private final Socket socket;
    private final String machineId;
    private final String machineName;
    private final AudioSourceProvider provider;
    private final AudioTcpServer owner;

    private final Object writeLock = new Object();
    private final Object queueLock = new Object();
    private final ArrayDeque<byte[]> audioQueue = new ArrayDeque<byte[]>();

    private volatile boolean running = true;
    private volatile boolean closed;
    private OutputStream output;
    private FrameReader reader;
    private Thread sender;

    private volatile AudioSource source;
    private volatile AudioFormat sentFormat;

    private volatile String remoteMachineId = "";
    private volatile String remoteMachineName = "";
    private volatile long lastSentAt;
    private volatile long audioFrames;
    private volatile long audioBytes;
    private volatile long droppedFrames;
    private volatile String closeNote = "";
    private int queuedBytes;

    SourceSession(Socket socket, String machineId, String machineName,
                  AudioSourceProvider provider, AudioTcpServer owner) {
        this.socket = socket;
        this.machineId = machineId == null ? "" : machineId;
        this.machineName = machineName == null ? "" : machineName;
        this.provider = provider;
        this.owner = owner;
    }

    // ========== 读线程 ==========

    @Override
    public void run() {
        try {
            prepare();
            while (running) {
                if (!reader.readNext()) break;
                // 非文本帧忽略：对端是拉取方，只会发命令；真收到音频帧说明对端不是本程序，
                // 但按帧读位置还是对的，丢掉继续读即可
                if (reader.lastKind() != FrameKind.TEXT) continue;
                String text = reader.readText();
                if (text.isEmpty()) continue;
                if (!handle(text)) break;
            }
        } catch (SocketTimeoutException e) {
            closeNote = "对端 " + (COMMAND_TIMEOUT_MS / 1000) + " 秒没有发过任何命令，判为掉线";
        } catch (IOException e) {
            if (running) closeNote = "连接出错：" + describe(e);
        } finally {
            close();
        }
    }

    private void prepare() throws IOException {
        socket.setTcpNoDelay(true);
        try {
            // 与 C# 端同一组值：音频是单向的大流量，发送缓冲给大，接收只有几条命令，给小
            socket.setReceiveBufferSize(32 * 1024);
            socket.setSendBufferSize(512 * 1024);
        } catch (IOException ignored) {
            // 缓冲大小只是性能参数，设不上不该拦住会话
        }
        socket.setSoTimeout(COMMAND_TIMEOUT_MS);
        output = socket.getOutputStream();
        reader = new FrameReader(socket.getInputStream());
    }

    /** @return false 表示这条会话该结束了 */
    private boolean handle(String text) throws IOException {
        if (text.startsWith("/Hello/")) return onHello(text);
        if (text.startsWith("/WaveFormat/")) return onWaveFormat(text);
        if ("/Start".equals(text)) {
            onStart();
            return true;
        }
        if ("/Pause".equals(text)) {
            closeNote = "对端收工（/Pause）";
            return false;
        }
        if ("/Ping".equals(text)) {
            onPing();
            return true;
        }
        // 认不出的命令不是错误：新旧版本之间必然有对方独有的话，位置还是对的，忽略即可
        Log.d(TAG, "来源端会话收到认不出的命令：" + text);
        return true;
    }

    /**
     * 握手。对端报上它的身份，我们回自己的；发现它点的就是它自己，说明这条流会绕回本机成环。
     */
    private boolean onHello(String text) throws IOException {
        // /Hello/<机器号>/<名字>：名字里可能有斜杠，所以切成 4 段、第 4 段整段当名字
        String[] parts = text.split("/", 4);
        String remoteId = parts.length > 2 ? parts[2].trim() : "";
        String remoteName = parts.length > 3 ? decodeQuietly(parts[3]) : "";

        if (!remoteId.isEmpty() && remoteId.equalsIgnoreCase(machineId)) {
            sendControl(AudioFraming.encodeText("/Reject/self"));
            closeNote = "对端点的是它自己（" + remoteId + "），拒掉这条必然成环的流";
            return false;
        }

        remoteMachineId = remoteId;
        remoteMachineName = remoteName;
        sendControl(AudioFraming.encodeText("/Hello/" + machineId + "/" + escape(machineName)));
        return true;
    }

    /**
     * 点单：对端要哪个来源。成功就立刻回一条格式帧——**不能等到 /Start**，
     * 对端在等这条帧来决定怎么建播放器，等不到就一直是「正在协商」。
     */
    private boolean onWaveFormat(String text) throws IOException {
        String[] parts = text.split("/", 4);
        String requestedId = parts.length > 2 ? parts[2].trim() : "";
        String requestedName = parts.length > 3 ? parts[3].trim() : "";

        // 同一条会话上重复点单：先把上一路退掉，否则两路采集会叠在一起发过去
        dropSource();

        AudioSource picked;
        try {
            if (SourceDevices.isMicrophone(requestedId)) {
                picked = provider.microphone();
            } else if (SourceDevices.isCapture(requestedId)) {
                picked = provider.playbackCapture();
            } else {
                // 说明白「要的是什么、这台机器有什么」，否则用户在 PC 端只看到一句设备不存在
                String wanted = requestedName.isEmpty() ? requestedId : requestedName;
                reject("nodevice", "手机端没有这个来源设备（要的是「" + wanted + "」），目前只有 "
                        + SourceDevices.availableSummary());
                return false;
            }
        } catch (SourceUnavailableException e) {
            reject(e.reason(), e.getMessage());
            return false;
        }

        AudioFormat format = picked.format();
        sendControl(AudioFraming.encodeWaveFormat(WaveFormatCodec.encode(format)));
        source = picked;
        sentFormat = format;
        Log.d(TAG, "来源端会话点单成功：" + format.describe() + "，等 /Start");
        return true;
    }

    private void onStart() throws IOException {
        AudioSource audio = source;
        if (audio == null) {
            // 没点单就要开始：这是对端的顺序错了，回一句让它看得见，别默默什么都不发
            sendControl(AudioFraming.encodeText("/Reject/nodevice/还没有点单就要开始"));
            return;
        }
        startSender();
        // addSink 会把采集真正启动（第一个订阅者）。放在发送线程起来之后，
        // 免得第一帧数据还没人接
        audio.addSink(this);
        Log.d(TAG, "来源端会话开始送音频");
    }

    private void onPing() throws IOException {
        // 对端每 10 秒问一次。只有这段时间我们确实没发过东西才回：
        // 正在送音频的时候插一条 Pong 没有必要，反而多占一次发送
        if (System.currentTimeMillis() - lastSentAt >= PONG_QUIET_MS) {
            sendControl(AudioFraming.encodeText("/Pong"));
        }
    }

    // ========== AudioSource.Sink（采集线程上调用） ==========

    @Override
    public void onAudio(byte[] data, int offset, int length) {
        if (!running || length <= 0) return;
        // encode 会把这段字节拷进新数组，这是必须的：data 是采集侧复用的缓冲，
        // 不拷就等于把后面被覆盖掉的数据当成音频发出去（听感是持续的杂音）
        byte[] frame = AudioFraming.encode(FrameKind.AUDIO, data, offset, length);
        synchronized (queueLock) {
            audioQueue.addLast(frame);
            queuedBytes += frame.length;
            while (queuedBytes > AUDIO_QUEUE_LIMIT && !audioQueue.isEmpty()) {
                byte[] dropped = audioQueue.pollFirst();
                if (dropped == null) break;
                queuedBytes -= dropped.length;
                droppedFrames++;
            }
            queueLock.notifyAll();
        }
        audioFrames++;
        audioBytes += length;
    }

    // ========== 发送线程 ==========

    private void startSender() {
        if (sender != null) return;
        sender = new Thread(this::sendLoop, "source-send");
        sender.setDaemon(true);
        sender.start();
    }

    private void sendLoop() {
        while (running) {
            byte[] frame = takeAudioFrame();
            if (frame == null) return;
            try {
                sendControl(frame);
            } catch (IOException e) {
                // 对端走了。读线程马上也会发现，这里不重复报
                close();
                return;
            }
        }
    }

    /** 取一帧音频，没有就等。返回 null 表示会话结束。 */
    private byte[] takeAudioFrame() {
        synchronized (queueLock) {
            while (running && audioQueue.isEmpty()) {
                try {
                    queueLock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (!running) return null;
            byte[] frame = audioQueue.pollFirst();
            if (frame != null) queuedBytes -= frame.length;
            return frame;
        }
    }

    /** 写一帧。所有往这条连接写的地方都走它，控制帧与音频帧因此不会交错撕开。 */
    private void sendControl(byte[] frame) throws IOException {
        synchronized (writeLock) {
            OutputStream out = output;
            if (out == null) throw new IOException("连接已经关了");
            AudioFraming.writeFrame(out, frame);
            lastSentAt = System.currentTimeMillis();
        }
    }

    private void reject(String reason, String message) throws IOException {
        String text = message == null || message.isEmpty() ? reason : message;
        sendControl(AudioFraming.encodeText("/Reject/" + reason + "/" + escape(text)));
        closeNote = "已拒绝（" + reason + "）：" + text;
        Log.d(TAG, "来源端会话拒绝点单：" + reason + "：" + text);
    }

    // ========== 收尾 ==========

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        running = false;
        dropSource();

        synchronized (queueLock) {
            audioQueue.clear();
            queuedBytes = 0;
            queueLock.notifyAll();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 收尾时关不掉没什么可做的
        }
        Thread thread = sender;
        sender = null;
        if (thread != null) thread.interrupt();

        AudioTcpServer server = owner;
        if (server != null) server.onSessionClosed(this);
        Log.d(TAG, "来源端会话结束：" + (remoteMachineName.isEmpty() ? remoteMachineId : remoteMachineName)
                + "，" + closeNote + "，送音频 " + audioFrames + " 帧 / " + audioBytes + " 字节"
                + (droppedFrames > 0 ? "，积压丢弃 " + droppedFrames + " 帧" : ""));
    }

    private void dropSource() {
        AudioSource audio = source;
        source = null;
        if (audio != null) {
            try {
                audio.removeSink(this);
            } catch (Exception ignored) {
                // 退订失败不该拦住会话收尾
            }
        }
    }

    // ========== 给上层与测试看的状态 ==========

    public String remoteMachineId() {
        return remoteMachineId;
    }

    public String remoteMachineName() {
        return remoteMachineName;
    }

    /** 这次会话给出去的格式；还没点单时为 null。 */
    public AudioFormat sentFormat() {
        return sentFormat;
    }

    /** 已经送出去的音频帧数与字节数。 */
    public long audioFrames() {
        return audioFrames;
    }

    public long audioBytes() {
        return audioBytes;
    }

    /** 因为对端收不动而丢掉的音频帧数。 */
    public long droppedFrames() {
        return droppedFrames;
    }

    /** 这条会话为什么结束。 */
    public String closeNote() {
        return closeNote;
    }

    public boolean isClosed() {
        return closed;
    }

    // ========== 工具 ==========

    /**
     * 名字与原因可能带空格、中文、斜杠，进 URL 段之前必须转义（对端用 {@code Uri.UnescapeDataString} 解回来）。
     *
     * 这里刻意复用拉取方那份 {@link RejectText#escapeDataString}，而不是就地再写一个：
     * 它按 RFC 3986 编，空格是 %20，与 C# 的 {@code Uri.EscapeDataString} 同口径。
     * 尤其不能用 {@code URLEncoder}——它把空格编成 '+'，而对端的 {@code Uri.UnescapeDataString}
     * 不把 '+' 当空格，解出来就是一个字面的加号（设备名里凭空多一个 +，现场极难查出来是编码问题）。
     */
    private static String escape(String text) {
        return RejectText.escapeDataString(text);
    }

    private static String decodeQuietly(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (Exception e) {
            // 名字只用于显示与日志，解不出来就原样留着
            return text;
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
