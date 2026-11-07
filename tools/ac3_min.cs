// 最小验证：只用 RCW 调 IAudioClient3::GetMinPeriod，排除 vtable 手调的一切干扰。
using System;
using System.Runtime.InteropServices;
using CSCore.CoreAudioAPI;

[ComImport, Guid("7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioClient3
{
    [PreserveSig] int Initialize(int shareMode, int streamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr format, IntPtr sessionGuid);
    [PreserveSig] int GetBufferSize(out int frames);
    [PreserveSig] int GetStreamLatency(out long latency);
    [PreserveSig] int GetCurrentPadding(out int padding);
    [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    [PreserveSig] int GetMixFormat(out IntPtr format);
    [PreserveSig] int GetDevicePeriod(out long def, out long min);
    [PreserveSig] int Start();
    [PreserveSig] int Stop();
    [PreserveSig] int Reset();
    [PreserveSig] int SetEventHandle(IntPtr evt);
    [PreserveSig] int GetService(ref Guid riid, out IntPtr service);
    [PreserveSig] int IsOffloadCapable(int category, out int capable);
    [PreserveSig] int SetClientProperties(IntPtr props);
    [PreserveSig] int GetBufferSizeLimits(IntPtr format, int eventDriven, out long min, out long max);
    [PreserveSig] int GetMinPeriod(out uint frames);
    [PreserveSig] int GetMinBufferSize(out uint frames);
    [PreserveSig] int GetCurrentPeriod(out uint frames);
    [PreserveSig] int GetCurrentBufferSize(out uint frames);
    [PreserveSig] int InitializeSharedAudioStream(uint flags, uint periodInFrames, IntPtr format, IntPtr sessionGuid);
}

internal static class Ac3Min
{
    private static void Main()
    {
        Console.WriteLine("Windows " + Environment.OSVersion.Version);
        using (var e = new MMDeviceEnumerator())
        using (var col = e.EnumAudioEndpoints(DataFlow.Render, DeviceState.Active))
        {
            foreach (MMDevice d in col)
            {
                if (d.FriendlyName.IndexOf("Realtek", StringComparison.OrdinalIgnoreCase) < 0) continue;
                try
                {
                    var iid = new Guid("7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42");
                    var p = d.Activate(iid, CSCore.Win32.CLSCTX.CLSCTX_ALL, IntPtr.Zero);
                    Console.WriteLine("Activate 返回 0x{0:X}", p.ToInt64());
                    if (p == IntPtr.Zero) continue;
                    var ac3 = (IAudioClient3)Marshal.GetObjectForIUnknown(p);
                    Marshal.Release(p);

                    uint min = 0;
                    var hr = ac3.GetMinPeriod(out min);
                    Console.WriteLine("GetMinPeriod hr=0x{0:X8} 值={1}", hr, min);
                    uint minBuf = 0;
                    hr = ac3.GetMinBufferSize(out minBuf);
                    Console.WriteLine("GetMinBufferSize hr=0x{0:X8} 值={1}", hr, minBuf);
                    uint cur = 0;
                    hr = ac3.GetCurrentPeriod(out cur);
                    Console.WriteLine("GetCurrentPeriod hr=0x{0:X8} 值={1}", hr, cur);
                }
                catch (Exception ex)
                {
                    Console.WriteLine("异常 " + ex.GetType().Name + ": " + ex.Message);
                }
                break;
            }
        }
        Console.WriteLine("done");
    }
}
