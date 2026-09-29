namespace AudioStream.AudioServer.Model
{
    using System.Collections.Generic;

    /// <summary>
    /// 一条播放记录此刻的真实状态，给界面和排查用。
    /// 配置里写着一条不等于它真的在响：对端可能没开、可能拒绝了、声卡可能打不开，
    /// 这些都要能在界面上一眼看出来，光看配置是看不出来的。
    /// </summary>
    public class PlayerStatus
    {
        public string ID { get; set; }

        /// <summary>对端地址，留空表示本机设备之间的流转。</summary>
        public string IP { get; set; }

        /// <summary>来源设备（对端那台机器上的）。</summary>
        public string SourceDeviceID { get; set; }

        /// <summary>来源设备名，只用于显示。</summary>
        public string SourceDeviceName { get; set; }

        /// <summary>播放设备（本机上的）。多目标时这是第一个，全部目标见 <see cref="Targets"/>。</summary>
        public string TargetDeiceID { get; set; }

        /// <summary>播放设备名，只用于显示。</summary>
        public string TargetDeviceName { get; set; }

        /// <summary>这一路配置里要播到哪些设备。</summary>
        public List<PlayTarget> Targets { get; set; }

        /// <summary>All（全播）或 Failover（按优先级主备）。</summary>
        public string Policy { get; set; }

        /// <summary>是不是从别的机器拉。</summary>
        public bool Remote { get; set; }

        /// <summary>这一路是不是成功起来了（连上了、声卡也开出来了）。</summary>
        public bool Playing { get; set; }

        /// <summary>
        /// 此刻是不是真的有声音在往外放。
        /// 和 <see cref="Playing"/> 的区别在于「起来了但还没声音」：对端静音、刚连上还没收到数据时，
        /// 这里是 false。被拉取侧的回路判定看的就是这个。
        /// </summary>
        public bool AudioFlowing { get; set; }

        /// <summary>此刻真的在出声的设备名。全播时可能有好几个。</summary>
        public List<string> ActiveDeviceNames { get; set; }

        /// <summary>对端的计算机名或身份，握手时才有。</summary>
        public string Peer { get; set; }

        /// <summary>
        /// 此刻处于哪个阶段：playing 在响 / connecting 正在连 / error 起不来 /
        /// idle 还没开始 / stopped 已收工。
        /// 断线自动重连是常态，界面得让用户看出「它在自己重试」而不是「它坏了」。
        /// </summary>
        public string State { get; set; }

        /// <summary>连续失败次数。连上就清零。</summary>
        public int Attempts { get; set; }

        /// <summary>还有几秒开始下一次重连，0 表示不在等待。</summary>
        public int NextRetryInSeconds { get; set; }

        /// <summary>没播起来的原因，能播就是空。</summary>
        public string Error { get; set; }

        /// <summary>
        /// 这一路此刻的音质指标：缓冲水位、欠载次数、时钟校正量。
        /// 起不来或还没开始播放时是 null，界面按「还没数据」处理。
        /// </summary>
        public PlaybackQuality Quality { get; set; }
    }
}
