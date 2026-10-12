package com.yiyiooo.audiostream.discovery;

/**
 * 对端的一个音频端点，来自 {@code GET /api/devices}。
 *
 * 与 C# 端 {@code DeviceInfo} 一一对应。这里不去「修正」对端给的任何东西：
 * {@link #id()} 是拿给对端去开声卡用的设备号，必须是原样透传，
 * 自己加工一下（截断、转小写、换成序号）就会变成一个对端认不出的设备。
 */
public final class DeviceInfo {

    public static final String FLOW_OUTPUT = "output";
    public static final String FLOW_INPUT = "input";

    private final String name;
    private final String id;
    private final boolean isDefault;
    private final String flow;

    public DeviceInfo(String name, String id, boolean isDefault, String flow) {
        this.name = name == null ? "" : name;
        this.id = id == null ? "" : id;
        this.isDefault = isDefault;
        this.flow = flow == null || flow.isEmpty() ? FLOW_OUTPUT : flow;
    }

    /** 设备名，给人看的。 */
    public String name() {
        return name;
    }

    /** 设备号，原样透传给对端。 */
    public String id() {
        return id;
    }

    /** 是不是对端此刻的系统默认设备。 */
    public boolean isDefault() {
        return isDefault;
    }

    /** {@link #FLOW_OUTPUT}（扬声器环回）或 {@link #FLOW_INPUT}（麦克风）。 */
    public String flow() {
        return flow;
    }

    public boolean isOutput() {
        return FLOW_OUTPUT.equals(flow);
    }

    public boolean isInput() {
        return FLOW_INPUT.equals(flow);
    }

    /** 界面上一行要显示的字，默认设备带标注。 */
    public String displayName() {
        return isDefault ? name + "（默认）" : name;
    }

    @Override
    public String toString() {
        return displayName() + " [" + flow + "] " + id;
    }
}
