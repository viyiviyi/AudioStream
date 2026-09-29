using System.Collections.Generic;

namespace AudioStream.AudioServer.Model
{
    /// <summary>
    /// 新建或修改一条拉取时，配置界面提交过来的内容。
    /// 「来源」可以是对端那台机器上的某个设备（IP 填对方地址），也可以是本机的另一个设备（IP 留空）；
    /// 「目标」是本机的一组播放设备——一条来源喂多个设备就是在 Targets 里体现的。
    /// </summary>
    public class PlayerRequest
    {
        /// <summary>对端地址。留空表示在本机自己的设备之间流转。</summary>
        public string IP { get; set; }

        /// <summary>来源设备号。对端那台机器上的扬声器或麦克风。</summary>
        public string SourceDeviceID { get; set; }

        /// <summary>来源设备名，只用于显示与设备号失效时的兜底查找。</summary>
        public string SourceDeviceName { get; set; }

        /// <summary>All（全播）或 Failover（按顺序主备）。留空按 All。</summary>
        public string Policy { get; set; }

        /// <summary>本机的播放设备列表。传进来的顺序就是主备顺序，第一个最优先。</summary>
        public List<PlayTarget> Targets { get; set; }
    }
}
