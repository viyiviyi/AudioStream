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
        /// <summary>
        /// 来源设备号里「跟随系统默认输出设备（扬声器 / 耳机）」的特殊值，走环回采集。
        /// 这个名字由被拉取侧解析，所以配置里选它，选的是「对方那台机器此刻的默认输出设备」。
        /// </summary>
        public const string DefaultOutputSourceId = "default";

        /// <summary>来源设备号里「跟随系统默认输入设备（麦克风 / 线路输入）」的特殊值。</summary>
        public const string DefaultInputSourceId = "default-input";

        /// <summary>
        /// 这两个特殊设备号对应的中文名字，解析不出来时返回 null。
        /// 界面显示与落盘的设备名共用同一份，免得两边写的不一样。
        /// </summary>
        public static string SourceDisplayName(string sourceId)
        {
            if (string.Equals(sourceId, DefaultOutputSourceId, StringComparison.OrdinalIgnoreCase))
                return "系统默认输出设备";
            if (string.Equals(sourceId, DefaultInputSourceId, StringComparison.OrdinalIgnoreCase))
                return "系统默认输入设备";
            return null;
        }

        /// <summary>
        /// 把配置里的来源设备号解析成此刻真实存在的设备号。
        /// "default" 取此刻的系统默认输出设备（环回），"default-input" 取此刻的系统默认输入设备，
        /// 其它值原样返回。
        ///
        /// 解析不出来时返回 null 并把原因写进 <paramref name="error"/>。
        /// 调用方必须拿这个 null 去拒绝，绝不能把空设备号交给声卡——那会抛出更难懂的异常。
        /// </summary>
        public static string ResolveSourceDeviceId(string sourceId, out string error)
        {
            error = null;
            if (string.IsNullOrWhiteSpace(sourceId))
            {
                error = "没有指定来源设备";
                return null;
            }
            if (string.Equals(sourceId, DefaultOutputSourceId, StringComparison.OrdinalIgnoreCase))
            {
                var deviceId = GetDefaultOutputDeviceId();
                if (string.IsNullOrEmpty(deviceId)) error = "本机此刻没有可用的默认输出设备（扬声器 / 耳机）";
                return deviceId;
            }
            if (string.Equals(sourceId, DefaultInputSourceId, StringComparison.OrdinalIgnoreCase))
            {
                var deviceId = GetDefaultInputDeviceId();
                if (string.IsNullOrEmpty(deviceId)) error = "本机此刻没有可用的默认输入设备（麦克风 / 线路输入）";
                return deviceId;
            }
            return sourceId;
        }

        /// <summary>系统默认输出设备（扬声器 / 耳机）的设备号，找不到返回 null。</summary>
        public static string GetDefaultOutputDeviceId()
        {
            return GetDefaultDeviceId(DataFlow.Render);
        }

        /// <summary>
        /// 系统默认输入设备（麦克风 / 线路输入）的设备号。
        /// 机器上没有任何录音设备时，系统调用本身会抛异常——这里按「没有默认输入」处理，
        /// 不能让它把整个设备列表一起带崩。
        /// </summary>
        public static string GetDefaultInputDeviceId()
        {
            return GetDefaultDeviceId(DataFlow.Capture);
        }

        private static string GetDefaultDeviceId(DataFlow flow)
        {
            try
            {
                using (var enumerator = new MMDeviceEnumerator())
                using (var device = enumerator.GetDefaultAudioEndpoint(flow, Role.Console))
                {
                    return device == null ? null : device.DeviceID;
                }
            }
            catch (CoreAudioAPIException)
            {
                return null;
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

        public static MMDevice GetDeviceByName(string deviceName)
        {
            using (var enumerator = new MMDeviceEnumerator())
            {
                try
                {
                    var device = enumerator.EnumAudioEndpoints(DataFlow.Render, DeviceState.Active).FirstOrDefault(a => a.FriendlyName == deviceName);
                    if (device != null) return device;
                    device = enumerator.EnumAudioEndpoints(DataFlow.Capture, DeviceState.Active).FirstOrDefault(a => a.FriendlyName == deviceName);
                    return device;
                }
                catch (CoreAudioAPIException ex)
                {
                    Console.WriteLine($"Error getting device with Name '{deviceName}': {ex.Message}");
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
