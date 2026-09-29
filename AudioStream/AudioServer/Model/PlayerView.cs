using System.Collections.Generic;

namespace AudioStream.AudioServer.Model
{
    /// <summary>
    /// 配置界面看到的一路拉取。
    /// 不把 PlayerInfo 直接丢给接口：EmbedIO 那边的 JSON 序列化器不认 JsonIgnore、
    /// StringEnumConverter、NullValueHandling 这些特性，内部字段会连循环引用一起漏到页面上。
    /// 这里显式挑字段，顺便把枚举定成字符串，省得界面去猜数字。
    /// </summary>
    public class PlayerView
    {
        public string ID { get; set; }

        /// <summary>对端地址，留空表示本机设备之间流转。</summary>
        public string IP { get; set; }

        /// <summary>对端计算机名，用户自己写的备注名优先显示。</summary>
        public string PcName { get; set; }

        public string RemakeName { get; set; }

        public string SourceDeviceID { get; set; }
        public string SourceDeviceName { get; set; }

        public float Volume { get; set; }
        public int Index { get; set; }
        public bool Play { get; set; }

        /// <summary>All（全播）或 Failover（按优先级主备）。</summary>
        public string Policy { get; set; }

        /// <summary>这一路要播到本机的哪些设备，按配置顺序给出。</summary>
        public List<PlayTarget> Targets { get; set; }

        public static PlayerView From(PlayerInfo info)
        {
            var view = new PlayerView
            {
                ID = info.ID.ToString(),
                IP = info.IP,
                PcName = info.PcName,
                RemakeName = info.RemakeName,
                SourceDeviceID = info.SourceDeviceID,
                SourceDeviceName = info.SourceDeviceName,
                Volume = info.Volume,
                Index = info.Index,
                Play = info.Play,
                Policy = info.Policy.ToString(),
                Targets = new List<PlayTarget>()
            };
            if (info.Targets != null)
            {
                foreach (var target in info.Targets)
                {
                    view.Targets.Add(target.Copy());
                }
            }
            return view;
        }
    }
}
