using AudioStream.AudioServer.Model;
using Common;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;

namespace AudioStream.AudioServer
{
    /// <summary>
    /// 档位为「需确认」时的审批队列。
    ///
    /// 对端点单那条连接会在这里挂着等，直到本机主人在配置页上点了批准或拒绝，
    /// 或者等到超时。等待发生在对端连接自己的线程上，没有连接就没人等，
    /// 所以这里不引入任何后台线程——队列只跟着连接的生命周期存在。
    ///
    /// 同一台机器的同一个设备可能同时来好几路（对端重试、多网卡等）：
    /// 批准一次就把这几路一起放过去，免得让人在界面上按十下同一个按钮。
    /// </summary>
    internal static class PullApprovalCenter
    {
        /// <summary>等多久没人理就算没过。太短来不及点，太长会把对端连接一直吊着。</summary>
        public const int DefaultTimeoutSeconds = 30;

        private static readonly object SyncRoot = new object();
        private static readonly List<PullApprovalRequest> _pending = new List<PullApprovalRequest>();

        /// <summary>挂上一条申请。调用方拿到它之后要调 <see cref="Wait"/>，并在结束时 <see cref="Abandon"/>。</summary>
        public static PullApprovalRequest Create(string machineId, string pcName, string clientIp, string deviceId, string deviceName)
        {
            var request = new PullApprovalRequest
            {
                Id = Guid.NewGuid().ToString("N"),
                MachineId = machineId,
                PcName = pcName,
                ClientIp = clientIp,
                DeviceId = deviceId,
                DeviceName = deviceName,
                CreatedAt = DateTime.Now,
                TimeoutSeconds = DefaultTimeoutSeconds
            };
            lock (SyncRoot)
            {
                _pending.Add(request);
            }
            Logger.Info($"拉取需要确认：{pcName ?? machineId ?? clientIp}（{clientIp}）想拉「{deviceName}」，等本机确认，最多 {DefaultTimeoutSeconds} 秒");
            return request;
        }

        /// <summary>当前还没被处理的申请，界面直接显示。</summary>
        public static List<PullApprovalView> Pending()
        {
            lock (SyncRoot)
            {
                var now = DateTime.Now;
                return _pending.Where(r => !r.Decided).Select(r => new PullApprovalView
                {
                    Id = r.Id,
                    MachineId = r.MachineId,
                    PcName = r.PcName,
                    ClientIp = r.ClientIp,
                    DeviceId = r.DeviceId,
                    DeviceName = r.DeviceName,
                    CreatedAt = r.CreatedAt,
                    RemainingSeconds = Math.Max(0, r.TimeoutSeconds - (int)(now - r.CreatedAt).TotalSeconds)
                }).ToList();
            }
        }

        /// <summary>
        /// 本机主人的裁决。<paramref name="remember"/> 为真时顺手写成一条永久规则（批准记成无需确认，拒绝记成永不允许）。
        /// 没有机器身份的申请不写规则：身份都没有，写出来的「任意机器」规则会管到所有人头上。
        /// </summary>
        public static bool Decide(string id, bool approved, bool remember)
        {
            if (string.IsNullOrWhiteSpace(id)) return false;
            PullApprovalRequest target;
            List<PullApprovalRequest> affected;
            lock (SyncRoot)
            {
                target = _pending.FirstOrDefault(r => string.Equals(r.Id, id.Trim(), StringComparison.OrdinalIgnoreCase));
                if (target == null) return false;
                affected = _pending.Where(r => !r.Decided
                    && string.Equals(r.MachineId ?? "", target.MachineId ?? "", StringComparison.OrdinalIgnoreCase)
                    && string.Equals(r.DeviceId ?? "", target.DeviceId ?? "", StringComparison.OrdinalIgnoreCase)).ToList();
                foreach (var item in affected)
                {
                    item.Decided = true;
                    item.Approved = approved;
                }
            }
            // 先写规则再叫醒等待方：等待方醒过来马上就要读授权结果，规则不能落在它后面
            if (remember && !string.IsNullOrWhiteSpace(target.MachineId))
            {
                PullAuthorization.Upsert(target.MachineId, target.PcName, target.DeviceId, target.DeviceName,
                    approved ? PullAccess.Allow : PullAccess.Deny);
            }
            foreach (var item in affected)
            {
                try { item.Signal.Set(); }
                catch (Exception e) { Logger.Error("唤醒等待中的拉取申请失败：" + e.Message, e); }
            }
            Logger.Info($"拉取申请 {target.Id} 已被{(approved ? "批准" : "拒绝")}{(remember ? "，并记住这条选择" : "")}：" +
                        $"{target.PcName ?? target.MachineId ?? target.ClientIp} 拉「{target.DeviceName}」");
            return true;
        }

        /// <summary>
        /// 挂着等裁决。返回是否被批准；超时返回 false。
        /// 调用方无论结果都要接 <see cref="Abandon"/>，否则这张单子会一直留在界面上。
        /// </summary>
        public static bool Wait(PullApprovalRequest request, int timeoutSeconds)
        {
            if (request == null) return false;
            var milliseconds = Math.Max(1, timeoutSeconds) * 1000;
            try
            {
                request.Signal.Wait(milliseconds);
            }
            catch (Exception e)
            {
                Logger.Error("等待拉取确认时出错，按未批准处理：" + e.Message, e);
                return false;
            }
            return request.Decided && request.Approved;
        }

        /// <summary>把申请从队列里摘掉。连接断了、超时了、批完了都走这里。</summary>
        public static void Abandon(PullApprovalRequest request)
        {
            if (request == null) return;
            lock (SyncRoot)
            {
                _pending.Remove(request);
            }
            try { request.Signal.Dispose(); }
            catch (Exception) { /* 已经释放过就算了 */ }
        }

        /// <summary>供测试与排查看的当前未决条数。</summary>
        public static int PendingCount
        {
            get { lock (SyncRoot) { return _pending.Count(r => !r.Decided); } }
        }
    }

    /// <summary>一条待审批的拉取申请。只在服务器内部流转，接口层看到的是它的视图模型。</summary>
    internal class PullApprovalRequest
    {
        public string Id { get; set; }
        public string MachineId { get; set; }
        public string PcName { get; set; }
        public string ClientIp { get; set; }
        public string DeviceId { get; set; }
        public string DeviceName { get; set; }
        public DateTime CreatedAt { get; set; }
        public int TimeoutSeconds { get; set; }

        /// <summary>是否已经被裁决过（无论批准还是拒绝）。</summary>
        public volatile bool Decided;

        /// <summary>裁决结果，只在 <see cref="Decided"/> 为真时有意义。</summary>
        public volatile bool Approved;

        /// <summary>等待方与裁决方之间的信号。</summary>
        public readonly ManualResetEventSlim Signal = new ManualResetEventSlim(false);
    }
}
