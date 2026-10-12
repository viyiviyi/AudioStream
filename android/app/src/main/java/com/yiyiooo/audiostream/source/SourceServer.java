package com.yiyiooo.audiostream.source;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.BindException;
import java.util.List;

/**
 * 手机当来源端的整体：配置接口（HTTP 12570）+ 音频服务（TCP 12670-12689）+ 一路麦克风来源。
 *
 * 这一层只负责「一起起来、一起停掉、把状态报给上层」，协议细节都在
 * {@link SourceHttpServer} 与 {@link AudioTcpServer} 里，采集在 {@link AudioSourceProvider} 后面。
 *
 * 两件事它**不**做，都是有意的：
 * <ul>
 *   <li>不管麦克风的释放。来源提供方可能被上层复用，谁建谁关（见 {@code MicrophoneSourceProvider}）。</li>
 *   <li>不主动回调界面。界面上那行状态是周期性读 {@link #statusLine()} 的，
 *       为每一次「有人连上了」都推一次消息，只会让界面比真实情况慢半拍。</li>
 * </ul>
 */
public final class SourceServer implements Closeable {

    private static final String TAG = "AudioStream";

    /** 配置接口端口。PC 端 HttpServer 里写死了 12570，改这里就等于两端对不上。 */
    public static final int HTTP_PORT = 12570;

    private final AudioSourceProvider provider;
    private final String machineId;
    private final String machineName;
    private final String version;
    private final int httpPort;

    private SourceHttpServer http;
    private AudioTcpServer tcp;
    private volatile boolean running;
    private volatile String lastError;

    /**
     * @param httpPort 配置接口端口；传 0 让系统分配（单元测试用）。正式运行传 {@link #HTTP_PORT}。
     */
    public SourceServer(AudioSourceProvider provider, String machineId, String machineName,
                        String version, int httpPort) {
        this.provider = provider;
        this.machineId = machineId;
        this.machineName = machineName;
        this.version = version;
        this.httpPort = httpPort;
    }

    /**
     * 起服务。成功返回 null，失败返回一句面向用户的中文原因。
     *
     * 顺序是**先音频口、后配置口**：{@code /api/info} 里的 TcpPort 必须是实际值
     * （12670 被占会顺延到 12689 中的某一个），先起配置口的话，第一次被访问时还不知道音频口是几号。
     */
    public synchronized String start() {
        if (running) return null;

        AudioTcpServer audio = new AudioTcpServer(machineId, machineName, provider);
        try {
            audio.start();
        } catch (IOException e) {
            lastError = audioFailure(e);
            Log.w(TAG, "来源端音频服务起不来：" + e);
            return lastError;
        }
        tcp = audio;

        SourceHttpServer web = new SourceHttpServer(httpPort, new SourceHttpServer.Content() {
            @Override
            public String infoJson() {
                AudioTcpServer server = tcp;
                SourceHttpServer self = http;
                if (server == null || self == null) {
                    return SourceJson.error(503, "来源端正在启动或已经停止");
                }
                return SourceJson.info(machineId, machineName, server.boundPort(), self.boundPort(),
                        version, LocalAddresses.list());
            }

            @Override
            public String devicesJson() {
                return SourceJson.devices(SourceDevices.list());
            }
        });
        try {
            web.start();
        } catch (IOException e) {
            // 音频口已经开着了，这里必须收掉：留着它等于开了一个没人能用的口
            audio.close();
            tcp = null;
            lastError = "配置接口端口 " + httpPort + " 用不了（被别的程序占了，或者系统不允许）：" + message(e);
            Log.w(TAG, "来源端配置接口起不来：" + e);
            return lastError;
        }
        http = web;
        running = true;
        lastError = null;
        Log.i(TAG, "来源端已启动：配置 " + http.boundPort() + "，音频 " + tcp.boundPort());
        return null;
    }

    @Override
    public synchronized void close() {
        running = false;
        SourceHttpServer web = http;
        http = null;
        if (web != null) web.close();
        AudioTcpServer audio = tcp;
        tcp = null;
        if (audio != null) audio.close();
        Log.i(TAG, "来源端已停止");
    }

    public boolean isRunning() {
        return running;
    }

    /** 配置接口端口；没起来时为 0。 */
    public int httpPort() {
        SourceHttpServer server = http;
        return server == null ? 0 : server.boundPort();
    }

    /** 音频端口；没起来时为 0。 */
    public int tcpPort() {
        AudioTcpServer server = tcp;
        return server == null ? 0 : server.boundPort();
    }

    /** 此刻有几台设备在拉。 */
    public int sessionCount() {
        AudioTcpServer server = tcp;
        return server == null ? 0 : server.sessionCount();
    }

    /** 从启动到现在一共被连过几次（含已经结束的）。 */
    public int totalSessions() {
        AudioTcpServer server = tcp;
        return server == null ? 0 : server.totalSessions();
    }

    /** 起不来时的那句原因；没出错时为 null。 */
    public String lastError() {
        return lastError;
    }

    /** 现在谁在拉。没人拉时返回空串。 */
    public String peersSummary() {
        AudioTcpServer server = tcp;
        if (server == null) return "";
        List<SourceSession> sessions = server.sessions();
        StringBuilder out = new StringBuilder();
        for (SourceSession session : sessions) {
            if (session.isClosed()) continue;
            String name = session.remoteMachineName();
            if (name == null || name.isEmpty()) name = session.remoteMachineId();
            if (name == null || name.isEmpty()) name = "未知设备";
            if (out.length() > 0) out.append('、');
            out.append(name);
        }
        return out.toString();
    }

    /** 界面上那一行状态。 */
    public String statusLine() {
        if (!running) {
            return lastError == null ? "来源端：未开启" : "来源端：起不来 —— " + lastError;
        }
        int sessions = sessionCount();
        String peers = peersSummary();
        return "来源端：监听 " + httpPort() + "（配置）/ " + tcpPort() + "（音频）"
                + (sessions == 0 ? "　没有设备在拉"
                : "　被 " + sessions + " 台拉取" + (peers.isEmpty() ? "" : "：" + peers));
    }

    private String audioFailure(IOException e) {
        if (e instanceof BindException) {
            return "音频端口 " + AudioTcpServer.PORT_START + "-" + (AudioTcpServer.PORT_END - 1)
                    + " 全被占用了，没法定给 PC 送声音。";
        }
        return "音频服务起不来：" + message(e);
    }

    private static String message(IOException e) {
        String text = e.getMessage();
        return text == null || text.isEmpty() ? e.getClass().getSimpleName() : text;
    }
}
