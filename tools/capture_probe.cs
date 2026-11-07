// 逐个设备量「相邻两次 DataAvailable 的间隔」以及每块承载多少毫秒音频。
// 用法：capture_probe.exe [每个设备采几秒，默认 2]
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Media;
using System.Threading;
using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundIn;

internal static class CaptureProbe
{
    private static readonly List<double> Gaps = new List<double>();
    private static readonly List<int> Sizes = new List<int>();
    private static Stopwatch clock;
    private static double lastMs = -1;

    private static void Reset()
    {
        lock (Gaps) { Gaps.Clear(); Sizes.Clear(); }
        lastMs = -1;
        clock = Stopwatch.StartNew();
    }

    private static void OnData(object s, DataAvailableEventArgs e)
    {
        double now = clock.Elapsed.TotalMilliseconds;
        lock (Gaps)
        {
            if (lastMs >= 0) Gaps.Add(now - lastMs);
            lastMs = now;
            Sizes.Add(e.ByteCount);
        }
    }

    private static double Median(List<double> v)
    {
        if (v.Count == 0) return 0;
        var c = new List<double>(v); c.Sort();
        int m = c.Count / 2;
        return c.Count % 2 == 0 ? (c[m - 1] + c[m]) / 2 : c[m];
    }

    private static void Measure(string name, bool loopback, MMDevice device, int seconds, int latency)
    {
        WasapiCapture cap = null;
        try
        {
            cap = loopback
                ? (WasapiCapture)new WasapiLoopbackCapture(latency: latency)
                : new WasapiCapture(false, AudioClientShareMode.Shared, latency: latency);
            cap.Device = device;
            cap.DataAvailable += OnData;
            Reset();
            // 看看 CSCore 内部到底走的是事件驱动还是 Sleep 轮询。
            // 共享模式本该是事件驱动；如果这里是 false，那 15.6ms 的间隔就是 Sleep 的系统 tick 造成的。
            var t = cap.GetType();
            while (t != null)
            {
                var f = t.GetField("_eventSync", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance);
                var lat = t.GetField("_latency", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance);
                if (f != null)
                {
                    // 允许外部强切事件驱动，验证间隔是不是就落到引擎周期上
                    if (Environment.GetEnvironmentVariable("PROBE_EVENTSYNC") == "1")
                        f.SetValue(cap, true);
                    Console.WriteLine("  {0,-45} _eventSync={1} _latency={2}",
                        name, f.GetValue(cap), lat != null ? lat.GetValue(cap) : null);
                    break;
                }
                t = t.BaseType;
            }

            cap.Initialize();

            cap.Start();
            Thread.Sleep(seconds * 1000);
            cap.Stop();

            double[] gaps; int[] sizes;
            lock (Gaps) { gaps = Gaps.ToArray(); sizes = Sizes.ToArray(); }

            if (gaps.Length == 0)
            {
                Console.WriteLine("  {0,-45} 无数据（{1}）", name, loopback ? "loopback" : "capture");
                return;
            }
            double min = double.MaxValue, max = 0, sum = 0;
            foreach (var g in gaps) { if (g < min) min = g; if (g > max) max = g; sum += g; }
            double bps = cap.WaveFormat.BytesPerSecond;
            var ms = new List<double>();
            foreach (var n in sizes) ms.Add(bps > 0 ? n / bps * 1000.0 : 0);
            var msSorted = new List<double>(ms); msSorted.Sort();
            long totalBytes = 0;
            foreach (var n in sizes) totalBytes += n;
            double audioMs = bps > 0 ? totalBytes / bps * 1000.0 : 0;
            double expectedMs = seconds * 1000.0;
            Console.WriteLine("  {0,-45} {1,-9} 块数 {2,4}  间隔 中位 {3,6:0.0} 最大 {4,6:0.0}ms  每块 {5,6:0.00}ms  采到 {6,7:0}ms / {7}ms = {8:0.0}%",
                name, loopback ? "loopback" : "capture", gaps.Length + 1,
                Median(new List<double>(gaps)), max, Median(ms), audioMs, (int)expectedMs, audioMs / expectedMs * 100);
        }
        catch (Exception ex)
        {
            Console.WriteLine("  {0,-45} 失败：{1}", name, ex.Message);
        }
        finally
        {
            if (cap != null) { try { cap.DataAvailable -= OnData; cap.Stop(); cap.Dispose(); } catch { } }
        }
    }

    private static string MakeWav(string path)
    {
        const int rate = 44100, secs = 60, ch = 1, bits = 16;
        int n = rate * secs;
        using (var fs = new FileStream(path, FileMode.Create))
        using (var bw = new BinaryWriter(fs))
        {
            bw.Write(new[] { (byte)'R', (byte)'I', (byte)'F', (byte)'F' });
            bw.Write(36 + n * 2);
            bw.Write(new[] { (byte)'W', (byte)'A', (byte)'V', (byte)'E' });
            bw.Write(new[] { (byte)'f', (byte)'m', (byte)'t', (byte)' ' });
            bw.Write(16); bw.Write((short)1); bw.Write((short)ch); bw.Write(rate);
            bw.Write(rate * ch * bits / 8); bw.Write((short)(ch * bits / 8)); bw.Write((short)bits);
            bw.Write(new[] { (byte)'d', (byte)'a', (byte)'t', (byte)'a' });
            bw.Write(n * 2);
            for (int i = 0; i < n; i++)
                bw.Write((short)(800 * Math.Sin(2 * Math.PI * 440 * i / rate)));
        }
        return path;
    }

    private static void Main(string[] args)
    {
        int seconds = args.Length > 0 ? int.Parse(args[0]) : 2;
        int latency = args.Length > 1 ? int.Parse(args[1]) : 10;
        string wav = MakeWav(Path.Combine(Path.GetTempPath(), "as_capture_probe.wav"));
        var player = new SoundPlayer(wav);
        player.PlayLooping();

        using (var e = new MMDeviceEnumerator())
        {
            Console.WriteLine("=== 渲染设备（loopback 采集，latency=" + latency + "ms）===");
            using (var col = e.EnumAudioEndpoints(DataFlow.Render, DeviceState.Active))
                foreach (MMDevice d in col) Measure(d.FriendlyName, true, d, seconds, latency);

            Console.WriteLine("=== 采集设备（麦克风，latency=" + latency + "ms）===");
            using (var col = e.EnumAudioEndpoints(DataFlow.Capture, DeviceState.Active))
                foreach (MMDevice d in col) Measure(d.FriendlyName, false, d, seconds, latency);
        }
        player.Stop();
        player.Dispose();
        Console.WriteLine("done");
    }
}
