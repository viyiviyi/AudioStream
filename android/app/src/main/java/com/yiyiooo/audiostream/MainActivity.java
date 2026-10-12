package com.yiyiooo.audiostream;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.yiyiooo.audiostream.discovery.DeviceInfo;
import com.yiyiooo.audiostream.discovery.PcInfo;
import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.pull.PullState;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：上半截是播放端（填 PC 地址 → 刷设备列表 → 选来源 → 连接/断开），
 * 下半截是来源端（把手机变成一台 AudioStream 机器，让 PC 反过来拉它的麦克风）。
 *
 * 布局纯代码构建，不引 AndroidX，和工程里其余部分保持一致。前台服务在后台把声音接住，
 * 这里只在屏幕上照实反映；锁屏或切后台再回来，读到的状态与服务是同一份。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 40;

    /** 点「开启来源端」时当场要录音权限：没这个权限手机发不出声音。 */
    private static final int REQ_RECORD_AUDIO = 41;

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

    // --- UI ---
    private EditText hostInput;
    private Spinner deviceSpinner;
    private Button discoverButton;
    private Button connectButton;
    private TextView statusText;
    private TextView detailText;
    private TextView formatText;
    private TextView countersText;
    private TextView sourceAddressText;
    private Button sourceButton;
    private TextView sourceStatusText;
    private TextView logText;
    private ScrollView logScroll;

    private final List<DeviceInfo> devices = new ArrayList<>();
    private final List<String> deviceLabels = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean tickRunning;
    private long lastCounterLogMs;
    private long lastWrittenBytes = -1;

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
        buildUi();
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

    /** 服务连上后才有的动作：回填上次的地址。 */
    private void onServiceReady() {
        AppConfig config = AppConfig.load(this);
        if (!config.host().isEmpty()) {
            hostInput.setText(config.host());
        }
        updateButtonState();
        refreshSourceStatus();
        refreshSourceAddress();
    }

    // ========== UI 构建 ==========

    private void buildUi() {
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        float sp = 15;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("音频流转 · 播放端 " + BuildConfig.VERSION_NAME, 18, Gravity.START);
        root.addView(title);

        MachineIdentity identity = MachineIdentity.load(this);
        root.addView(text("本机：" + identity.name(), 12, Gravity.START));

        // 地址行
        hostInput = new EditText(this);
        hostInput.setHint("对方电脑 IP（例如 192.168.1.10）");
        hostInput.setTextSize(sp);
        hostInput.setSingleLine(true);
        root.addView(hostInput);

        discoverButton = new Button(this);
        discoverButton.setText("刷新设备列表");
        discoverButton.setOnClickListener(v -> onDiscover());
        root.addView(discoverButton);

        // 设备选择
        root.addView(text("选择来源设备（输出=电脑在放的 / 输入=电脑麦克风）", 13, Gravity.START));
        deviceSpinner = new Spinner(this);
        deviceSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"先刷新设备列表"}));
        root.addView(deviceSpinner);

        connectButton = new Button(this);
        connectButton.setText("连接");
        connectButton.setTextSize(sp);
        connectButton.setOnClickListener(v -> onConnectToggle());
        root.addView(connectButton);

        statusText = text("未连接", 16, Gravity.START);
        root.addView(statusText);

        detailText = text("", 13, Gravity.START);
        detailText.setTextColor(0xFFC00000);
        root.addView(detailText);

        formatText = text("", 13, Gravity.START);
        root.addView(formatText);

        countersText = text("", 12, Gravity.START);
        root.addView(countersText);

        // --- 来源端：让 PC 反过来拉这台手机 ---
        root.addView(text("— 让电脑拉这台手机的声音 —", 13, Gravity.START));

        sourceAddressText = text("手机地址：读取中…", 12, Gravity.START);
        root.addView(sourceAddressText);

        sourceButton = new Button(this);
        sourceButton.setText("开启来源端");
        sourceButton.setTextSize(sp);
        // 后台服务没绑上之前点了也没有对象可操作，先灰着，服务一到就打开
        sourceButton.setEnabled(false);
        sourceButton.setOnClickListener(v -> onSourceToggle());
        root.addView(sourceButton);

        sourceStatusText = text("来源端：未开启", 12, Gravity.START);
        root.addView(sourceStatusText);

        logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setGravity(Gravity.START);
        logText.setTextColor(0xFF885500);
        logScroll.addView(logText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        logScroll.setPadding(0, pad, 0, 0);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateButtonState();
    }

    private TextView text(String s, int size, int gravity) {
        TextView view = new TextView(this);
        view.setText(s);
        view.setTextSize(size);
        view.setGravity(gravity);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    // ========== 交互 ==========

    private void onDiscover() {
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            detailText.setText("请先填对方电脑 IP。");
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
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            detailText.setText("请先填对方电脑 IP。");
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
        // 服务就绪就必须把按钮打开：buildUi 里第一次调用它时服务还没绑上，
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

    // ========== 来源端（让 PC 反过来拉这台手机） ==========

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
        if (!service.hasRecordPermission()) {
            // 没权限就先要，不能「先开服务再补权限」：Android 会在服务真去读麦克风那一刻直接拒掉
            Log.i(TAG, "没有录音权限，先申请");
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

    /**
     * 刷来源端那两行。它跟着 tick 跑，所以每处 setText 都要先比内容——
     * 无条件 setText 会让窗口一直重绘：费电，而且自动化工具永远等不到空闲，界面读不出来。
     */
    private void refreshSourceStatus() {
        if (sourceStatusText == null) return;
        if (service == null) {
            if (sourceButton.isEnabled()) sourceButton.setEnabled(false);
            setIfChanged(sourceButton, "开启来源端");
            setIfChanged(sourceStatusText, "来源端：未开启");
            return;
        }
        if (!sourceButton.isEnabled()) sourceButton.setEnabled(true);
        boolean running = service.sourceRunning();
        setIfChanged(sourceButton, running ? "关闭来源端" : "开启来源端");
        setIfChanged(sourceStatusText, service.sourceStatusLine()
                + (running ? "　累计被拉 " + service.sourceTotalSessions() + " 次" : ""));
    }

    /**
     * 地址要枚举网卡，只在服务就绪与开关来源端时刷一次，不跟着 tick 每 500ms 跑。
     * 用户真正需要它的时刻就是「要去 PC 那边填地址」的时候。
     */
    private void refreshSourceAddress() {
        if (sourceAddressText == null || service == null) return;
        String addresses = service.localAddresses();
        setIfChanged(sourceAddressText, addresses.isEmpty()
                ? "手机地址：没找到可用的 IPv4（是不是没连上网？）"
                : "手机地址：" + addresses + "　（填到 PC 端的「电脑 IP」里）");
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}