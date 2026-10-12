package com.yiyiooo.audiostream.source;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 本机的 IPv4 地址，给 {@code /api/info} 的 Addresses 用。
 *
 * PC 端把这份清单显示在界面上，方便用户确认「手机到底在哪几个网段上」——
 * 手机常常同时挂着 Wi-Fi 和运营商网络，谁也不知道该填哪个。
 *
 * 过滤掉的几类及其原因：
 * <ul>
 *   <li>回环（127.0.0.1）：对端拿着它连的是它自己。</li>
 *   <li>IPv6：PC 端的连接代码只走 IPv4，列出来只会让人填错。</li>
 *   <li>链路本地（169.254.x.x）：那是 DHCP 没拿到地址时的临时地址，同网段也未必通。</li>
 *   <li>没启用的网卡：插着但没连上的接口，读出来是历史地址。</li>
 * </ul>
 */
public final class LocalAddresses {

    private LocalAddresses() {
    }

    public static List<String> list() {
        List<String> out = new ArrayList<String>();
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            // 拿不到网卡清单不该让整个 /api/info 失败：对端至少还能靠他填的那个 IP 连过来
            return out;
        }
        if (interfaces == null) return out;

        while (interfaces.hasMoreElements()) {
            NetworkInterface nic = interfaces.nextElement();
            try {
                if (!nic.isUp() || nic.isLoopback()) continue;
            } catch (SocketException e) {
                continue;
            }
            Enumeration<InetAddress> addresses = nic.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address)) continue;
                if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                String text = address.getHostAddress();
                if (text != null && !text.isEmpty() && !out.contains(text)) {
                    out.add(text);
                }
            }
        }
        // 顺序固定下来：同一台手机两次请求给出一致的清单，界面才不会看起来在跳
        Collections.sort(out);
        return out;
    }
}
