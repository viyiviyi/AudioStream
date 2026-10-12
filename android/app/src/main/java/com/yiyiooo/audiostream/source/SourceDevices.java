package com.yiyiooo.audiostream.source;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机能提供的来源清单。
 *
 * 与 PC 端不同，这里不做声卡枚举：手机能当来源的东西是固定的（麦克风；内录稍后加入），
 * 而真正会失败的是「能不能打开」——那是点单时的事，打不开就回 {@code /Reject/unavailable}。
 * 比在列表里凭空少一项、让用户在 PC 端对着空列表猜原因要清楚得多。
 *
 * 同理，没给录音权限时也照样列出麦克风，点单时回 {@code /Reject/denied}：
 * 协议里 denied 就是「对方没放行本机拉这个设备」，说的正是这件事。
 */
public final class SourceDevices {

    /** 麦克风的具体设备号。点单时对端会原样发回来。 */
    public static final String ID_MIC = "mic:default";

    /** 界面上给这两条用的名字。 */
    public static final String NAME_MIC_FOLLOW = "手机麦克风（跟随系统默认）";
    public static final String NAME_MIC = "手机麦克风";

    private SourceDevices() {
    }

    /** 输出在前、输入在后——与 PC 端枚举设备时的顺序口径一致（本清单目前只有输入）。 */
    public static List<SourceDevice> list() {
        List<SourceDevice> devices = new ArrayList<SourceDevice>(2);
        // 「跟随后」：PC 端选的是「对方的系统默认输入」，由手机这边解析，
        // 对方换了默认麦克风之后自动跟着走，不必回 PC 端重配
        devices.add(new SourceDevice(SourceDevice.ID_DEFAULT_INPUT, NAME_MIC_FOLLOW,
                true, SourceDevice.FLOW_INPUT));
        devices.add(new SourceDevice(ID_MIC, NAME_MIC, false, SourceDevice.FLOW_INPUT));
        return devices;
    }

    /**
     * 点单来的设备号是不是我们能给的麦克风。
     *
     * 认这几种：空串、{@code 0}、{@code default-input}（都是「就用对方的默认输入」的意思）、
     * 以及我们自己在清单里报出去的 {@code mic:default}。大小写不敏感：
     * 设备号是对端原样回传的，不同版本可能大小写不同。
     */
    public static boolean isMicrophone(String id) {
        if (id == null) return false;
        String trimmed = id.trim();
        return trimmed.isEmpty()
                || "0".equals(trimmed)
                || SourceDevice.ID_DEFAULT_INPUT.equalsIgnoreCase(trimmed)
                || ID_MIC.equalsIgnoreCase(trimmed);
    }
}
