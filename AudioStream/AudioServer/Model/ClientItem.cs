using System;

namespace AudioStream.AudioServer.Model
{
    /// <summary>
    /// 一条正在拉取本机音频的连接，界面上的「客户端列表」就是它的集合。
    /// </summary>
    public class ClientItem
    {
        /// <summary>被拉走的音频设备名。</summary>
        public string DeviceName { get; set; }

        /// <summary>拉取方的来源地址。</summary>
        public string ClientIp { get; set; }

        /// <summary>拉取方的本机身份，用来判断连接是不是绕回了自己。</summary>
        public string MachineId { get; set; }

        /// <summary>拉取方的计算机名，只用于显示。</summary>
        public string PcName { get; set; }

        /// <summary>拉取方指定的音频设备号，握手时才知道，可能为空。</summary>
        public string SourceDeviceID { get; set; }

        /// <summary>音频是否已经在往对端送。</summary>
        public bool Streaming { get; set; }

        /// <summary>这条连接此刻卡在「等本机主人点头」上。界面据此提示有人在等确认。</summary>
        public bool ApprovalPending { get; set; }

        /// <summary>这条连接眼下处在什么状态，给人看的短句：等待本机确认 / 已被本机拒绝 / 确认超时。</summary>
        public string Note { get; set; }

        /// <summary>连接建立时间。</summary>
        public DateTime ConnectedAt { get; set; }

        /// <summary>
        /// 已经从本机发出去的音频字节数。
        /// 与对端的「收到多少」对不上时，就能判断问题出在这条链路中间还是对端。
        /// </summary>
        public long SentAudioBytes { get; set; }

        /// <summary>
        /// 发送队列积压时丢掉的字节数。
        /// 这个数一直涨说明本机发得比链路能送走的快，对端听到的就是断断续续；
        /// 它不涨而对方还说卡，那就不是这条连接的问题。
        /// </summary>
        public long DroppedBytes { get; set; }

        /// <summary>丢弃的那些字节折算成多少毫秒音频，界面直接显示这个。</summary>
        public int DroppedMs { get; set; }
    }
}
