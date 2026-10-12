package com.yiyiooo.audiostream;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.yiyiooo.audiostream.discovery.DeviceInfo;
import com.yiyiooo.audiostream.discovery.DiscoveryException;
import com.yiyiooo.audiostream.discovery.PcDiscovery;
import com.yiyiooo.audiostream.discovery.PcInfo;
import com.yiyiooo.audiostream.player.AudioSinkFactory;
import com.yiyiooo.audiostream.player.AudioTrackSinkFactory;
import com.yiyiooo.audiostream.player.PlaybackSession;
import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.pull.PullSession;
import com.yiyiooo.audiostream.pull.PullState;
import com.yiyiooo.audiostream.source.CaptureGrant;
import com.yiyiooo.audiostream.source.DeviceSourceProvider;
import com.yiyiooo.audiostream.source.SourceServer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务：把播放端的整个生命周期管起来——发现、连接、播放、断开、断线重连。
 *
 * UI 通过 {@link #bindService} 拿到 {@link LocalBinder}，设置 {@link Listener} 后
 * 调 {@link #discover} 与 {@link #connect}，状态变化自然走回调回来，不轮询。
 */
public final class AudioStreamService extends Service {

    // --- 通知常量 ---
    private static final String CHANNEL_ID = "audiostream-playback";
    private static final int NOTIFICATION_ID = 1;

    /** 与 MainActivity 同一个标签：一次联调里界面事件与服务事件能按时间排在一起看。 */
    private static final String TAG = "AudioStream";

    // --- 重连 ---
    private static final long RECONNECT_BASE_MS = 1000L;
    private static final long RECONNECT_CAP_MS  = 30000L;

    // --- Binder ---
    public class LocalBinder extends Binder {
        public AudioStreamService service() {
            return AudioStreamService.this;
        }
    }

    /** UI 要实现的回调（全在主线程）。 */
    public interface Listener {
        void onState(PullState state, String detail);
        void onFormat(AudioFormat format);
        void onPlaybackFailed(String reason);
        /** 发现完成——设备列表与 PC 身份。 */
        void onDevices(List<DeviceInfo> devices, PcInfo info);
        /** 发现失败——reason 已经是中文。 */
        void onDiscoveryFailed(String reason);
    }

    // --- 内部状态 ---
    private Handler mainHandler;
    private ExecutorService executor;
    private NotificationManager notificationManager;

    private MachineIdentity identity;
    private PcDiscovery discoveryClient;
    private final AudioSinkFactory sinkFactory = new AudioTrackSinkFactory();

    private volatile PullSession pull;
    private volatile PlaybackSession playback;
    private volatile boolean intentionalStop;

    // 重连
    private volatile long reconnectDelay = RECONNECT_BASE_MS;
    private Runnable reconnectTask;

    // 当前连接会话参数（重连要用同一组）
    private volatile String sessionHost;
    private volatile String sessionDeviceId;
    private volatile String sessionDeviceName;

    // 缓存最新状态
    private volatile PullState state = PullState.IDLE;
    private volatile String stateDetail = PullState.IDLE.label();
    private volatile AudioFormat format;
    private volatile String lastError;

    private volatile Listener listener;

    // --- 来源端（手机当音源，让 PC 反过来拉）---
    /**
     * 来源提供者。它跟着**服务**的寿命走，不跟着来源端的开关走：
     * 内录授权是「用户在系统弹框里点过一次同意」的状态，不能因为用户把来源端关了再开就丢掉
     * ——那意味着每开一次都要重新弹一次系统框。
     */
    private DeviceSourceProvider sourceProvider;
    private volatile SourceServer sourceServer;
    /**
     * 已经生效的前台服务类型；0 表示还没进过前台。
     * 要改类型必须先把旧的撤掉——Android 会忽略对已经生效的类型的再次声明，
     * 直接再调一次 startForeground 换类型是没用的（这个坑不报错，只是不生效）。
     */
    private int foregroundTypesApplied;

    // ========== Service 生命周期 ==========

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "audiostream-svc");
            thread.setDaemon(true);
            return thread;
        });
        identity = MachineIdentity.load(this);
        discoveryClient = new PcDiscovery();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "音频流转",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示当前播放状态");
            notificationManager.createNotificationChannel(channel);
        }
        sourceProvider = new DeviceSourceProvider(this);
        Log.i(TAG, "服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "服务被启动，转前台");
        syncForeground();
        // 如果是从通知栏启动了 MainActivity，intent 可能带 flag
        // 目前只保证前台服务跑着
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "服务被绑定");
        return new LocalBinder();
    }

    @Override
    public void onDestroy() {
        intentionalStop = true;
        cancelReconnect();
        executor.shutdownNow();
        cleanup();
        closeSourceQuietly();
        DeviceSourceProvider provider = sourceProvider;
        sourceProvider = null;
        // 两路采集与内录通道一起交还系统
        if (provider != null) provider.release();
        super.onDestroy();
    }

    // ========== 公开 API（UI 线程调） ==========

    /** 设置一个监听者（仅一个）。传 null 取消。 */
    public void setListener(Listener l) {
        listener = l;
    }

    /**
     * 用用户填的地址去查对端身份与设备列表。结果走 {@link Listener#onDevices} 或
     * {@link Listener#onDiscoveryFailed}。
     */
    public void discover(final String host) {
        final String normalizedHost = AppConfig.normalizeHost(host);
        AppConfig.saveHost(this, host);
        executor.submit(() -> {
            try {
                PcInfo info = discoveryClient.fetchInfo(host);
                List<DeviceInfo> devices = discoveryClient.fetchDevices(host);
                mainHandler.post(() -> {
                    Listener l = listener;
                    if (l != null) l.onDevices(devices, info);
                });
            } catch (DiscoveryException e) {
                final String reason = e.getMessage();
                mainHandler.post(() -> {
                    Listener l = listener;
                    if (l != null) l.onDiscoveryFailed(reason);
                });
            }
        });
    }

    /**
     * 连上指定对端设备，开始收音频并在本机播放。
     * 已在连接中时会先断开旧的。
     */
    public void connect(final String host, final String deviceId, final String deviceName) {
        final String tcpHost = AppConfig.normalizeHost(host);
        AppConfig.saveHost(this, host);
        AppConfig.saveDevice(this, deviceId, deviceName);
        executor.submit(() -> doConnect(tcpHost, deviceId, deviceName));
    }

    /** 主动断开当前连接。如果在重连中，一并取消。 */
    public void disconnect() {
        intentionalStop = true;
        cancelReconnect();
        executor.submit(this::cleanup);
    }

    // --- 状态查询（UI 可能在任意线程调，值都是 volatile 打底） ---

    public PullState state()         { return state; }
    public String stateDetail()     { return stateDetail; }
    public AudioFormat format()     { return format; }
    public String lastError()       { return lastError; }
    public boolean isPlaying() {
        PlaybackSession pb = playback;
        return pb != null && pb.isPlaying();
    }
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

    /**
     * 设置播放输出的音量（0..1），并记住它。
     *
     * 只影响本应用播出去的声音，不动系统媒体音量——用户要的是「这台手机放电脑的声音小一点」，
     * 而不是把整台手机的媒体音量压下去。
     */
    public void setVolume(float volume) {
        float clamped = AppConfig.clampVolume(volume);
        PlaybackSession pb = playback;
        if (pb != null) pb.setVolume(clamped);
        AppConfig.saveVolume(this, clamped);
    }

    /**
     * 当前输出音量。没在播放时返回上次存下的值——界面刚打开、还没连上时滑条也该停在用户上次调的位置。
     */
    public float volume() {
        PlaybackSession pb = playback;
        if (pb != null) return pb.volume();
        return AppConfig.loadVolume(this);
    }

    // ========== 来源端（手机当音源，让 PC 反过来拉） ==========

    /**
     * 手机有没有被允许录音。没有它就不能开来源端，也不能声明 microphone 类型的前台服务
     * （targetSdk 34 起，声明了却没有权限会直接抛 SecurityException）。
     */
    public boolean hasRecordPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 开启来源端：手机开始对外提供设备接口（12570）与音频服务（12670-12689），
     * PC 端在配置页填手机 IP 就能把它当成一台 AudioStream 机器。
     *
     * 同步做完（绑两个端口是毫秒级的事），按钮点下去立刻能给出「起没起来」的答复；
     * 起不来时返回的是面向用户的中文原因，调用方直接显示即可。
     */
    public String startSource() {
        if (sourceServer != null) return null;
        // 麦克风与内录至少要有一个能出声，否则开了也白开。
        // 刻意不在「没录音权限」时直接拒绝：只想用内录的用户不该被逼着先给录音权限，
        // 而真去点麦克风那一路时协议会回 denied 并把原因写清楚。
        if (!hasRecordPermission() && !hasCaptureGrant()) {
            return "还没有声音可送：先给「录音」权限，或者在手机上点一次「允许内录」。";
        }
        DeviceSourceProvider provider = sourceProvider;
        if (provider == null) {
            return "服务状态异常，请关掉 AudioStream 重新打开。";
        }
        SourceServer server = new SourceServer(provider, identity.id(), identity.name(),
                BuildConfig.VERSION_NAME, SourceServer.HTTP_PORT);
        String error = server.start();
        if (error != null) {
            Log.w(TAG, "来源端起不来：" + error);
            return error;
        }
        sourceServer = server;
        syncForeground();
        Log.i(TAG, "来源端已开启：" + server.statusLine());
        return null;
    }

    /** 关掉来源端：停服务、放掉麦克风。用户主动关的，不重试也不重连。 */
    public void stopSource() {
        closeSourceQuietly();
        syncForeground();
    }

    public boolean sourceRunning() {
        SourceServer server = sourceServer;
        return server != null && server.isRunning();
    }

    /** 界面上那行来源端状态。 */
    public String sourceStatusLine() {
        SourceServer server = sourceServer;
        return server == null ? "来源端：未开启" : server.statusLine();
    }

    /** 此刻有几台设备在拉手机的声音。 */
    public int sourceSessionCount() {
        SourceServer server = sourceServer;
        return server == null ? 0 : server.sessionCount();
    }

    /** 从开启到现在一共被连过几次（含已结束的）。 */
    public int sourceTotalSessions() {
        SourceServer server = sourceServer;
        return server == null ? 0 : server.totalSessions();
    }

    /** 这台手机此刻的 IPv4 地址，显示给用户好让他填到 PC 端那边。 */
    public String localAddresses() {
        return android.text.TextUtils.join("、",
                com.yiyiooo.audiostream.source.LocalAddresses.list());
    }

    // ---------- 内录（手机上正在放的声音） ----------

    /**
     * 用户在系统弹框里点完同意之后，把那份授权收下来。
     *
     * 这里必须**先**把前台服务类型补上 mediaProjection 再返回：Android 14 起，
     * 没以这个类型进前台就去取 MediaProjection，系统会直接拒绝——而那时用户刚点完同意，
     * 屏幕上却什么都没发生，是最难向用户解释的一种失败。
     */
    public void setCaptureGrant(int resultCode, Intent data) {
        DeviceSourceProvider provider = sourceProvider;
        if (provider == null) {
            Log.w(TAG, "收到内录授权时服务状态异常，丢弃这次授权");
            return;
        }
        provider.setGrant(new CaptureGrant(resultCode, data));
        syncForeground();
        Log.i(TAG, "收到内录授权：resultCode=" + resultCode
                + "，可用=" + provider.hasCaptureGrant());
    }

    /** 本次进程里用户点过内录授权没有。注意它不代表「以后也一直有效」。 */
    public boolean hasCaptureGrant() {
        DeviceSourceProvider provider = sourceProvider;
        return provider != null && provider.hasCaptureGrant();
    }

    /** 界面上那行内录状态。 */
    public String captureStatusLine() {
        DeviceSourceProvider provider = sourceProvider;
        if (provider == null) return "内录：服务未就绪";
        if (!provider.hasCaptureGrant()) {
            return "内录：未授权 —— 点下面的按钮，并在系统弹框里同意";
        }
        return provider.isCaptureOpen() ? "内录：已授权（PC 端可以拉）" : "内录：已授权";
    }

    /** 停来源端但不碰前台服务——服务正在销毁时不能再去 startForeground。 */
    private void closeSourceQuietly() {
        SourceServer server = sourceServer;
        sourceServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (Exception e) {
                Log.w(TAG, "停来源端出错：" + e);
            }
        }
        // 只停采集，不丢内录授权：用户关掉来源端再打开时不该被要求重新点一次系统弹框
        DeviceSourceProvider provider = sourceProvider;
        if (provider != null) provider.stopSources();
    }

    // ========== 内部 ==========

    private void doConnect(String host, String deviceId, String deviceName) {
        // 旧的先放掉
        cleanup();
        intentionalStop = false;

        sessionHost = host;
        sessionDeviceId = deviceId;
        sessionDeviceName = deviceName;

        replayState(PullState.CONNECTING, PullState.CONNECTING.label());

        // 播放层监听 → 转给 UI
        PlaybackSession pb = new PlaybackSession(sinkFactory, new PlaybackSession.Listener() {
            @Override
            public void onState(PullState s, String detail) {
                handleStateChange(s, detail);
            }

            @Override
            public void onPlaybackFailed(String reason) {
                lastError = reason;
                mainHandler.post(() -> {
                    Listener l = listener;
                    if (l != null) l.onPlaybackFailed(reason);
                });
            }
        });
        playback = pb;

        // tcpPort 传 0 让 PullSession 自己去全区间扫描
        PullSession ps = new PullSession(host, 0, deviceId, deviceName,
                identity.id(), identity.name(), pb);
        pull = ps;
        ps.start();
    }

    private void cleanup() {
        PullSession ps = pull;
        pull = null;
        if (ps != null) try { ps.close(); } catch (Exception ignored) {}

        PlaybackSession pb = playback;
        playback = null;
        if (pb != null) try { pb.close(); } catch (Exception ignored) {}
    }

    // PullSession 状态变化由 PlaybackSession.Listener.onState 传过来
    private void handleStateChange(PullState s, String detail) {
        state = s;
        stateDetail = detail;
        replayState(s, detail);

        if (!intentionalStop && s == PullState.FAILED) {
            scheduleReconnect();
        } else if (s == PullState.PLAYING) {
            reconnectDelay = RECONNECT_BASE_MS; // 连上了就重置退避
        }
    }

    private void replayState(PullState s, String detail) {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onState(s, detail);
        });
        updateNotification();
    }

    // ---------- 重连 ----------

    private void scheduleReconnect() {
        cancelReconnect();
        if (intentionalStop) return;
        if (sessionHost == null || sessionHost.isEmpty()) return;

        final String host = sessionHost;
        final String devId = sessionDeviceId;
        final String devName = sessionDeviceName;

        String msg = "正在第 " + reconnectAttempt() + " 次重连…";
        replayState(PullState.CONNECTING, msg);

        reconnectTask = () -> {
            if (intentionalStop) return;
            doConnect(host, devId, devName);
        };
        mainHandler.postDelayed(reconnectTask, reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_CAP_MS);
    }

    private int reconnectAttempt() {
        long d = reconnectDelay;
        int n = 0;
        while (d > RECONNECT_BASE_MS) { n++; d /= 2; }
        return n + 1;
    }

    private void cancelReconnect() {
        Runnable task = reconnectTask;
        reconnectTask = null;
        if (task != null) mainHandler.removeCallbacks(task);
    }

    // ---------- 通知 ----------

    private Notification buildNotification(String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ?
                        PendingIntent.FLAG_IMMUTABLE : 0));

        // Notification.Builder(Context, String) 是 API 26 才有的构造器，
        // minSdk 是 23，低版本必须用旧构造器，否则直接 NoSuchMethodError 崩。
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("音频流转")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }

    /** 通知栏那句话：同时兼顾两端——可能在放声音，也可能在当音源，还可能两件事一起干。 */
    private String notificationText() {
        PullState s = state;
        if (s == PullState.PLAYING) {
            return "正在从 " + (sessionHost != null ? sessionHost : "—") + " 播放";
        }
        if (s == PullState.FAILED) {
            return "连接失败";
        }
        SourceServer server = sourceServer;
        if (server != null && server.isRunning()) {
            int sessions = server.sessionCount();
            return sessions == 0 ? "正在当音源（还没有人来拉）" : "正在给 " + sessions + " 台设备送声音";
        }
        return s.label();
    }

    private void updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(notificationText()));
    }

    /**
     * 把前台服务的类型调整成此刻真正需要的组合，并且只在需要时动它。
     *
     * 类型里要不要 microphone，取决于「来源端开着」与「真的拿到了录音权限」两条同时成立：
     * 声明了 microphone 类型却没有 RECORD_AUDIO 权限，Android 会直接抛 SecurityException。
     *
     * 要改已经生效的类型，必须先 stopForeground 再 startForeground——直接再调一次
     * startForeground 传新类型，系统会当成「已经在跑了」而不做任何改变，且不报错。
     */
    private void syncForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(notificationText()));
            return;
        }
        int types = foregroundTypes();
        if (foregroundTypesApplied != 0 && types == foregroundTypesApplied) {
            updateNotification();
            return;
        }
        if (foregroundTypesApplied != 0) {
            stopForeground(STOP_FOREGROUND_DETACH);
        }
        startForeground(NOTIFICATION_ID, buildNotification(notificationText()), types);
        foregroundTypesApplied = types;
    }

    private int foregroundTypes() {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        // microphone 这个类型位是 API 30 才有的：在 Android 10 上传一个它不认识的位，
        // 有可能被当成非法类型拒掉，而那一版本来也不强制类型匹配，不如干脆不传。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && sourceServer != null && hasRecordPermission()) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        // mediaProjection 与内录同版本（API 29）。它不由「来源端开着」决定，而由「用户点过授权」决定：
        // 类型要在取 MediaProjection 之前就已经生效，而取的那一刻可能就是第一次点单。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasCaptureGrant()) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        }
        return types;
    }
}