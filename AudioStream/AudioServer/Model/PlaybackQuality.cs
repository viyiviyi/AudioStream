using System;

namespace AudioStream.AudioServer.Model
{
    /// <summary>
    /// 一路音频此刻的音质指标。
    ///
    /// 为什么要有它：用户只会说「有杂音」，但杂音的成因是能分开的——
    /// 欠载（缓冲不够，声卡要数据时手里没有）会一直涨；时钟校正（两端声卡晶振不同步，
    /// 靠丢/插单帧把水位拉回来）会慢慢涨；溢出丢帧（对端发得比本机放得快）又是一回事。
    /// 把这三个数字摆出来，下一次「有杂音」就不用猜是网络、是缓冲、还是设备。
    /// </summary>
    public class PlaybackQuality
    {
        /// <summary>此刻抖动缓冲里的水位（毫秒）。它接近 0 就离爆音不远了。</summary>
        public int LevelMs { get; set; }

        /// <summary>水位想稳在多少毫秒。</summary>
        public int TargetLevelMs { get; set; }

        /// <summary>缓冲容量（毫秒）。水位顶到它意味着已经追不上了。</summary>
        public int CapacityMs { get; set; }

        /// <summary>缓冲空、只能补静音的次数。一直涨就是周期性的咔哒声。</summary>
        public long Underruns { get; set; }

        /// <summary>补出去的静音合计多少毫秒。</summary>
        public long SilenceMs { get; set; }

        /// <summary>为把水位拉高而重复的数据合计多少毫秒（本机放得比对方采得快）。</summary>
        public long DriftRepeatMs { get; set; }

        /// <summary>为把水位压低而丢掉的数据合计多少毫秒（本机放得比对方采得慢）。</summary>
        public long DriftDropMs { get; set; }

        /// <summary>
        /// 时钟漂移一共修正了多少次。
        /// 毫秒数看不出频率：同样是 300ms，摊成每秒二十次是持续的轻微噪声，摊成每秒三次基本听不出来。
        /// 两端时钟同步时这个数应当几乎不涨。
        /// </summary>
        public long DriftCorrections { get; set; }

        /// <summary>读者落后超过一圈、被整段丢掉的毫秒数。这个数大说明卡过很久。</summary>
        public long DroppedMs { get; set; }

        /// <summary>真正喂给声卡的字节数。</summary>
        public long ServedBytes { get; set; }

        /// <summary>
        /// 当前重采样比率相对 1.0 的偏离（ppm）。
        /// 用重采样吸收时钟差时它会稳定在一个非零值上——那是它在持续替两端时钟跑差，不是异常。
        /// </summary>
        public int ResamplePpm { get; set; }

        /// <summary>一句话总结，界面直接显示，不必自己拼。</summary>
        public string Summary { get; set; }

        /// <summary>
        /// 从播放组的计数换算成给人看的一份。没有计数就返回 null。
        /// bytesPerMs 是格式算出来的每毫秒字节数，用来把字节数换成毫秒——用户看毫秒才看得出严重程度。
        /// </summary>
        internal static PlaybackQuality From(PlaybackQualityStats stats, int bytesPerMs, int targetMs, int capacityMs, long droppedBytes)
        {
            if (stats == null) return null;
            if (bytesPerMs <= 0) bytesPerMs = 1;
            var quality = new PlaybackQuality
            {
                LevelMs = stats.LevelMs,
                TargetLevelMs = targetMs,
                CapacityMs = capacityMs,
                Underruns = stats.Underruns,
                SilenceMs = stats.SilenceBytes / bytesPerMs,
                DriftRepeatMs = stats.DriftRepeatBytes / bytesPerMs,
                DriftDropMs = stats.DriftDropBytes / bytesPerMs,
                DriftCorrections = stats.DriftCorrections,
                ResamplePpm = stats.ResamplePpm,
                DroppedMs = droppedBytes / bytesPerMs,
                ServedBytes = stats.BytesServed
            };
            quality.Summary = BuildSummary(quality);
            return quality;
        }

        /// <summary>拼一句给人看的话。水位是常态信息，出过问题才把问题摆出来。</summary>
        private static string BuildSummary(PlaybackQuality quality)
        {
            var text = "缓冲 " + quality.LevelMs + "/" + quality.TargetLevelMs + "ms";
            text += quality.Underruns > 0
                ? " · 欠载 " + quality.Underruns + " 次/" + quality.SilenceMs + "ms"
                : " · 无欠载";
            var drift = quality.DriftRepeatMs + quality.DriftDropMs;
            if (quality.ResamplePpm != 0)
            {
                // 重采样模式下没有离散的丢/补，「比率偏离多少」才是要看的东西
                text += " · 时钟跟随 " + quality.ResamplePpm + "ppm";
            }
            else if (drift > 0)
            {
                text += " · 时钟校正 " + quality.DriftCorrections + " 次/" + drift + "ms";
            }
            if (quality.DroppedMs > 0) text += " · 丢旧数据 " + quality.DroppedMs + "ms";
            return text;
        }
    }
}
