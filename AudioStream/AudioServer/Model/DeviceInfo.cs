using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace AudioStream.AudioServer.Model
{
    /// <summary>
    /// 一台机器上的一个音频端点，供「来源设备」和「播放设备」两个下拉框使用。
    /// 两类端点都能当来源：输出设备走环回采集（录下扬声器正在放的东西），输入设备直接录麦克风。
    /// 播放目标只能是输出设备——往麦克风里推声音没有意义。
    /// </summary>
    public class DeviceInfo
    {
        public string Name { get; set; }
        public string ID { get; set; }
        public bool Default { set; get; }

        /// <summary>output = 输出设备，input = 输入设备。界面靠它把下拉框分组。</summary>
        public string Flow { get; set; }
    }
}
