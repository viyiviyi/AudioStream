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
import com.yiyiooo.audiostream.pull.PullState;
import com.yiyiooo.audiostream.route.PlaybackRoute;
import com.yiyiooo.audiostream.route.RouteConfig;
import com.yiyiooo.audiostream.source.CaptureGrant;
import com.yiyiooo.audiostream.source.DeviceSourceProvider;
import com.yiyiooo.audiostream.source.SourceServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务：把两端都管起来。
 *
 * **播放端是多路的**：{@link #routes} 里每一路都有自己的会话、声卡、音量与重连节奏
 * （见 {@link PlaybackRoute}）。服务本身只负责三件事——增删改这一组路由、把变化通知给界面、
 * 让通知栏那句话跟上。一路的电脑关掉不该影响另一路，所以凡是「这一路自己的」状态都不放在服务上。
 *
 * **来源端是单例的**：手机只有一个麦克风、一路内录，做成多份没有意义。
 *
 * UI 通过 {@link #bindService} 拿到 {@link LocalBinder}，设置 {@link Listener} 后调
 * {@link #discover}、{@link #addRoute} 等，状态变化走 {@link Listener#onRoutesChanged} 回来，不轮询。
 */
public final class AudioStreamService extends Service {

    // --- 通知常量 ---
    private static final String CHANNEL_ID = "audiostream-playback";
    private static final int NOTIFICATION_ID = 1;

    /** 与 MainActivity 同一个标签：一次联调里界面事件与服务事件能按时间排在一起看。 */
    private static final String TAG = "AudioStream";

    /** 滑条拖动时音量会连着改几十次，落盘去抖一下，只写最后一次。 */
    private static final long PERSIST_DEBOUNCE_MS = 400L;

    // --- Binder ---
    public class LocalBinder extends Binder {
        public AudioStreamService service() {
            return AudioStreamService.this;
        }
    }

    /** UI 要实现的回调（全在主线程）。 */
    public interface Listener {
        /**
         * 路由集合或某一路的状态变了，界面该重绘了。
         *
         * 把「状态、格式、播放失败」合并成一条：界面本来就是整块重绘的，分开三个回调
         * 只会让它在同一瞬间被叫三次。
         */
        void onRoutesChanged();

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

    /**
     * 当前所有拉取路。用 CopyOnWriteArrayList：读它的地方（界面每秒刷新计数、通知文案）
     * 远比改它的地方多，而每次改动本来就要通知界面重绘，复制的代价可以忽略。
     */
    private final List<PlaybackRoute> routes = new CopyOnWriteArrayList<PlaybackRoute>();

    private final Runnable persistTask = () -> persistRoutes();

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

    /** 一路的状态变化都汇到这里：记日志、通知界面、更新通知栏。 */
    private final PlaybackRoute.Listener routeListener = new PlaybackRoute.Listener() {
        @Override
        public void onState(PlaybackRoute route, PullState state, String detail) {
            Log.i(TAG, "第「" + route.config().sourceLabel() + "」路：" + state + " "
                    + (detail == null ? "" : detail));
            notifyRoutesChanged();
        }

        @Override
        public void onPlaybackFailed(PlaybackRoute route, String reason) {
            Log.w(TAG, "第「" + route.config().sourceLabel() + "」路播放失败：" + reason);
            notifyRoutesChanged();
        }
    };

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
        restoreRoutes();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "服务被启动，转前台");
        syncForeground();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "服务被绑定");
        return new LocalBinder();
    }

    @Override
    public void onDestroy() {
        for (PlaybackRoute route : routes) {
            try {
                route.stop();
            } catch (Exception e) {
                Log.w(TAG, "停止一路时出错：" + e);
            }
        }
        routes.clear();
        mainHandler.removeCallbacks(persistTask);
        executor.shutdownNow();
        closeSourceQuietly();
        DeviceSourceProvider provider = sourceProvider;
        sourceProvider = null;
        // 两路采集与内录通道一起交还系统
        if (provider != null) provider.release();
        super.onDestroy();
    }

    // ========== 播放端：多路 ==========

    /** 设置一个监听者（仅一个）。传 null 取消。 */
    public void setListener(Listener l) {
        listener = l;
    }

    /**
     * 用用户填的地址去查对端身份与设备列表。结果走 {@link Listener#onDevices} 或
     * {@link Listener#onDiscoveryFailed}。
     *
     * 发现与「已经在跑的路」完全无关：它只是去问一次对方有什么设备，失败也不影响任何一路。
     */
    public void discover(final String host) {
        AppConfig.saveLastHost(this, host);
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
     * 加一路并立刻开始拉。返回 null 表示加上了，否则是面向用户的中文原因。
     *
     * 同一台电脑的同一块设备只允许一路：两路一模一样的声音叠在一起，用户听到的只是「变大了」，
     * 完全看不出是自己加了两路。
     */
    public String addRoute(String host, String deviceId, String deviceName) {
        String normalized = AppConfig.normalizeHost(host);
        if (normalized.isEmpty() || deviceId == null || deviceId.isEmpty()) {
            return "地址和设备都要选好才能加一路。";
        }
        String id = RouteConfig.key(normalized, deviceId);
        for (PlaybackRoute existing : routes) {
            if (existing.id().equals(id)) {
                return "这一路已经在列表里了。";
            }
        }
        AppConfig.saveLastHost(this, normalized);

        PlaybackRoute route = new PlaybackRoute(
                RouteConfig.create(normalized, deviceId, deviceName, AppConfig.DEFAULT_VOLUME),
                sinkFactory, identity.id(), identity.name(), routeListener);
        routes.add(route);
        persistRoutes();
        notifyRoutesChanged();
        route.start();
        syncForeground();
        return null;
    }

    /** 停掉并移除一路。之后不会再自己连回来。 */
    public void removeRoute(String id) {
        for (PlaybackRoute route : routes) {
            if (route.id().equals(id)) {
                routes.remove(route);
                try {
                    route.stop();
                } catch (Exception e) {
                    Log.w(TAG, "停一路时出错：" + e);
                }
                break;
            }
        }
        persistRoutes();
        notifyRoutesChanged();
        syncForeground();
    }

    /**
     * 改一路的音量（0..1）。
     *
     * 只影响这一路播出去的声音，不动系统媒体音量，也不影响其它路——每路一块 AudioTrack、
     * 各带自己的增益，所以这是天然的。
     *
     * 拖动滑条时调用会很密集，落盘做了去抖。
     */
    public void setRouteVolume(String id, float volume) {
        for (PlaybackRoute route : routes) {
            if (route.id().equals(id)) {
                route.setVolume(volume);
                break;
            }
        }
        schedulePersist();
    }

    /** 此刻的全部路（快照，界面拿它重绘整块列表）。 */
    public List<PlaybackRoute> routes() {
        return new ArrayList<PlaybackRoute>(routes);
    }

    public int routeCount() {
        return routes.size();
    }

    /** 正在出声的路数。界面与通知都用它。 */
    public int playingRouteCount() {
        int playing = 0;
        for (PlaybackRoute route : routes) {
            if (route.state() == PullState.PLAYING) playing++;
        }
        return playing;
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

    /**
     * 把上次存下的那几路重新拉起来。
     *
     * 在 onCreate 里做：服务是被 Activity 启动或系统拉起的，两种情况下用户上次配的路都该接着跑，
     * 而不是等他打开界面点一下。某一路的地址已经失效也不影响别的路——每路各连各的。
     */
    private void restoreRoutes() {
        List<RouteConfig> saved = AppConfig.loadRoutes(this);
        if (saved.isEmpty()) return;
        Log.i(TAG, "恢复 " + saved.size() + " 路");
        for (RouteConfig config : saved) {
            routes.add(new PlaybackRoute(config, sinkFactory,
                    identity.id(), identity.name(), routeListener));
        }
        for (PlaybackRoute route : routes) {
            route.start();
        }
        updateNotification();
    }

    private void persistRoutes() {
        List<RouteConfig> snapshot = new ArrayList<RouteConfig>();
        for (PlaybackRoute route : routes) {
            snapshot.add(route.config());
        }
        AppConfig.saveRoutes(this, snapshot);
    }

    /** 音量拖动时的落盘去抖：连着改变几十次，只写最后一次的值。 */
    private void schedulePersist() {
        mainHandler.removeCallbacks(persistTask);
        mainHandler.postDelayed(persistTask, PERSIST_DEBOUNCE_MS);
    }

    private void notifyRoutesChanged() {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onRoutesChanged();
        });
        updateNotification();
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

    /**
     * 通知栏那句话：多路时要把「几路在响、几路连不上」说清，同时兼顾来源端。
     * 这句话有长度限制，所以先挑最要紧的说。
     */
    private String notificationText() {
        StringBuilder text = new StringBuilder();
        int playing = 0;
        int failed = 0;
        for (PlaybackRoute route : routes) {
            PullState state = route.state();
            if (state == PullState.PLAYING) playing++;
            else if (state == PullState.FAILED) failed++;
        }
        if (playing > 0) text.append("正在播放 ").append(playing).append(" 路");
        if (failed > 0) {
            if (text.length() > 0) text.append("，");
            text.append(failed).append(" 路连不上");
        }

        SourceServer server = sourceServer;
        if (server != null && server.isRunning()) {
            if (text.length() > 0) text.append("；");
            int sessions = server.sessionCount();
            text.append(sessions == 0 ? "正在当音源" : "给 " + sessions + " 台设备送声音");
        }

        if (text.length() == 0) {
            text.append(routes.isEmpty() ? "等待连接" : "正在连接…");
        }
        return text.toString();
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
