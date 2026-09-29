using Newtonsoft.Json;
using Newtonsoft.Json.Converters;
using System;
using System.Collections.Generic;
using System.Linq;

namespace AudioStream.AudioServer
{
    public class PlayerInfo
    {
        public Guid ID { get; set; }
        public string SourceDeviceID { get; set; }
        public string SourceDeviceName { get; set; }
        public string IP { get; set; }
        public string PcName { get; set; }
        public string RemakeName { get; set; }
        public float Volume { get; set; } = 1;
        public int Index { get; set; }
        public bool Play { get; set; } = false;

        // 无用
        public bool Hidden { get; set; } = false;

        /// <summary>
        /// 多个播放目标之间怎么分配声音：全播还是按优先级主备。
        /// </summary>
        [JsonConverter(typeof(StringEnumConverter))]
        public PlaybackPolicy Policy { get; set; } = PlaybackPolicy.All;

        /// <summary>
        /// 这一路要播到本机的哪些设备。至少一条，没有目标就没什么可播的。
        /// </summary>
        public List<PlayTarget> Targets { get; set; } = new List<PlayTarget>();

        /// <summary>
        /// 旧版的单目标字段，只为读得懂老配置文件。
        /// 名字保持旧拼写，读进来迁移到 Targets 之后立刻清空，不会再写回磁盘。
        /// </summary>
        [JsonProperty("TargetDeiceID", NullValueHandling = NullValueHandling.Ignore)]
        public string LegacyTargetDeviceID { get; set; }

        /// <inheritdoc cref="LegacyTargetDeviceID"/>
        [JsonProperty("TargetDeiceName", NullValueHandling = NullValueHandling.Ignore)]
        public string LegacyTargetDeviceName { get; set; }

        /// <summary>第一个目标。单目标场景（以及还没支持多目标的那些调用点）用它。</summary>
        [JsonIgnore]
        public PlayTarget PrimaryTarget
        {
            get { return Targets != null && Targets.Count > 0 ? Targets[0] : null; }
        }

        /// <summary>
        /// 把老配置里的单目标补进 Targets。
        /// 读盘之后、写盘之前都要走一遍：读的时候是迁移，写的时候是兜底保证落盘的永远是完整形态。
        /// </summary>
        public void NormalizeTargets()
        {
            if (Targets == null) Targets = new List<PlayTarget>();
            if (Targets.Count == 0 && !string.IsNullOrEmpty(LegacyTargetDeviceID))
            {
                Targets.Add(new PlayTarget
                {
                    DeviceID = LegacyTargetDeviceID,
                    DeviceName = LegacyTargetDeviceName,
                    Priority = 0
                });
            }
            LegacyTargetDeviceID = null;
            LegacyTargetDeviceName = null;
        }

        public PlayerInfo Copy()
        {
            var clone = (PlayerInfo)MemberwiseClone();
            // MemberwiseClone 是浅拷贝，目标列表得自己复制一份，
            // 否则界面拿到的那份和正在跑的这份会共用同一个 List。
            clone.Targets = Targets == null
                ? new List<PlayTarget>()
                : Targets.Select(t => t.Copy()).ToList();
            return clone;
        }
    }
}
