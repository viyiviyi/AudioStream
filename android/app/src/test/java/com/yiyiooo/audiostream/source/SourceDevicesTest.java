package com.yiyiooo.audiostream.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 来源清单的形状：PC 端下拉里能不能看到手机、看到的条目对不对，全看这里。
 *
 * 这一份在加内录之后改过一次：从前「空串」与「0」算麦克风，现在算内录。
 * 不是随手换的——PC 端自己的跟随项 {@code default} 指的就是系统默认输出（环回），
 * 手机端对应的是内录，两端口径必须一致，否则 PC 端选「跟随默认」会拉到麦克风去。
 */
public class SourceDevicesTest {

    @Test
    public void 清单是内录两条在前麦克风两条在后() {
        List<SourceDevice> devices = SourceDevices.list();

        assertEquals(4, devices.size());
        // 输出在前、输入在后：PC 端配置页按 Flow 分两组显示，
        // 「手机正在放的声音」要与 PC 端那边的输出设备排在同一个位置
        assertTrue("第一条应当是内录（输出类）", devices.get(0).isOutput());
        assertTrue(devices.get(1).isOutput());
        assertTrue(devices.get(2).isInput());
        assertTrue(devices.get(3).isInput());

        assertEquals("default", devices.get(0).id());
        assertTrue("跟随项要标成默认", devices.get(0).isDefault());
        assertEquals(SourceDevices.ID_CAPTURE, devices.get(1).id());
        assertFalse(devices.get(1).isDefault());

        assertEquals(SourceDevice.ID_DEFAULT_INPUT, devices.get(2).id());
        assertTrue("跟随项要标成默认", devices.get(2).isDefault());
        assertEquals(SourceDevices.ID_MIC, devices.get(3).id());
        assertFalse(devices.get(3).isDefault());
    }

    @Test
    public void 麦克风认这两种设备号() {
        assertTrue(SourceDevices.isMicrophone("default-input"));
        assertTrue(SourceDevices.isMicrophone("mic:default"));
        assertTrue(SourceDevices.isMicrophone("MIC:DEFAULT"));
        assertTrue(SourceDevices.isMicrophone("  mic:default  "));
    }

    @Test
    public void 内录认这几种设备号() {
        assertTrue(SourceDevices.isCapture("capture:default"));
        assertTrue(SourceDevices.isCapture("default"));
        assertTrue(SourceDevices.isCapture("DEFAULT"));
        // 老版本或手写客户端会用 0 / 空串表示「随便给一个默认的」；
        // PC 端自己的跟随项 default 指的是系统默认输出，所以这里也归内录
        assertTrue(SourceDevices.isCapture("0"));
        assertTrue(SourceDevices.isCapture(""));
        assertTrue(SourceDevices.isCapture("  capture:default  "));
    }

    @Test
    public void 同一个设备号不会同时算两路来源() {
        // 点单走哪条路不能是「实现细节」：两路都认同一个号，行为就取决于谁的判断写在前面，
        // 这种不确定在现场表现为「有时候拉到的是麦克风，有时候是内录」，极难查
        String[] ids = {"default-input", "mic:default", "default", "capture:default", "0", ""};
        for (String id : ids) {
            assertFalse("「" + id + "」不该同时算麦克风与内录",
                    SourceDevices.isMicrophone(id) && SourceDevices.isCapture(id));
        }
    }

    @Test
    public void 别的设备号一概不认() {
        assertFalse(SourceDevices.isKnown(null));
        assertFalse(SourceDevices.isKnown("speaker:0"));
        assertFalse(SourceDevices.isKnown("{0.0.0.00000000}.{abc}"));
        // 给 PC 端看的「有什么」那句话里要能把两路都数出来
        String summary = SourceDevices.availableSummary();
        assertTrue(summary, summary.contains("内录"));
        assertTrue(summary, summary.contains("麦克风"));
    }
}
