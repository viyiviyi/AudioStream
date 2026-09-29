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
