using CSCore.CoreAudioAPI;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace AudioStream.AudioServer
{
    internal interface IPlayerRedirector : IDisposable
    {
        float Volume { get; set; }

        /// <summary>
        /// 这一路真的建起来了吗。
        /// 连上了、握手过了、播放设备也开出来了才算，用来判断这次启动成不成功。
        /// </summary>
        bool IsActive { get; }

        /// <summary>
        /// 此刻是不是真的有声音在往外放。
        /// 被拉取侧靠它区分「配置里写着这么一条」「连上了但还没声音」和「此刻真的在放别人的声音」——
        /// 对端离线时不能算数，否则用户会莫名点不了单。
        /// </summary>
        bool AudioFlowing { get; }

        /// <summary>
        /// 这一路的声音是从哪台机器拉来的（对端握手时报的机器号）。
        /// 本机设备之间的流转不出本机，不构成跨机链路，返回 null。
        /// 被拉取侧靠它判断「点单的是不是就是这一路的来源」——只有那种情况才会把对方的声音绕回去；
        /// 只看「这块设备上有没有远程音频在放」会把不相干的第三方也一起挡掉，而那是多一个听众，不成环。
        /// </summary>
        string SourceMachineId { get; }

        /// <summary>此刻真的在出声的设备名。一路喂多块声卡时可能有好几个。</summary>
        List<string> ActiveDeviceNames { get; }

        /// <summary>
        /// 这一路此刻的音质指标（缓冲水位、欠载、时钟校正）。起不来时是 null。
        /// 用户说「有杂音」时，界面靠它把「网络抖动 / 缓冲不够 / 声卡时钟跑偏」分开。
        /// </summary>
        Model.PlaybackQuality Quality { get; }

        void SetDevice(MMDevice outputDevice);
    }
}
