using AudioStream.AudioServer;
using AudioStream.AudioServer.Model;
using Common;
using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundIn;
using System;
using System.Collections.Generic;

namespace AudioStream
{
    /// <summary>
    /// 本机设备之间的流转：把一台设备（扬声器环回或麦克风）的声音放到本机的若干台设备上。
    ///
    /// 采集只做一份，分发在本地的广播缓冲里做——用户把同一声源挂到三个声卡上时，
    /// 声卡被打开一次而不是三次，三路各自从同一份数据里取，不会出现三份互不同步的采集。
    /// </summary>
    internal class LocalAudioRedirector : IPlayerRedirector
    {
        /// <summary>
        /// 缓冲长度。采集回调一次可能给好几十毫秒的数据，缓冲必须比它大，
        /// 否则一次写入就绕圈盖掉还没放出去的部分，听起来就是断断续续。
        /// 与网络那一侧保持同一套口径（容量 120ms），两条链路的听感才一致。
        /// </summary>
        private const int BufferMilliseconds = 120;

        /// <summary>采集侧向 WASAPI 申请的缓冲时长，与网络采集侧一致。</summary>
        private const int CaptureLatencyMs = 10;

        private WasapiCapture wasapiCapture = null;
        private PlaybackGroup group = null;
        private AudioBroadcastBuffer buffer = null;

        private float _Volume = 1;
        /// <summary>本机这一路是不是真的在采也在放。</summary>
        private volatile bool isActive;

        /// <summary>这一路是不是真的建起来了（采集开出来了、播放设备也开出来了）。</summary>
        public bool IsActive { get { return isActive && group != null; } }

        /// <summary>此刻是不是真的有声音在往外放。刚起好还没收到采集数据时不算。</summary>
        public bool AudioFlowing { get { return isActive && group != null && group.IsActive; } }

        /// <summary>此刻真的在出声的设备名。全播时可能有好几个。</summary>
        public List<string> ActiveDeviceNames
        {
            get { return group == null ? new List<string>() : group.ActiveDeviceNames; }
        }

        /// <summary>本机流转这一路的音质指标。</summary>
        public PlaybackQuality Quality
        {
            get { return group == null ? null : group.Quality; }
        }

        public float Volume
        {
            get { return _Volume; }
            set
            {
                _Volume = value;
                if (group != null) group.Volume = value;
            }
        }

        public LocalAudioRedirector(MMDevice sourceDevice, IList<PlaybackTargetSpec> targets, float Volume)
        {
            _Volume = Volume;
            try
            {
                if (sourceDevice.DataFlow == DataFlow.Render)
                {
                    // 输出设备 扬声器、耳机
                    wasapiCapture = new WasapiLoopbackCapture(latency: CaptureLatencyMs);
                }
                else
                {
                    // 输入设备 麦克风
                    wasapiCapture = new WasapiCapture(false, AudioClientShareMode.Shared, latency: CaptureLatencyMs);
                }
                wasapiCapture.Device = sourceDevice;
                wasapiCapture.Initialize();

                var format = wasapiCapture.WaveFormat;
                var capacity = Math.Max(format.BlockAlign * 8,
                    (int)(format.BytesPerSecond / 1000.0 * BufferMilliseconds));
                buffer = new AudioBroadcastBuffer(capacity);
                try
                {
                    group = new PlaybackGroup(buffer, format, targets, _Volume);
                }
                catch (Exception e)
                {
                    // 采集那头是好的，只是目标声卡开不出来。标出来好让主备策略换下一块再试。
                    throw new PlaybackDeviceException(e.Message, e);
                }

                wasapiCapture.DataAvailable += OnDataAvailable;
                wasapiCapture.Start();
                // 采集和播放都起来了，这一路才算真的在放
                isActive = true;
            }
            catch (Exception)
            {
                // 建到一半失败（目标声卡被独占之类）也要把自己收干净：
                // 这一路会被反复重连，漏一次就攒下一份声卡句柄
                Dispose();
                throw;
            }
        }

        /// <summary>
        /// 采集回调：把这一块写进广播缓冲，然后让各块声卡自己看有没有东西可放。
        /// 这里在音频线程上，不能做耗时的事，也不能让异常跑出去把采集打断。
        /// </summary>
        private void OnDataAvailable(object sender, DataAvailableEventArgs e)
        {
            try
            {
                buffer.Write(e.Data, 0, e.ByteCount);
                group.Pump();
            }
            catch (Exception ex)
            {
                Logger.Error("本机流转写入缓冲失败", ex);
            }
        }

        public void SetDevice(MMDevice outputDevice)
        {
            if (group == null) return;
            group.SetDevice(outputDevice);
        }

        private void Stop()
        {
            isActive = false;
            try
            {
                if (wasapiCapture != null) wasapiCapture.DataAvailable -= OnDataAvailable;
            }
            catch (Exception)
            {
            }
            if (wasapiCapture != null && wasapiCapture.RecordingState != RecordingState.Stopped)
                wasapiCapture.Stop();
        }

        public void Dispose()
        {
            Stop();
            if (group != null)
            {
                group.Dispose();
                group = null;
            }
            if (buffer != null)
            {
                buffer.Dispose();
                buffer = null;
            }
            if (wasapiCapture != null)
            {
                wasapiCapture.Dispose();
                wasapiCapture = null;
            }
        }
    }
}
