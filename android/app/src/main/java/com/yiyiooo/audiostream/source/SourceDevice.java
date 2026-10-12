package com.yiyiooo.audiostream.source;

/**
 * 手机能提供给对端的音频来源条目。
 *
 * 字段口径照抄 PC 端 {@code /api/devices} 的返回体（Name / ID / Default / Flow）：
 * PC 端配置页就是按这几个名字解析的，改一个字母它就不认这条设备。
 */
public final class SourceDevice {

    /** 采集类来源（麦克风、内录）。 */
    public static final String FLOW_INPUT = "input";

    /** 播放类来源（这台机器正在放的声音，环回/内录采集）。手机端对应的是内录。 */
    public static final String FLOW_OUTPUT = "output";

    /** 「跟随对方此刻的系统默认输入设备」这个特殊设备号，与 PC 端同一个字面量。 */
    public static final String ID_DEFAULT_INPUT = "default-input";

    private final String id;
    private final String name;
    private final boolean isDefault;
    private final String flow;

    public SourceDevice(String id, String name, boolean isDefault, String flow) {
        this.id = id;
        this.name = name;
        this.isDefault = isDefault;
        this.flow = flow;
    }

    /** 点单时对端会原样把这个号发回来。 */
    public String id() {
        return id;
    }

    /** 给人看的名字，会出现在 PC 端配置页的下拉里。 */
    public String name() {
        return name;
    }

    /** 是不是「此刻的系统默认」。 */
    public boolean isDefault() {
        return isDefault;
    }

    public String flow() {
        return flow;
    }

    public boolean isInput() {
        return FLOW_INPUT.equals(flow);
    }

    /** 输出类来源（这台机器正在放的声音）。PC 端配置页按它把条目分到「输出」那一组。 */
    public boolean isOutput() {
        return FLOW_OUTPUT.equals(flow);
    }
}
