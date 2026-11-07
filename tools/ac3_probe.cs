// 直接按 vtable 调 IAudioClient3：数一数共享流能不能开得比设备默认周期小。
// IAudioClient2 只管客户端属性（RAW），改周期的是 IAudioClient3::InitializeSharedAudioStream。
// 用法：ac3_probe.exe
using System;
using System.Runtime.InteropServices;
using CSCore;
using CSCore.CoreAudioAPI;

internal static class Ac3Probe
{
    private const int AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    private const int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;

    // vtable：0 QueryInterface / 1 AddRef / 2 Release
    // 3..14 IAudioClient，15..17 IAudioClient2，18..22 IAudioClient3
    private const int SlotGetDevicePeriod = 9;
    private const int SlotGetMinPeriod = 18;
    private const int SlotGetMinBufferSize = 19;
    private const int SlotGetCurrentPeriod = 20;
    private const int SlotGetCurrentBufferSize = 21;
    private const int SlotInitializeSharedAudioStream = 22;

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int GetDevicePeriodFn(IntPtr self, out long def, out long min);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int GetUIntFn(IntPtr self, out uint value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int GetUIntPtrFn(IntPtr self, IntPtr value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int InitializeSharedAudioStreamFn(IntPtr self, uint streamFlags, uint periodInFrames, IntPtr format, IntPtr sessionGuid);

    private static T Fn<T>(IntPtr p, int slot) where T : class
    {
        IntPtr vtbl = Marshal.ReadIntPtr(p);
        IntPtr fn = Marshal.ReadIntPtr(vtbl, slot * IntPtr.Size);
        return (T)(object)Marshal.GetDelegateForFunctionPointer(fn, typeof(T));
    }

    /// <summary>
    /// 取委托、调用、再把委托钉住到调用结束。
    /// 直接把 <c>Fn&lt;T&gt;(...)(...)</c> 连起来写，临时委托可能在调用前就被回收，
    /// 表现是随机的访问冲突——同一段代码调十次，前面几次成功后面崩。
    /// </summary>
    private static int CallUInt(IntPtr p, int slot, out uint value)
    {
        // 参数用手工分配的 4 字节传，并且不经泛型取委托：两条都试过，为的是把
        // 「封送」和「泛型」这两个变量从现场里排掉。
        IntPtr vtbl = Marshal.ReadIntPtr(p);
        IntPtr addr = Marshal.ReadIntPtr(vtbl, slot * IntPtr.Size);
        var fn = (GetUIntPtrFn)Marshal.GetDelegateForFunctionPointer(addr, typeof(GetUIntPtrFn));
        var buf = Marshal.AllocHGlobal(4);
        try
        {
            Marshal.WriteInt32(buf, 0);
            var hr = fn(p, buf);
            value = (uint)Marshal.ReadInt32(buf);
            return hr;
        }
        finally
        {
            GC.KeepAlive(fn);
            Marshal.FreeHGlobal(buf);
        }
    }

    private static int CallDevicePeriod(IntPtr p, out long def, out long min)
    {
        var fn = Fn<GetDevicePeriodFn>(p, SlotGetDevicePeriod);
        try { return fn(p, out def, out min); }
        finally { GC.KeepAlive(fn); }
    }

    private static int CallInitializeShared(IntPtr p, uint flags, uint frames, IntPtr fmt)
    {
        var fn = Fn<InitializeSharedAudioStreamFn>(p, SlotInitializeSharedAudioStream);
        try { return fn(p, flags, frames, fmt, IntPtr.Zero); }
        finally { GC.KeepAlive(fn); }
    }

    private static string HResult(int hr)
    {
        if (hr == 0) return "OK";
        if (hr == unchecked((int)0x88890008)) return "0x88890008 格式不支持";
        if (hr == unchecked((int)0x8889000E)) return "0x8889000E 缓冲大小未对齐";
        if (hr == unchecked((int)0x88890019)) return "0x88890019 请求超出范围";
        if (hr == unchecked((int)0x80070057)) return "0x80070057 参数错误";
        if (hr == unchecked((int)0x88890004)) return "0x88890004 设备无效";
        return "0x" + hr.ToString("X8");
    }

    /// <summary>把 CSCore 的 WaveFormat 摆成原生 WAVEFORMATEX / WAVEFORMATEXTENSIBLE 内存。</summary>
    private static IntPtr BuildFormat(WaveFormat wf)
    {
        var ext = wf as WaveFormatExtensible;
        int size = ext != null ? 40 : 18;
        var ptr = Marshal.AllocHGlobal(size);
        Marshal.WriteInt16(ptr, 0, (short)(ext != null ? 0xFFFE : (short)wf.WaveFormatTag));
        Marshal.WriteInt16(ptr, 2, (short)wf.Channels);
        Marshal.WriteInt32(ptr, 4, wf.SampleRate);
        Marshal.WriteInt32(ptr, 8, wf.BytesPerSecond);
        Marshal.WriteInt16(ptr, 12, (short)wf.BlockAlign);
        Marshal.WriteInt16(ptr, 14, (short)wf.BitsPerSample);
        if (ext != null)
        {
            Marshal.WriteInt16(ptr, 16, 22);
            Marshal.WriteInt16(ptr, 18, (short)wf.BitsPerSample);
            Marshal.WriteInt32(ptr, 20, (int)ext.ChannelMask);
            var g = ext.SubFormat.ToByteArray();
            Marshal.Copy(g, 0, ptr + 24, 16);
        }
        else
        {
            Marshal.WriteInt16(ptr, 16, 0);
        }
        return ptr;
    }

    private static void Probe(MMDevice device, bool loopback)
    {
        IntPtr p = IntPtr.Zero;
        IntPtr fmt = IntPtr.Zero;
        try
        {
            using (var ac = AudioClient.FromMMDevice(device))
            {
                var iid = new Guid("7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42");
                p = device.Activate(iid, CSCore.Win32.CLSCTX.CLSCTX_ALL, IntPtr.Zero);
                if (p == IntPtr.Zero)
                {
                    Console.WriteLine("  {0} -> IMMDevice::Activate 拿不到 IAudioClient3", device.FriendlyName);
                    return;
                }

                Console.WriteLine("  {0} [{1}]", device.FriendlyName, loopback ? "loopback" : "capture");

                // 把 vtable 前 24 项打出来：如果 IAudioClient3 的方法不在这个范围，
                // 或者某一项明显不属于同一个模块，就能一眼看出拿到的对象其实不是 3 版本。
                var vtbl = Marshal.ReadIntPtr(p);
                Console.WriteLine("      vtable 0x{0:X}", vtbl.ToInt64());
                var sb = new System.Text.StringBuilder();
                for (var i = 0; i < 24; i++)
                {
                    var f = IntPtr.Zero;
                    try { f = Marshal.ReadIntPtr(vtbl, i * IntPtr.Size); } catch { }
                    sb.Append(i).Append(":0x").Append(f.ToInt64().ToString("X")).Append(' ');
                    if (i % 4 == 3) sb.AppendLine();
                }
                Console.WriteLine("      " + sb.ToString().TrimEnd());

                long defPeriod = 0, minPeriod = 0;
                CallDevicePeriod(p, out defPeriod, out minPeriod);
                Console.WriteLine("      GetDevicePeriod : 默认 {0:0.###}ms / 最小 {1:0.###}ms",
                    defPeriod / 10000.0, minPeriod / 10000.0);

                uint minFrames = 0, minBuf = 0, curPeriod = 0, curBuf = 0;
                var hrMinF = CallUInt(p, SlotGetMinPeriod, out minFrames);
                var hrMinB = CallUInt(p, SlotGetMinBufferSize, out minBuf);
                CallUInt(p, SlotGetCurrentPeriod, out curPeriod);
                CallUInt(p, SlotGetCurrentBufferSize, out curBuf);

                var wf = ac.GetMixFormat();
                var rate = wf.SampleRate > 0 ? wf.SampleRate : 48000;
                Console.WriteLine("      GetMinPeriod    : {0} 帧 = {1:0.###}ms ({2})", minFrames,
                    minFrames * 1000.0 / rate, HResult(hrMinF));
                Console.WriteLine("      GetMinBufferSize: {0} 帧 = {1:0.###}ms ({2})", minBuf,
                    minBuf * 1000.0 / rate, HResult(hrMinB));
                Console.WriteLine("      当前周期/缓冲    : {0} 帧 = {1:0.###}ms / {2} 帧 = {3:0.###}ms",
                    curPeriod, curPeriod * 1000.0 / rate, curBuf, curBuf * 1000.0 / rate);
                Console.WriteLine("      mix format      : {0}Hz {1}ch {2}bit tag={3} blockAlign={4}",
                    wf.SampleRate, wf.Channels, wf.BitsPerSample, wf.WaveFormatTag, wf.BlockAlign);

                fmt = BuildFormat(wf);
                var flags = (uint)AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
                if (loopback) flags |= (uint)AUDCLNT_STREAMFLAGS_LOOPBACK;
                var want = Math.Max(1u, minFrames);
                var hr = CallInitializeShared(p, flags, want, fmt);
                Console.WriteLine("      InitializeSharedAudioStream({0} 帧 = {1:0.###}ms) -> {2}",
                    want, want * 1000.0 / rate, HResult(hr));
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine("  {0} -> 异常 {1}: {2}", device.FriendlyName, ex.GetType().Name, ex.Message);
        }
        finally
        {
            if (p != IntPtr.Zero) Marshal.Release(p);
            if (fmt != IntPtr.Zero) Marshal.FreeHGlobal(fmt);
        }
    }

    private static void Main()
    {
        using (var e = new MMDeviceEnumerator())
        {
            Console.WriteLine("=== 渲染设备（loopback）===");
            using (var col = e.EnumAudioEndpoints(DataFlow.Render, DeviceState.Active))
                foreach (MMDevice d in col) { if (Environment.GetEnvironmentVariable("AC3_ALL") == null && d.FriendlyName.IndexOf("Realtek", StringComparison.OrdinalIgnoreCase) < 0) continue; Probe(d, true); }

            Console.WriteLine("=== 采集设备 ===");
            using (var col = e.EnumAudioEndpoints(DataFlow.Capture, DeviceState.Active))
                foreach (MMDevice d in col) { if (d.FriendlyName.IndexOf("Realtek", StringComparison.OrdinalIgnoreCase) < 0) continue; Probe(d, false); }
        }
        Console.WriteLine("done");
    }
}
