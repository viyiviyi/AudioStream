package com.yiyiooo.audiostream.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 来源清单的形状：PC 端下拉里能不能看到手机、看到的条目对不对，全看这里。
 */
public class SourceDevicesTest {

    @Test
    public void 清单里麦克风两条且都标成输入() {
        List<SourceDevice> devices = SourceDevices.list();

        assertEquals(2, devices.size());
        for (SourceDevice device : devices) {
            assertTrue(device.name(), device.isInput());
        }
        assertEquals(SourceDevice.ID_DEFAULT_INPUT, devices.get(0).id());
        assertTrue("跟随项要标成默认", devices.get(0).isDefault());
        assertEquals(SourceDevices.ID_MIC, devices.get(1).id());
        assertFalse(devices.get(1).isDefault());
    }

    @Test
    public void 跟随项与具体设备号都认() {
        assertTrue(SourceDevices.isMicrophone("default-input"));
        assertTrue(SourceDevices.isMicrophone("mic:default"));
        assertTrue(SourceDevices.isMicrophone("MIC:DEFAULT"));
        // PC 端老版本会把「默认」写成 0 或空串
        assertTrue(SourceDevices.isMicrophone("0"));
        assertTrue(SourceDevices.isMicrophone(""));
        assertTrue(SourceDevices.isMicrophone("  mic:default  "));
    }

    @Test
    public void 别的设备号不认() {
        assertFalse(SourceDevices.isMicrophone(null));
        assertFalse(SourceDevices.isMicrophone("default"));
        assertFalse(SourceDevices.isMicrophone("speaker:0"));
        assertFalse(SourceDevices.isMicrophone("{0.0.0.00000000}.{abc}"));
    }
}
