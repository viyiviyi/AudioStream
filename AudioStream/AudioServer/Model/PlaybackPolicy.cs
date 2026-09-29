namespace AudioStream.AudioServer
{
    /// <summary>
    /// 一条拉取记录有多个播放目标时，声音该怎么分配。
    /// </summary>
    public enum PlaybackPolicy
    {
        /// <summary>所有目标设备同时出声。适合「一台电脑的声音同时在客厅和书房响」。</summary>
        All,

        /// <summary>
        /// 按优先级只让第一个能用的目标出声，它不可用了自动换下一个。
        /// 适合「平时走音箱，音箱没插就走耳机」这类主备关系；同时只播一路，不会出现两份声音。
        /// </summary>
        Failover
    }
}
