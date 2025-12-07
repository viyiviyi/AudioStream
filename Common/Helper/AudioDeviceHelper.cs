using CSCore.CoreAudioAPI;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Common.Helper
{
    public class AudioDeviceHelper
    {
        public static string GetDefaultOutputDeviceId()
        {
            using (var enumerator = new MMDeviceEnumerator())
            {
                using (var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Console))
                {
                    if (device != null)
                    {
                        return device.DeviceID;
                    }
                    else
                    {
                        return null; // 或者抛出异常，表示没有找到默认设备
                    }
                }
            }

        }

        public static MMDevice GetDeviceById(string deviceId)
        {
            using (var enumerator = new MMDeviceEnumerator())
            {
                try
                {
                    return enumerator.GetDevice(deviceId);
                }
                catch (CoreAudioAPIException ex)
                {
                    Console.WriteLine($"Error getting device with ID '{deviceId}': {ex.Message}");
                    return null;
                }
            }
        }

        public static MMDeviceCollection OutputDevices()
        {
            using (var deviceEnumerator = new MMDeviceEnumerator())
            {
                // 获取所有音频设备
                var devices = deviceEnumerator.EnumAudioEndpoints(DataFlow.Render, DeviceState.Active);
                return devices;
            }
        }
        public static MMDeviceCollection InputDevices()
        {
            using (var deviceEnumerator = new MMDeviceEnumerator())
            {
                // 获取所有音频设备
                var devices = deviceEnumerator.EnumAudioEndpoints(DataFlow.Capture, DeviceState.Active);
                return devices;
            }
        }
    }
}
