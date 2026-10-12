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
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
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
import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.pull.PullState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：一屏管两头——上半截是播放端（填电脑地址 → 刷设备列表 → 选来源 → 连接/断开 →
 * 调这一路的音量），下半截是来源端（把手机变成一台 AudioStream 机器，让电脑反过来拉）。
 *
 * 界面走 Material 组件与 XML 布局（res/layout/activity_main.xml）；协议层与音频层仍然只用平台 API，
 * 因为那两层要逐字节对齐 C# 端，多一层封装只会多一个出错的地方。
 *
 * 这里只做三件事：把控件找出来接上回调、把服务报上来的状态照实显示、把用户的选择落盘。
 * 音频链路本身在前台服务里，锁屏或切后台再回来，读到的状态与服务是同一份。
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
            updateButtonState();
        }
    };

    // --- 播放端控件 ---
    private TextInputLayout hostLayout;
    private TextInputEditText hostInput;
    private MaterialButton discoverButton;
    private Spinner deviceSpinner;
    private MaterialButton connectButton;
    private TextView statusText;
    private TextView detailText;
    private TextView formatText;
    private TextView countersText;
    private Slider volumeSlider;
    private TextView volumeText;

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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean tickRunning;
    private long lastCounterLogMs;
    private long lastWrittenBytes = -1;

    /** 音量滑条的步长。取值与布局里 Slider 的 android:stepSize 必须一致，见 applyVolume。 */
    private static final float VOLUME_STEP = 0.05f;

    // ========== 服务回调（主线程） ==========

    private final AudioStreamService.Listener serviceListener = new AudioStreamService.Listener() {
        @Override
        public void onState(PullState state, String detail) {
            statusText.setText(state.label());
            detailText.setText(detail == null ? "" : detail);
            updateButtonState();
            log("状态：" + state.label() + (detail == null || detail.isEmpty() ? "" : " — " + detail));
        }

        @Override
        public void onFormat(AudioFormat format) {
            formatText.setText(format == null ? "" : "格式：" + format.describe());
            log("格式：" + (format == null ? "—" : format.describe()));
        }

        @Override
        public void onPlaybackFailed(String reason) {
            detailText.setText(reason);
            log("播放失败：" + reason);
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

            // 列表可能为空：对端一台设备都没接时可以显示提示
            if (deviceLabels.isEmpty()) {
                deviceLabels.add("（对方没有可选设备）");
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this,
                    android.R.layout.simple_spinner_dropdown_item, deviceLabels);
            deviceSpinner.setAdapter(adapter);
            restoreSelection();
            updateButtonState();

            String pcBrief = (info == null ? "" :
                    info.pcName().isEmpty() ? info.machineId() : info.pcName());
            log("发现 " + devices.size() + " 个设备" + (pcBrief.isEmpty() ? "" : "（" + pcBrief + "）"));
            if (devices.isEmpty()) {
                detailText.setText("对方没有可选设备：检查它的声卡是否可用。");
            }
        }

        @Override
        public void onDiscoveryFailed(String reason) {
            detailText.setText(reason);
            log("发现失败：" + reason);
            updateButtonState();
        }
    };

    // ========== 生命周期 ==========

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupToolbar();
        setupVolume();
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

    /** 服务连上后才有的动作：回填上次的地址与音量。 */
    private void onServiceReady() {
        AppConfig config = AppConfig.load(this);
        if (!config.host().isEmpty()) {
            hostInput.setText(config.host());
        }
        updateButtonState();
        refreshSourceStatus();
        refreshSourceAddress();
    }

    // ========== 控件装配 ==========

    private void bindViews() {
        hostLayout = findViewById(R.id.hostLayout);
        hostInput = findViewById(R.id.hostInput);
        discoverButton = findViewById(R.id.discoverButton);
        deviceSpinner = findViewById(R.id.deviceSpinner);
        connectButton = findViewById(R.id.connectButton);
        statusText = findViewById(R.id.statusText);
        detailText = findViewById(R.id.detailText);
        formatText = findViewById(R.id.formatText);
        countersText = findViewById(R.id.countersText);
        volumeSlider = findViewById(R.id.volumeSlider);
        volumeText = findViewById(R.id.volumeText);

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
        connectButton.setOnClickListener(v -> onConnectToggle());
        sourceButton.setOnClickListener(v -> onSourceToggle());
        captureButton.setOnClickListener(v -> onCaptureRequest());

        // 后台服务没绑上之前点了也没有对象可操作，先灰着，服务一到就打开
        sourceButton.setEnabled(false);
        captureButton.setEnabled(false);

        updateButtonState();
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        MachineIdentity identity = MachineIdentity.load(this);
        // 副标题给版本号与本机名字：排查时第一件要问的就是「你装的是哪一版、这台机器是谁」
        toolbar.setSubtitle("v" + BuildConfig.VERSION_NAME + "　·　本机 " + identity.name());
    }

    // ---------- 播放音量（只作用于本应用输出，不碰系统媒体音量） ----------

    private void setupVolume() {
        applyVolume(AppConfig.loadVolume(this));
        volumeSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) return; // 代码设的值不必再回写一次
            applyVolume(value);
        });
    }

    private void applyVolume(float value) {
        // Slider 要求设进去的值正好落在 stepSize 的整数倍上，否则直接抛。
        // 存的偏好可能来自旧版本或被手改过，所以在这里量化一次再设——崩溃比音量差一格严重得多。
        float snapped = Math.round(value / VOLUME_STEP) * VOLUME_STEP;
        snapped = Math.max(0f, Math.min(1f, snapped));
        if (Math.abs(volumeSlider.getValue() - snapped) > 1e-4) {
            volumeSlider.setValue(snapped);
        }
        volumeText.setText(Math.round(snapped * 100) + "%");
        if (service != null) {
            service.setVolume(snapped); // 服务负责作用到当前会话并落盘
        } else {
            AppConfig.saveVolume(this, snapped); // 还没连上服务也不能丢用户的选择
        }
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

    // ========== 交互 ==========

    private void onDiscover() {
        String host = hostInput.getText() == null ? "" : hostInput.getText().toString().trim();
        hostLayout.setError(null);
        if (host.isEmpty()) {
            showFieldError("请先填电脑地址。");
            return;
        }
        detailText.setText("正在发现…");
        if (service != null) service.discover(host);
    }

    private void onConnectToggle() {
        Log.i(TAG, "点连接：service=" + (service == null ? "null" : "就绪")
                + " state=" + (service == null ? "-" : service.state()));
        if (service == null) return;
        if (service.state() != PullState.IDLE && service.state() != PullState.STOPPED
                && service.state() != PullState.FAILED) {
            // 正在连接或播放中：这个按钮就是「断开」
            service.disconnect();
            return;
        }
        connectSelected();
    }

    private void connectSelected() {
        String host = hostInput.getText() == null ? "" : hostInput.getText().toString().trim();
        hostLayout.setError(null);
        if (host.isEmpty()) {
            showFieldError("请先填电脑地址。");
            return;
        }
        int index = deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= devices.size()) {
            detailText.setText("请先刷新并选择一个来源设备。");
            Log.w(TAG, "连接被拦下：下拉选项 " + index + " 对不上设备表（" + devices.size() + " 项）");
            return;
        }
        // 注意：deviceLabels 是「输出在前、输入在后」的顺序，与 devices 一 一对应，
        // 这里重建 labels 时对空条目没有跳过，所以 index 就是 devices 的下标。
        DeviceInfo device = devices.get(index);
        AppConfig.saveDevice(this, device.id(), device.name());
        Log.i(TAG, "开始连接 " + host + " 的设备 " + device.name() + "（" + device.id() + "）");
        ensureForegroundService();
        service.connect(host, device.id(), device.name());
    }

    /** 地址填错时把红字放在输入框下面而不是状态区：用户的视线本来就在那一格上。 */
    private void showFieldError(String message) {
        hostLayout.setError(message);
        detailText.setText(message);
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

    private void restoreSelection() {
        AppConfig config = AppConfig.load(this);
        if (config.deviceId().isEmpty()) return;
        for (int i = 0; i < devices.size(); i++) {
            if (config.deviceId().equals(devices.get(i).id())) {
                deviceSpinner.setSelection(i);
                return;
            }
        }
    }

    private void updateButtonState() {
        if (service == null) {
            connectButton.setText("连接");
            connectButton.setEnabled(false);
            return;
        }
        // 服务就绪就必须把按钮打开：bindViews 里第一次调用它时服务还没绑上，
        // 那时按钮被禁用了；这里若不显式打开，它会一直灰着——用户点了没反应，
        // 而界面上看不出任何原因（这一条只有真把界面跑起来才会暴露）。
        connectButton.setEnabled(true);
        PullState s = service.state();
        if (s == PullState.CONNECTING || s == PullState.HANDSHAKING
                || s == PullState.NEGOTIATING || s == PullState.PLAYING) {
            connectButton.setText("断开");
        } else {
            connectButton.setText("连接");
        }
        // 发现中不禁用连接按钮，但没选中设备时连接会提示
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

    private void refreshCounters() {
        if (service == null) return;
        String text = "缓冲 " + service.bufferedMs() + "ms"
                + "　欠载 " + service.underruns()
                + "　丢字节 " + service.droppedBytes()
                + "　写 " + (service.writtenBytes() / 1024) + "KB";
        // 每 500ms 无条件 setText 会让这个窗口一直在重绘：白耗电，而且窗口永远不 idle，
        // uiautomator 之类的自动化工具会一直等不到，联调时连界面都读不出来。内容没变就不动它。
        if (!text.contentEquals(countersText.getText())) {
            countersText.setText(text);
        }
        // 计数进 logcat 但降频：写入字节在涨就是「音频真的送到声卡了」的直接证据，
        // 每 2 秒一条足够看趋势，又不至于把日志刷爆。
        long written = service.writtenBytes();
        long now = System.currentTimeMillis();
        if (written != lastWrittenBytes && now - lastCounterLogMs >= 2000) {
            lastWrittenBytes = written;
            lastCounterLogMs = now;
            Log.i(TAG, text);
        }
        refreshSourceStatus();
    }

    // ========== 来源端（让电脑反过来拉这台手机） ==========

    private void onSourceToggle() {
        if (service == null) {
            detailText.setText("后台服务还没就绪，稍等一下再点。");
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
            detailText.setText(error);
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
            detailText.setText("后台服务还没就绪，稍等一下再点。");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            detailText.setText("这台手机的 Android 版本太老，内录需要 Android 10 及以上。");
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            detailText.setText("这台设备没有提供内录能力。");
            return;
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
            log("请在系统弹框里点「立即开始」，手机才会把正在放的声音送出去");
        } catch (Exception e) {
            // 部分定制系统会把这一步拦下来（缺少系统组件、被安全策略禁止）
            detailText.setText("打不开内录授权框：" + e);
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
            detailText.setText("内录没被授权：想要电脑拉手机正在放的声音，需要同意那个系统弹框。");
        } else if (!service.sourceRunning()) {
            detailText.setText("内录已允许。别忘了点上面的「开启来源端」，电脑才连得进来。");
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
            detailText.setText("没有录音权限，手机没法当音源。");
        }
    }

    @SuppressWarnings("unused")
    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
