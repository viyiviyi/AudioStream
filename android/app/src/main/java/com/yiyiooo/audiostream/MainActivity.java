package com.yiyiooo.audiostream;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.yiyiooo.audiostream.discovery.DeviceInfo;
import com.yiyiooo.audiostream.discovery.PcInfo;
import com.yiyiooo.audiostream.pull.PullState;
import com.yiyiooo.audiostream.route.PlaybackRoute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主界面：一屏管两头——上半截是播放端（填电脑地址 → 刷设备列表 → 选来源 → 加一路，
 * 加了几路就有几张卡片，每张卡片自己一根音量滑条），下半截是来源端
 * （把手机变成一台 AudioStream 机器，让电脑反过来拉）。
 *
 * 界面走 Material 组件与 XML 布局（res/layout/activity_main.xml + item_route.xml）；
 * 协议层与音频层仍然只用平台 API，因为那两层要逐字节对齐 C# 端，多一层封装只会多一个出错的地方。
 *
 * 这里只做三件事：把控件找出来接上回调、把服务报上来的状态照实显示、把用户的选择落盘。
 * 音频链路本身在前台服务里，锁屏或切后台再回来，读到的状态与服务是同一份。
 *
 * **每张卡片里的控件 id 是重复的**（有几路就有几份），所以一律从卡片自己的根视图上找，
 * 拿 Activity 去找只会拿到第一张卡片的控件——那会让所有路的音量都调到第一路去。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIFICATIONS = 40;

    /** 点「开启来源端」时当场要录音权限：没这个权限手机发不出声音。 */
    private static final int REQ_RECORD_AUDIO = 41;

    /**
     * 点「允许内录」时请系统弹授权框。这个框**每次都要弹、没法省**：
     * Android 不允许应用自己决定能否录屏内音频，而且拿到的凭据只在本进程活着期间有效
     * （存不了盘，下次开机就没了），所以内录做不到「授权一次以后一直可用」。
     */
    private static final int REQ_CAPTURE = 42;

    /** logcat 标签：界面日志同时进 logcat，联调与事后排查不必盯着那块屏幕。 */
    private static final String TAG = "AudioStream";

    /** 音量滑条的步长。取值与布局里 Slider 的 android:stepSize 必须一致，见 snapVolume。 */
    private static final float VOLUME_STEP = 0.05f;

    // --- 服务绑定 ---
    private AudioStreamService service;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AudioStreamService.LocalBinder) binder).service();
            service.setListener(serviceListener);
            bound = true;
            Log.i(TAG, "服务已连上");
            onServiceReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            Log.w(TAG, "服务断开（进程多半被系统收了），界面按未连接处理");
            updateAddButtonState();
        }
    };

    // --- 「加一路」控件 ---
    private TextInputLayout hostLayout;
    private TextInputEditText hostInput;
    private MaterialButton discoverButton;
    private Spinner deviceSpinner;
    private MaterialButton addRouteButton;
    private TextView addHintText;

    // --- 路由列表 ---
    private TextView routesTitle;
    private TextView routesEmptyText;
    private LinearLayout routeContainer;

    // --- 来源端控件 ---
    private TextView localAddressText;
    private MaterialButton sourceButton;
    private MaterialButton captureButton;
    private TextView sourceStatusText;
    private TextView captureStatusText;

    // --- 外观与日志 ---
    private MaterialButtonToggleGroup themeGroup;
    private TextView logText;
    private ScrollView logScroll;

    private final List<DeviceInfo> devices = new ArrayList<>();
    private final List<String> deviceLabels = new ArrayList<>();

    /** 屏幕上每一路的那张卡片，按路由 id 索引。 */
    private final Map<String, RouteCard> cards = new HashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean tickRunning;

    // ========== 服务回调（主线程） ==========

    private final AudioStreamService.Listener serviceListener = new AudioStreamService.Listener() {
        @Override
        public void onRoutesChanged() {
            renderRoutes();
            updateAddButtonState();
        }

        @Override
        public void onDevices(List<DeviceInfo> list, PcInfo info) {
            devices.clear();
            deviceLabels.clear();
            if (list != null) devices.addAll(list);

            // 重排成「输出在前、输入在后」，并且 devices 与 deviceLabels 严格一一对应：
            // spinner 的下标直接就是 devices 的下标，中途跳过谁都会让后面点错设备。
            List<DeviceInfo> ordered = new ArrayList<>();
            for (DeviceInfo d : devices) if (d.isOutput()) ordered.add(d);
            for (DeviceInfo d : devices) if (d.isInput()) ordered.add(d);
            devices.clear();
            devices.addAll(ordered);

            for (DeviceInfo d : devices) {
                deviceLabels.add((d.isOutput() ? "[输出] " : "[输入] ") + d.displayName());
            }
            if (deviceLabels.isEmpty()) {
                deviceLabels.add("（对方没有可选设备）");
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this,
                    android.R.layout.simple_spinner_dropdown_item, deviceLabels);
            deviceSpinner.setAdapter(adapter);

            String pcBrief = (info == null ? "" :
                    info.pcName().isEmpty() ? info.machineId() : info.pcName());
            log("发现 " + devices.size() + " 个设备" + (pcBrief.isEmpty() ? "" : "（" + pcBrief + "）"));
            if (devices.isEmpty()) {
                addHint("对方没有可选设备：检查它的声卡是否可用。");
            } else {
                addHint("");
            }
            updateAddButtonState();
        }

        @Override
        public void onDiscoveryFailed(String reason) {
            addHint(reason);
            log("发现失败：" + reason);
        }
    };

    // ========== 生命周期 ==========

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupToolbar();
        setupTheme();

        requestNotificationPermissionIfNeeded();

        // 只绑定，不在这里启动前台服务。
        //
        // startForegroundService 一调用，系统就给了 5 秒期限要求服务 startForeground；
        // 而打开界面这一刻主线程正要画首帧，慢设备（模拟器、老机器）上首帧要十几秒才出来，
        // 期限一到就是「did not then call Service.startForeground」的 ANR，界面从此再也起不来。
        // 何况只是打开界面看看设备列表，本来也不需要常驻的前台服务——转前台推迟到真要连的时候。
        boolean bindOk = bindService(new Intent(this, AudioStreamService.class),
                connection, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "bindService -> " + bindOk);

        startTick();
    }

    @Override
    protected void onDestroy() {
        stopTick();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    /** 服务连上后才有的动作：回填上次的地址、把已经在跑的那几路画出来。 */
    private void onServiceReady() {
        String lastHost = AppConfig.loadLastHost(this);
        if (!lastHost.isEmpty() && text(hostInput).isEmpty()) {
            hostInput.setText(lastHost);
        }
        updateAddButtonState();
        renderRoutes();
        refreshSourceStatus();
        refreshSourceAddress();
    }

    // ========== 控件装配 ==========

    private void bindViews() {
        hostLayout = findViewById(R.id.hostLayout);
        hostInput = findViewById(R.id.hostInput);
        discoverButton = findViewById(R.id.discoverButton);
        deviceSpinner = findViewById(R.id.deviceSpinner);
        addRouteButton = findViewById(R.id.addRouteButton);
        addHintText = findViewById(R.id.addHintText);

        routesTitle = findViewById(R.id.routesTitle);
        routesEmptyText = findViewById(R.id.routesEmptyText);
        routeContainer = findViewById(R.id.routeContainer);

        localAddressText = findViewById(R.id.localAddressText);
        sourceButton = findViewById(R.id.sourceButton);
        captureButton = findViewById(R.id.captureButton);
        sourceStatusText = findViewById(R.id.sourceStatusText);
        captureStatusText = findViewById(R.id.captureStatusText);

        themeGroup = findViewById(R.id.themeGroup);
        logScroll = findViewById(R.id.logScroll);
        logText = findViewById(R.id.logText);

        // 设备下拉先给一句话，等用户刷出列表再换成真选项
        deviceSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"先刷新设备列表"}));

        discoverButton.setOnClickListener(v -> onDiscover());
        addRouteButton.setOnClickListener(v -> onAddRoute());
        sourceButton.setOnClickListener(v -> onSourceToggle());
        captureButton.setOnClickListener(v -> onCaptureRequest());

        // 后台服务没绑上之前点了也没有对象可操作，先灰着，服务一到就打开
        addRouteButton.setEnabled(false);
        sourceButton.setEnabled(false);
        captureButton.setEnabled(false);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        MachineIdentity identity = MachineIdentity.load(this);
        // 副标题给版本号与本机名字：排查时第一件要问的就是「你装的是哪一版、这台机器是谁」
        toolbar.setSubtitle("v" + BuildConfig.VERSION_NAME + "　·　本机 " + identity.name());
    }

    // ---------- 外观 ----------

    private void setupTheme() {
        // 先把选中状态摆好，再挂监听：check() 自己会触发一次回调，
        // 顺序反了就会在界面刚起来时按当前设置重设一次主题（多余的一次重建）。
        themeGroup.check(themeButtonId(AppConfig.loadThemeMode(this)));
        themeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return; // 只看「被选中」那一次，取消选中那一次忽略
            int mode = themeModeOf(checkedId);
            if (mode == AppConfig.loadThemeMode(this)) return;
            AppConfig.saveThemeMode(this, mode);
            AppCompatDelegate.setDefaultNightMode(toDelegateMode(mode));
            // setDefaultNightMode 会让 Activity 自己重建，界面不需要手工刷新
            Log.i(TAG, "外观改为：" + mode);
        });
    }

    private static int themeButtonId(int mode) {
        if (mode == AppConfig.THEME_LIGHT) return R.id.themeLight;
        if (mode == AppConfig.THEME_DARK) return R.id.themeDark;
        return R.id.themeSystem;
    }

    private static int themeModeOf(int buttonId) {
        if (buttonId == R.id.themeLight) return AppConfig.THEME_LIGHT;
        if (buttonId == R.id.themeDark) return AppConfig.THEME_DARK;
        return AppConfig.THEME_SYSTEM;
    }

    private static int toDelegateMode(int mode) {
        if (mode == AppConfig.THEME_LIGHT) return AppCompatDelegate.MODE_NIGHT_NO;
        if (mode == AppConfig.THEME_DARK) return AppCompatDelegate.MODE_NIGHT_YES;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    // ========== 多路列表 ==========

    /**
     * 按服务报上来的那几路重画列表。
     *
     * 已有的卡片连着控件一起留着、只更新内容——整块清掉重建会有两个后果：用户正在拖的音量滑条
     * 会被打断，而且滚动位置会跳回顶部。
     */
    private void renderRoutes() {
        if (service == null) return;
        List<PlaybackRoute> routes = service.routes();

        Set<String> alive = new HashSet<>();
        for (PlaybackRoute route : routes) alive.add(route.id());

        // 撤掉已经不在列表里的
        Iterator<Map.Entry<String, RouteCard>> iterator = cards.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, RouteCard> entry = iterator.next();
            if (!alive.contains(entry.getKey())) {
                routeContainer.removeView(entry.getValue().root);
                iterator.remove();
            }
        }

        // 补上新出现的，并把每一路的内容刷一遍
        for (PlaybackRoute route : routes) {
            RouteCard card = cards.get(route.id());
            if (card == null) {
                card = createCard(route);
                cards.put(route.id(), card);
                routeContainer.addView(card.root);
            }
            card.route = route;
            card.bind();
        }

        routesEmptyText.setVisibility(routes.isEmpty() ? View.VISIBLE : View.GONE);
        setIfChanged(routesTitle, routes.isEmpty()
                ? "正在播放的路"
                : "正在播放的路（" + routes.size() + " 路）");
    }

    private RouteCard createCard(PlaybackRoute route) {
        View root = getLayoutInflater().inflate(R.layout.item_route, routeContainer, false);
        RouteCard card = new RouteCard(root, route.id());

        card.volume.addOnChangeListener((slider, value, fromUser) -> {
            // fromUser 为假是代码设值（bind 里对齐配置），那时不该回写服务——
            // 否则会变成「服务说 0.3 → 界面设 0.3 → 回写 0.3」，白转一圈还可能打断用户
            if (!fromUser) return;
            card.volumeText.setText(percent(value));
            if (service != null) service.setRouteVolume(card.id, value);
        });
        card.volume.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                card.tracking = true;
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                card.tracking = false;
            }
        });

        card.stop.setOnClickListener(v -> {
            if (service == null) return;
            log("停掉一路：" + card.title.getText());
            service.removeRoute(card.id);
        });
        return card;
    }

    /** 屏幕上的一路。它拿着这一路的运行时对象与那几个控件，负责把状态照实显示。 */
    private final class RouteCard {
        final View root;
        final String id;
        final TextView title;
        final TextView state;
        final TextView detail;
        final TextView counters;
        final TextView volumeText;
        final Slider volume;
        final MaterialButton stop;

        PlaybackRoute route;

        /** 用户此刻正按着这根滑条。按着的时候不去动它，免得跟他的手指打架。 */
        boolean tracking;

        RouteCard(View root, String id) {
            this.root = root;
            this.id = id;
            this.title = root.findViewById(R.id.routeTitle);
            this.state = root.findViewById(R.id.routeState);
            this.detail = root.findViewById(R.id.routeDetail);
            this.counters = root.findViewById(R.id.routeCounters);
            this.volumeText = root.findViewById(R.id.routeVolumeText);
            this.volume = root.findViewById(R.id.routeVolume);
            this.stop = root.findViewById(R.id.routeStop);
        }

        void bind() {
            PlaybackRoute current = route;
            if (current == null) return;

            setIfChanged(title, current.config().sourceLabel());
            setIfChanged(state, current.state().label());

            String note = current.stateDetail();
            if (note == null || note.contentEquals(current.state().label())) {
                setIfChanged(detail, "");
            } else {
                setIfChanged(detail, note);
            }
            // 连不上与播放失败要显眼；正常状态的那句话不必抢眼。
            // 颜色从当前主题里取而不是写死：深色模式下写死的深红会变成一团看不清的字。
            boolean bad = current.state() == PullState.FAILED;
            detail.setTextColor(themeColor(bad
                    ? com.google.android.material.R.attr.colorError
                    : com.google.android.material.R.attr.colorOnSurfaceVariant));

            bindCounters();

            if (!tracking) {
                float value = snapVolume(current.config().volume());
                // 只在真的不一样时设：Slider.setValue 会让它自己重绘并触发一次监听
                if (Math.abs(volume.getValue() - value) > 1e-4f) {
                    volume.setValue(value);
                }
                setIfChanged(volumeText, percent(value));
            }
        }

        void bindCounters() {
            PlaybackRoute current = route;
            if (current == null) return;
            setIfChanged(counters, "缓冲 " + current.bufferedMs() + "ms"
                    + "　欠载 " + current.underruns()
                    + "　丢字节 " + current.droppedBytes()
                    + "　写 " + (current.writtenBytes() / 1024) + "KB");
        }
    }

    // ========== 交互 ==========

    private void onDiscover() {
        String host = text(hostInput).trim();
        hostLayout.setError(null);
        if (host.isEmpty()) {
            showFieldError("请先填电脑地址。");
            return;
        }
        addHint("正在发现…");
        if (service != null) service.discover(host);
    }

    /**
     * 把「当前填的地址 + 当前选中的设备」加成一路。
     *
     * 与从前的「连接」按钮的区别：那时它只能有一路，点了就是把旧的那路换掉；
     * 现在它是往上加，加完这一路照样在跑，用户可以换个设备再加一路。
     */
    private void onAddRoute() {
        String host = text(hostInput).trim();
        hostLayout.setError(null);
        if (host.isEmpty()) {
            showFieldError("请先填电脑地址。");
            return;
        }
        if (service == null) {
            addHint("后台服务还没就绪，稍等一下再点。");
            return;
        }
        int index = deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= devices.size()) {
            addHint("请先刷新设备列表，再选一个来源设备。");
            Log.w(TAG, "加一路被拦下：下拉选项 " + index + " 对不上设备表（" + devices.size() + " 项）");
            return;
        }
        // deviceLabels 与 devices 一一对应（都在 onDevices 里按同一顺序重建），所以 index 就是下标
        DeviceInfo device = devices.get(index);
        ensureForegroundService();
        String error = service.addRoute(host, device.id(), device.name());
        if (error != null) {
            addHint(error);
            log("加一路失败：" + error);
            return;
        }
        addHint("");
        log("加了一路：" + host + " 的 " + device.name());
        renderRoutes();
    }

    /** 地址填错时把红字放在输入框下面而不是别处：用户的视线本来就在那一格上。 */
    private void showFieldError(String message) {
        hostLayout.setError(message);
        addHint(message);
    }

    private void addHint(String message) {
        setIfChanged(addHintText, message == null ? "" : message);
    }

    /**
     * 开始播放才需要前台服务（锁屏与切后台都不能断声），转前台的动作就放在这一刻。
     *
     * 此时界面早就画完了、主线程空闲，服务能立刻 startForeground，不会踩到那 5 秒的期限。
     */
    private void ensureForegroundService() {
        Intent intent = new Intent(this, AudioStreamService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void updateAddButtonState() {
        if (service == null) {
            addRouteButton.setEnabled(false);
            return;
        }
        // 服务就绪就必须把按钮打开：bindViews 里第一次调用它时服务还没绑上，那时按钮被禁用了；
        // 这里若不显式打开，它会一直灰着——用户点了没反应，而界面上看不出任何原因。
        addRouteButton.setEnabled(true);
    }

    private void log(String line) {
        Log.i(TAG, line);
        String now = logText.getText().toString();
        String stamp = Long.toString(System.currentTimeMillis() / 1000 % 100000);
        logText.setText((now.isEmpty() ? "" : now + "\n") + stamp + " " + line);
        // 滚到底
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ========== 周期刷新计数 ==========

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (tickRunning) {
                refreshCounters();
                ui.postDelayed(this, 500);
            }
        }
    };

    private void startTick() {
        if (tickRunning) return;
        tickRunning = true;
        ui.postDelayed(tick, 500);
    }

    private void stopTick() {
        tickRunning = false;
        ui.removeCallbacks(tick);
    }

    /**
     * 每 500ms 只刷数字，不重建列表：无条件 setText 会让窗口一直在重绘（费电，而且窗口永远不 idle，
     * uiautomator 之类的自动化工具会一直等不到），所以每一处 setText 之前都比一次内容。
     */
    private void refreshCounters() {
        for (RouteCard card : cards.values()) {
            card.bindCounters();
        }
        refreshSourceStatus();
    }

    // ========== 来源端（让电脑反过来拉这台手机） ==========

    private void onSourceToggle() {
        if (service == null) {
            addHint("后台服务还没就绪，稍等一下再点。");
            return;
        }
        if (service.sourceRunning()) {
            service.stopSource();
            log("来源端已关闭");
            refreshSourceStatus();
            return;
        }
        if (!service.hasRecordPermission() && !service.hasCaptureGrant()) {
            // 两个来源一个都用不了，先要录音权限：它能被永久授予，比内录那个每次都要弹框的
            // 授权更该先试。已经授权过内录的用户不该在这里被拦住。
            Log.i(TAG, "录音权限与内录授权都没有，先申请录音权限");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        // 来源端要在后台持续送声音（锁屏也不能断），所以和播放端一样要进前台
        ensureForegroundService();
        String error = service.startSource();
        if (error == null) {
            log("来源端已开启：" + service.sourceStatusLine());
        } else {
            log("来源端起不来：" + error);
        }
        refreshSourceStatus();
        refreshSourceAddress();
    }

    // ---------- 内录（把手机正在放的声音给电脑拉） ----------

    /**
     * 点「允许内录」：请系统弹一次授权框。
     *
     * 这个按钮和「开启来源端」是两件独立的事，刻意不合并：录音权限可以通过系统设置页永久授予，
     * 内录授权不能——它每次都弹框且只在本次进程有效。两者的失败提示语与补救动作都不同，
     * 合成一个按钮的话，用户点了不知道自己在同意什么。
     */
    private void onCaptureRequest() {
        if (service == null) {
            addHint("后台服务还没就绪，稍等一下再点。");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setIfChanged(captureStatusText, "内录：这台手机的 Android 版本太老，内录需要 Android 10 及以上。");
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            setIfChanged(captureStatusText, "内录：这台设备没有提供内录能力。");
            return;
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
            log("请在系统弹框里点「立即开始」，手机才会把正在放的声音送出去");
        } catch (Exception e) {
            // 部分定制系统会把这一步拦下来（缺少系统组件、被安全策略禁止）
            setIfChanged(captureStatusText, "内录：打不开授权框 —— " + e);
            Log.w(TAG, "createScreenCaptureIntent 失败", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (service == null) {
            // 用户在系统弹框上停留的过程中进程可能被回收过
            Log.w(TAG, "内录授权回来时服务已经断开，这次授权用不上");
            return;
        }
        // 取消时 resultCode 不是 RESULT_OK。这一点必须如实带给服务：不能因为 data 非空
        // 就当成授权成功，否则 PC 端点单会走到「系统没给出内录通道」那种没法解释的失败
        service.setCaptureGrant(resultCode, data);
        boolean ok = service.hasCaptureGrant();
        log(ok ? "内录已授权：PC 端可以拉「手机内录」了" : "内录授权被取消");
        if (!ok) {
            setIfChanged(captureStatusText, "内录：没被授权 —— 想要电脑拉手机正在放的声音，需要同意那个系统弹框。");
        } else if (!service.sourceRunning()) {
            setIfChanged(captureStatusText, "内录：已允许。别忘了点上面的「开启来源端」，电脑才连得进来。");
        }
        refreshSourceStatus();
    }

    /**
     * 刷来源端那几行。它跟着 tick 跑，所以每处 setText 都要先比内容——
     * 无条件 setText 会让窗口一直重绘：费电，而且自动化工具永远等不到空闲，界面读不出来。
     */
    private void refreshSourceStatus() {
        if (sourceStatusText == null) return;
        if (service == null) {
            if (sourceButton.isEnabled()) sourceButton.setEnabled(false);
            setIfChanged(sourceButton, "开启来源端");
            setIfChanged(sourceStatusText, "来源端：未开启");
            if (captureButton != null) {
                if (captureButton.isEnabled()) captureButton.setEnabled(false);
                setIfChanged(captureButton, "允许内录");
                setIfChanged(captureStatusText, "内录：未授权");
            }
            return;
        }
        if (!sourceButton.isEnabled()) sourceButton.setEnabled(true);
        boolean running = service.sourceRunning();
        setIfChanged(sourceButton, running ? "关闭来源端" : "开启来源端");
        setIfChanged(sourceStatusText, service.sourceStatusLine()
                + (running ? "　累计被拉 " + service.sourceTotalSessions() + " 次" : ""));

        if (captureButton != null) {
            if (!captureButton.isEnabled()) captureButton.setEnabled(true);
            // 已经授权过就给「重新授权」的措辞：那个授权活不过本次进程，
            // 用户过一会儿回来发现 PC 拉不了内录时，要知道该点的是这个按钮
            setIfChanged(captureButton, service.hasCaptureGrant() ? "重新授权内录" : "允许内录");
            setIfChanged(captureStatusText, service.captureStatusLine());
        }
    }

    /**
     * 地址要枚举网卡，只在服务就绪与开关来源端时刷一次，不跟着 tick 每 500ms 跑。
     * 用户真正需要它的时刻就是「要去电脑那边填地址」的时候。
     */
    private void refreshSourceAddress() {
        if (localAddressText == null || service == null) return;
        String addresses = service.localAddresses();
        setIfChanged(localAddressText, addresses.isEmpty()
                ? "本机地址：没找到可用的 IPv4（是不是没连上网？）"
                : "本机地址：" + addresses);
    }

    // ========== 小工具 ==========

    private static String text(TextInputEditText view) {
        return view.getText() == null ? "" : view.getText().toString();
    }

    private static String percent(float value) {
        return Math.round(value * 100) + "%";
    }

    /**
     * 取当前主题下某个属性的颜色值。
     *
     * 用它而不是写死颜色：同一张卡片在浅色与深色下都要能看清，而这两种模式的「错误色」
     * 与「次要文字色」是两个不同的值；写死一组的后果是另一种模式下字看不清。
     */
    private int themeColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return 0;
    }

    /**
     * 把音量对到滑条的刻度上。
     *
     * Slider 要求设进去的值正好落在 stepSize 的整数倍上，否则直接抛。
     * 存下来的偏好可能来自旧版本或被手改过，所以在这里量化一次——崩溃比音量差一格严重得多。
     */
    private static float snapVolume(float value) {
        float snapped = Math.round(value / VOLUME_STEP) * VOLUME_STEP;
        return Math.max(0f, Math.min(1f, snapped));
    }

    private static void setIfChanged(TextView view, String text) {
        if (view != null && !text.contentEquals(view.getText())) view.setText(text);
    }

    // ========== 权限 ==========

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD_AUDIO) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "录音权限申请结果：" + granted);
        if (granted) {
            // 拿到权限就顺势把来源端开起来：用户点的是「开启来源端」，
            // 权限只是流程中的一道，不该让他为同一件事点两次
            onSourceToggle();
        } else {
            setIfChanged(sourceStatusText, "来源端：没有录音权限，手机没法当音源。");
        }
    }

    @SuppressWarnings("unused")
    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
