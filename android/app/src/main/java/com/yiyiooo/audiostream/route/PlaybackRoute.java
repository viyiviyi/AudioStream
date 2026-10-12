package com.yiyiooo.audiostream.route;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.yiyiooo.audiostream.player.AudioSinkFactory;
import com.yiyiooo.audiostream.player.PlaybackSession;
import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.pull.PullSession;
import com.yiyiooo.audiostream.pull.PullState;

/**
 * 运行时的一路：一条拉取会话 + 一块声卡 + 它自己的重连节奏。
 *
 * 与 {@link RouteConfig} 的分工是「此刻正在发生什么」对「用户配了什么」。
 *
 * 每一路自己退避、自己重连、自己记状态，互不干涉——一路的电脑关机了，不该让另一路的声音
 * 跟着断一下，也不该把另一路的退避计时重置掉。原来单路时这些状态都摆在服务上，
 * 多路之后它们必须跟着这一路走，否则「两路同时重连」会互相踩。
 *
 * 音量挂在这一路而不是服务上：每路一块 AudioTrack、各带自己的增益（见 AudioTrackSink），
 * 所以「这路小一点、那路大一点」是天然支持的，不需要自己混音。
 */
public final class PlaybackRoute {

    private static final String TAG = "AudioStream";

    private static final long RECONNECT_BASE_MS = 1000L;
    private static final long RECONNECT_CAP_MS = 30000L;

    /** 这一路的状态与细节变了。回调在两个后台线程上（拉取线程、写线程），实现者自己保证线程安全。 */
    public interface Listener {
        void onState(PlaybackRoute route, PullState state, String detail);

        /** 声卡建不起来这类硬失败，原因已经是中文。与状态分开是因为它要单独提醒用户。 */
        void onPlaybackFailed(PlaybackRoute route, String reason);
    }

    private final AudioSinkFactory sinkFactory;
    private final String machineId;
    private final String machineName;
    private final Listener listener;

    /** 主线程 Handler 只用来排重连——每路的 Runnable 是各自的，共用这一个不影响隔离性。 */
    private final Handler scheduler = new Handler(Looper.getMainLooper());

    private volatile RouteConfig config;

    private volatile PullSession pull;
    private volatile PlaybackSession playback;

    private volatile PullState state = PullState.IDLE;
    private volatile String stateDetail = PullState.IDLE.label();
    private volatile String lastError;

    /** 这一路是不是被用户主动停了。停了就不再重连，也不再刷新状态。 */
    private volatile boolean closed;

    /** 这一路自己的退避节奏。别的路连上没有都不影响它。 */
    private volatile long reconnectDelay = RECONNECT_BASE_MS;
    private volatile Runnable reconnectTask;

    public PlaybackRoute(RouteConfig config, AudioSinkFactory sinkFactory,
                         String machineId, String machineName, Listener listener) {
        this.config = config;
        this.sinkFactory = sinkFactory;
        this.machineId = machineId;
        this.machineName = machineName;
        this.listener = listener;
    }

    public String id() {
        return config.id();
    }

    public RouteConfig config() {
        return config;
    }

    public PullState state() {
        return state;
    }

    public String stateDetail() {
        return stateDetail;
    }

    public AudioFormat format() {
        // 委托给当前这条播放会话，而不是自己缓存一份。
        // 这里原本有个自己的 format 字段，但没人给它赋过值（PlaybackSession.Listener 只有
        // onState/onPlaybackFailed，没有 onFormat 回调），于是界面上每路的格式永远显示为空。
        // 而 PlaybackSession 本来就存着协商出来的那个格式，直接问它即可，也省掉一处要同步的状态。
        PlaybackSession pb = playback;
        return pb == null ? null : pb.format();
    }

    public String lastError() {
        return lastError;
    }

    public boolean isClosed() {
        return closed;
    }

    // ---------- 计数（界面上的实时数字） ----------

    public int bufferedMs() {
        PlaybackSession pb = playback;
        return pb == null ? 0 : pb.bufferedMs();
    }

    public long underruns() {
        PlaybackSession pb = playback;
        return pb == null ? 0 : pb.underruns();
    }

    public long droppedBytes() {
        PlaybackSession pb = playback;
        return pb == null ? 0 : pb.droppedBytes();
    }

    public long writtenBytes() {
        PlaybackSession pb = playback;
        return pb == null ? 0 : pb.writtenBytes();
    }

    public boolean isPlaying() {
        PlaybackSession pb = playback;
        return pb != null && pb.isPlaying();
    }

    // ---------- 生命周期 ----------

    /** 起这一路。已经在跑或已停的都不动。 */
    public void start() {
        if (closed) return;
        reconnectDelay = RECONNECT_BASE_MS;
        connect();
    }

    /** 用户主动停这一路：取消重连、放掉 socket 与声卡，之后不会再自己起来。 */
    public void stop() {
        closed = true;
        cancelReconnect();
        release();
        setState(PullState.STOPPED, PullState.STOPPED.label());
    }

    /**
     * 改这一路的音量。立刻作用到当前声卡，并记住——重连会重建声卡，
     * 不补这一次用户会看到「断了一次之后这路声音突然变大」。
     */
    public void setVolume(float value) {
        config = config.withVolume(value);
        PlaybackSession pb = playback;
        if (pb != null) {
            try {
                pb.setVolume(config.volume());
            } catch (Exception e) {
                Log.w(TAG, "设置音量失败：" + config.sourceLabel() + "：" + e);
            }
        }
    }

    // ---------- 内部 ----------

    private void connect() {
        if (closed) return;
        release();

        setState(PullState.CONNECTING, PullState.CONNECTING.label());

        PlaybackSession pb = new PlaybackSession(sinkFactory, new PlaybackSession.Listener() {
            @Override
            public void onState(PullState s, String detail) {
                handleState(s, detail);
            }

            @Override
            public void onPlaybackFailed(String reason) {
                lastError = reason;
                Log.w(TAG, "播放失败：" + config.sourceLabel() + "：" + reason);
                Listener l = listener;
                if (l != null) l.onPlaybackFailed(PlaybackRoute.this, reason);
            }
        });
        // 声卡是这一路新建的，音量要当场补上——PlaybackSession 只知道 1.0
        pb.setVolume(config.volume());
        playback = pb;

        // tcpPort 传 0 让 PullSession 自己去全区间扫描
        PullSession ps = new PullSession(config.host(), 0, config.deviceId(), config.deviceName(),
                machineId, machineName, pb);
        pull = ps;
        Log.i(TAG, "开始连接：" + config.sourceLabel());
        ps.start();
    }

    private void handleState(PullState next, String detail) {
        if (closed) return;
        state = next;
        stateDetail = detail;
        Listener l = listener;
        if (l != null) l.onState(this, next, detail);

        if (next == PullState.FAILED) {
            scheduleReconnect();
        } else if (next == PullState.PLAYING) {
            reconnectDelay = RECONNECT_BASE_MS; // 连上了就重置退避
        }
    }

    /** 只放连接与声卡，不碰 closed（重连时要能重新连上）。 */
    private void release() {
        PullSession ps = pull;
        pull = null;
        if (ps != null) {
            try {
                ps.close();
            } catch (Exception ignored) {
            }
        }
        PlaybackSession pb = playback;
        playback = null;
        if (pb != null) {
            try {
                pb.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 重连 ----------

    private void scheduleReconnect() {
        if (closed) return;
        cancelReconnect();

        final int attempt = reconnectAttempt();
        setState(PullState.CONNECTING, "正在第 " + attempt + " 次重连…");

        reconnectTask = () -> {
            if (closed) return;
            connect();
        };
        scheduler.postDelayed(reconnectTask, reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_CAP_MS);
    }

    private int reconnectAttempt() {
        long delay = reconnectDelay;
        int attempt = 0;
        while (delay > RECONNECT_BASE_MS) {
            attempt++;
            delay /= 2;
        }
        return attempt + 1;
    }

    private void cancelReconnect() {
        Runnable task = reconnectTask;
        reconnectTask = null;
        if (task != null) scheduler.removeCallbacks(task);
    }

    private void setState(PullState next, String detail) {
        state = next;
        stateDetail = detail;
        Listener l = listener;
        if (l != null) l.onState(this, next, detail);
    }
}
