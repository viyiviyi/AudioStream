package com.yiyiooo.audiostream.discovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对端那台 AudioStream 机器的自述，来自 {@code GET /api/info}。
 *
 * 字段与 C# 端 {@code DefaultController.Info()} 一一对应。之所以要这个接口而不是「HTTP 端口
 * 一定等于音频端口减 300」这种推断：音频端口是被占用就往后顺延的（12670~12689），
 * 唯一可靠的来源就是对端自己说的 {@link #tcpPort}。
 */
public final class PcInfo {

    private final String machineId;
    private final String pcName;
    private final int tcpPort;
    private final int httpPort;
    private final String version;
    private final List<String> addresses;

    public PcInfo(String machineId, String pcName, int tcpPort, int httpPort, String version,
                  List<String> addresses) {
        this.machineId = machineId == null ? "" : machineId;
        this.pcName = pcName == null ? "" : pcName;
        this.tcpPort = tcpPort;
        this.httpPort = httpPort;
        this.version = version == null ? "" : version;
        this.addresses = Collections.unmodifiableList(
                addresses == null ? new ArrayList<String>() : new ArrayList<String>(addresses));
    }

    /** 对端身份，32 位十六进制。对端靠它认出「这是不是我自己」，界面上也用它区分设备。 */
    public String machineId() {
        return machineId;
    }

    /** 对端给人看的名字（PC 上是计算机名）。 */
    public String pcName() {
        return pcName;
    }

    /** 音频 TCP 端口。0 表示对端没给（老版本），调用方只能按 12670 起的区间去试。 */
    public int tcpPort() {
        return tcpPort;
    }

    /** 对端 HTTP 端口，正常就是 12570。 */
    public int httpPort() {
        return httpPort;
    }

    /** 对端程序版本。 */
    public String version() {
        return version;
    }

    /** 对端的本机 IPv4 地址列表，只用于显示与排查。 */
    public List<String> addresses() {
        return addresses;
    }

    @Override
    public String toString() {
        return "PcInfo{" + pcName + " / " + machineId + " / http:" + httpPort + " / tcp:" + tcpPort
                + " / v" + version + "}";
    }
}
