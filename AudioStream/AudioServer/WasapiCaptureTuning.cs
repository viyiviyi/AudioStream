using Common;
using CSCore.SoundIn;
using System;
using System.Reflection;

namespace AudioStream
{
    /// <summary>
    /// 把 CSCore 的采集切到事件驱动。
    ///
    /// CSCore 1.2.1 在共享模式下默认用 <c>Thread.Sleep</c> 轮询取数据，而 Sleep 的精度
    /// 只有系统时钟 tick（15.6ms），于是采集交付被钉在 15.6ms 一档，每档还要塞 1~2 块，
    /// 块大小也跟着被拉成 20ms。实测同一台机器同一批设备：
    ///
    ///     轮询：间隔中位 15.6ms，块 10.67~20ms，最大间隔 24~32ms
    ///     事件：间隔中位 10.0~10.6ms，块 10.00ms，最大间隔 11~21ms
    ///
    /// 交付延迟和抖动同时减半，而且延迟参数（latency）这时才真正起作用——轮询时它被
    /// 系统 tick 盖过去了。CSCore 没有公开这个开关，只能改私有字段。
    /// </summary>
    internal static class WasapiCaptureTuning
    {
        private static readonly FieldInfo EventSyncField = typeof(WasapiCapture).GetField(
            "_eventSync", BindingFlags.NonPublic | BindingFlags.Instance);

        /// <summary>
        /// 在 <see cref="WasapiCapture.Initialize"/> 之前调用：Initialize 才会按这个开关
        /// 去建事件句柄并把它交给声卡，之后再改就无效了。
        /// </summary>
        public static void UseEventSync(WasapiCapture capture)
        {
            if (capture == null || EventSyncField == null) return;
            try
            {
                EventSyncField.SetValue(capture, true);
            }
            catch (Exception ex)
            {
                // 改不动就保持轮询。声音还能出来，只是交付晚几毫秒，不该因此让采集起不来。
                Logger.Error("切换采集到事件驱动失败，退回轮询", ex);
            }
        }
    }
}
