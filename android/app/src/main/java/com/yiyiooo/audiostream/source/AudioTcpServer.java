package com.yiyiooo.audiostream.source;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手机当来源端时的音频 TCP 服务。
 *
 * PC 端是从 12670 起逐个端口试过来的（它不知道我们最终绑到了哪个，除非问 HTTP 的 /api/info），
 * 所以这里也从 12670 开始往 12689 顺延，与 C# 端 TcpServer 的区间一致。
 *
 * 一条连接一个 {@link SourceSession}，各自两条线程。音频来源由 {@link AudioSourceProvider} 提供，
 * 多个会话拉同一个麦克风时共享同一路采集。
 */
public final class AudioTcpServer implements Closeable {

    private static final String TAG = "AudioStream";

    /** 与 C# 端 TcpServer.PortStart / PortEnd 同一区间（PortEnd 不含）。 */
    public static final int PORT_START = 12670;
    public static final int PORT_END = 12690;

    private final String machineId;
    private final String machineName;
    private final AudioSourceProvider provider;
    private final int portStart;
    private final int portEnd;

    private final List<SourceSession> sessions = new CopyOnWriteArrayList<SourceSession>();
    private final AtomicInteger totalSessions = new AtomicInteger();

    private ServerSocket server;
    private Thread acceptor;
    private volatile boolean running;
    private volatile int boundPort;

    public AudioTcpServer(String machineId, String machineName, AudioSourceProvider provider) {
        this(machineId, machineName, provider, PORT_START, PORT_END);
    }

    /**
     * @param portStart 起始端口；传 0 表示让系统随便给一个（单元测试用）
     * @param portEnd   上界，不含
     */
    public AudioTcpServer(String machineId, String machineName, AudioSourceProvider provider,
                          int portStart, int portEnd) {
        this.machineId = machineId == null ? "" : machineId;
        this.machineName = machineName == null ? "" : machineName;
        this.provider = provider;
        this.portStart = portStart;
        this.portEnd = Math.max(portEnd, portStart + 1);
    }

    /** 实际监听的端口；还没起来时为 0。 */
    public int boundPort() {
        return boundPort;
    }

    /**
     * 在区间里找一个能绑的端口并开始接受连接，返回实际端口。
     *
     * 整个区间都被占则抛 IOException：手机上 12670-12689 全满几乎不可能，
     * 真发生了也该让用户看到，而不是悄悄退到一个 PC 端永远不会去试的端口上。
     */
    public int start() throws IOException {
        if (running) return boundPort;
        IOException last = null;
        for (int port = portStart; port < portEnd; port++) {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
            } catch (IOException e) {
                last = e;
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 绑定失败后关不关都不影响下一步
                }
                continue;
            }
            server = socket;
            // 要问 socket 要真实端口：传 0 进来是「让系统随便给一个」，循环变量这时候还是 0，
            // 拿它当结果会让调用方去连 0 号端口（Windows 上报的是 Cannot assign requested address）
            boundPort = socket.getLocalPort();
            running = true;
            acceptor = new Thread(this::acceptLoop, "source-accept");
            acceptor.setDaemon(true);
            acceptor.start();
            Log.d(TAG, "来源端音频服务已监听 " + boundPort);
            return boundPort;
        }
        throw new IOException("音频端口 " + portStart + "-" + (portEnd - 1) + " 全被占用了，换不了",
                last);
    }

    @Override
    public void close() {
        running = false;
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关监听失败没什么可做的
            }
        }
        for (SourceSession session : new ArrayList<SourceSession>(sessions)) {
            session.close();
        }
        sessions.clear();
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket socket = server;
            if (socket == null) break;
            try {
                Socket client = socket.accept();
                SourceSession session = new SourceSession(client, machineId, machineName, provider, this);
                sessions.add(session);
                totalSessions.incrementAndGet();
                Thread thread = new Thread(session, "source-session-" + totalSessions.get());
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                // 关监听会让 accept 立刻抛出来，这是正常退出路径；其它情况单条连接不该带走监听
                if (!running) break;
            }
        }
    }

    /** 会话收尾时回调，把它从名单里去掉；否则长时间运行会一直攒着死会话。 */
    void onSessionClosed(SourceSession session) {
        sessions.remove(session);
    }

    /** 此刻还连着的会话数。 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 服务起来之后一共接过多少条会话。 */
    public int totalSessions() {
        return totalSessions.get();
    }

    /** 此刻的会话名单（只读快照），给界面显示「谁在拉」用。 */
    public List<SourceSession> sessions() {
        return new ArrayList<SourceSession>(sessions);
    }
}
