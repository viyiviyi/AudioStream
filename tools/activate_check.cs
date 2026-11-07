// 验证 CSCore 的 MMDevice.Activate 到底有没有把 IID 交给系统：
// 拿一个根本不存在的接口 GUID 去激活，如果还能返回非零指针，说明它没把 IID 传下去。
using System;
using CSCore.CoreAudioAPI;

internal static class ActivateCheck
{
    private static void Try(string label, Guid iid)
    {
        using (var e = new MMDeviceEnumerator())
        using (var col = e.EnumAudioEndpoints(DataFlow.Render, DeviceState.Active))
        {
            foreach (MMDevice d in col)
            {
                if (d.FriendlyName.IndexOf("Realtek", StringComparison.OrdinalIgnoreCase) < 0) continue;
                try
                {
                    var p = d.Activate(iid, CSCore.Win32.CLSCTX.CLSCTX_ALL, IntPtr.Zero);
                    Console.WriteLine("{0,-22} -> 0x{1:X}", label, p.ToInt64());
                }
                catch (Exception ex)
                {
                    Console.WriteLine("{0,-22} -> 抛异常 {1}: {2}", label, ex.GetType().Name, ex.Message);
                }
                break;
            }
        }
    }

    private static void Main()
    {
        Try("IAudioClient", new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"));
        Try("IAudioClient2", new Guid("726778CD-F60A-4EDA-82DE-E47610CD78AA"));
        Try("IAudioClient3", new Guid("7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42"));
        Try("不存在的接口", new Guid("11111111-2222-3333-4444-555555555555"));
        Console.WriteLine("done");
    }
}
