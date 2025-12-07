using System;

namespace AudioStream.AudioServer
{
    public class PlayerInfo
    {
        public Guid ID { get; set; }
        public string SourceDeviceID { get; set; }
        public string SourceDeviceName { get; set; }
        public string TargetDeiceID { get; set; }
        public string TargetDeiceName { get; set; }
        public string IP { get; set; }
        public string PcName { get; set; }
        public string RemakeName { get; set; }
        public float Volume { get; set; } = 1;
        public int Index { get; set; }
        public bool Play { get; set; } = false;

        // 无用
        public bool Hidden { get; set; } = false;

        public PlayerInfo Copy()
        {
            return (PlayerInfo)this.MemberwiseClone();
        }
    }
}
