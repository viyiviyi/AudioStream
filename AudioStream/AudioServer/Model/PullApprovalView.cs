using System;

namespace AudioStream.AudioServer
{
    /// <summary>
    /// 一条「等本机主人点头」的拉取申请。
    /// 档位是「需确认」时，对端点单的一瞬间会生成它，界面轮询 /api/grants 就能看到。
    /// </summary>
    public class PullApprovalView
    {
        /// <summary>申请号，批准/拒绝时按它定位。</summary>
        public string Id { get; set; }

        public string MachineId { get; set; }

        /// <summary>对端的计算机名，显示用。</summary>
        public string PcName { get; set; }

        /// <summary>对端来源地址，显示用。</summary>
        public string ClientIp { get; set; }

        public string DeviceId { get; set; }

        /// <summary>对端想拉的本机设备名。</summary>
        public string DeviceName { get; set; }

        public DateTime CreatedAt { get; set; }

        /// <summary>还能等多少秒，过时不候。</summary>
        public int RemainingSeconds { get; set; }
    }
}
