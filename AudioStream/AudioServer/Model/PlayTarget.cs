namespace AudioStream.AudioServer
{
    /// <summary>
    /// 一条拉取记录要喂到的一个本机播放设备。
    /// 一条来源可以带多个目标：全播时它们是并行的多份播放，主备时按 Priority 轮流顶上。
    /// </summary>
    public class PlayTarget
    {
        /// <summary>本机播放设备号。写 "default" 表示跟随当时的系统默认输出设备。</summary>
        public string DeviceID { get; set; }

        /// <summary>设备名。设备号在重装驱动或换机器后会变，名字是它找不到时的兜底。</summary>
        public string DeviceName { get; set; }

        /// <summary>
        /// 主备（Failover）策略下的启用顺序，小的先用。
        /// 全播（All）策略下所有目标都出声，不看这个值。
        /// </summary>
        public int Priority { get; set; }

        public PlayTarget Copy()
        {
            return (PlayTarget)MemberwiseClone();
        }
    }
}
