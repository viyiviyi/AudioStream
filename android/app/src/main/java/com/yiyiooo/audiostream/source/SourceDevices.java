package com.yiyiooo.audiostream.source;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机能提供的来源清单。
 *
 * 与 PC 端不同，这里不做声卡枚举：手机能当来源的东西是固定的（内录 + 麦克风），
 * 而真正会失败的是「能不能打开」——那是点单时的事，打不开就回 {@code /Reject/unavailable}。
 * 比在列表里凭空少一项、让用户在 PC 端对着空列表猜原因要清楚得多。
 *
 * 同理，没授权时也照样列出：没给录音权限时点麦克风回 {@code /Reject/denied}，
 * 没给内录授权时点内录也回 {@code denied}。协议里 denied 就是「对方没放行本机拉这个设备」，
 * 说的正是这件事。
 *
 * 顺序是**输出在前、输入在后**：PC 端配置页按 Flow 分两组显示，而「手机正在放的声音」与
 * 「让对方电脑在放的声音」是同一个位置（PC 端那边的输出设备也是环回），排在前面才与它对齐。
 */
public final class SourceDevices {

    /** 麦克风的具体设备号。点单时对端会原样发回来。 */
    public static final String ID_MIC = "mic:default";

    /** 内录的具体设备号。 */
    public static final String ID_CAPTURE = "capture:default";

    /** 界面上给这几条用的名字。 */
    public static final String NAME_CAPTURE_FOLLOW = "手机内录（跟随系统默认输出）";
    public static final String NAME_CAPTURE = "手机内录";
    public static final String NAME_MIC_FOLLOW = "手机麦克风（跟随系统默认）";
    public static final String NAME_MIC = "手机麦克风";

    private SourceDevices() {
    }

    public static List<SourceDevice> list() {
        List<SourceDevice> devices = new ArrayList<SourceDevice>(4);
        // 输出组：内录。选「跟随系统默认输出」由手机这边解析——对应 PC 端那个同名跟随项的语义，
        // 手机换了正在播放的东西，PC 端不必回来重配
        devices.add(new SourceDevice("default", NAME_CAPTURE_FOLLOW,
                true, SourceDevice.FLOW_OUTPUT));
        devices.add(new SourceDevice(ID_CAPTURE, NAME_CAPTURE, false, SourceDevice.FLOW_OUTPUT));
        // 输入组：麦克风
        devices.add(new SourceDevice(SourceDevice.ID_DEFAULT_INPUT, NAME_MIC_FOLLOW,
                true, SourceDevice.FLOW_INPUT));
        devices.add(new SourceDevice(ID_MIC, NAME_MIC, false, SourceDevice.FLOW_INPUT));
        return devices;
    }

    /**
     * 点单来的设备号是不是麦克风。
     *
     * 认这两种：{@code default-input}（对方要「本机的默认输入」）与 {@code mic:default}（我们自己报出去的）。
     * 大小写不敏感：设备号是对端原样回传的，不同版本可能大小写不同。
     *
     * 注意这里**不认**空串与 {@code 0}：那两个现在归内录（见 {@link #isCapture}）。
     * 从前没有内录时它们算麦克风，加了内录之后「默认」应当指默认输出——PC 端自己的跟随项
     * {@code default} 指的就是系统默认输出。两边口径必须一致。
     */
    public static boolean isMicrophone(String id) {
        if (id == null) return false;
        String trimmed = id.trim();
        return SourceDevice.ID_DEFAULT_INPUT.equalsIgnoreCase(trimmed)
                || ID_MIC.equalsIgnoreCase(trimmed);
    }

    /**
     * 点单来的设备号是不是内录。
     *
     * 认这几种：空串、{@code 0}（老版本或手写客户端的「随便给一个」，按默认输出算）、
     * {@code default}（PC 端的跟随项：系统默认输出）以及我们自己报出去的 {@code capture:default}。
     */
    public static boolean isCapture(String id) {
        if (id == null) return false;
        String trimmed = id.trim();
        return trimmed.isEmpty()
                || "0".equals(trimmed)
                || "default".equalsIgnoreCase(trimmed)
                || ID_CAPTURE.equalsIgnoreCase(trimmed);
    }

    /** 这个设备号我们认不认。两个都不认就该回 nodevice，并把「有什么」告诉对方。 */
    public static boolean isKnown(String id) {
        return isMicrophone(id) || isCapture(id);
    }

    /** 给拒绝原因用的一句话：这台手机此刻能给出什么。 */
    public static String availableSummary() {
        return NAME_CAPTURE_FOLLOW + "、" + NAME_MIC_FOLLOW;
    }
}
