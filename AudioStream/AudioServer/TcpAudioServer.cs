
using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundIn;
using System;

namespace AudioStream
{
    internal class TcpAudioServer : IDisposable
    {
        /// <summary>采集侧向 WASAPI 申请的缓冲时长（毫秒）。共享模式的一个引擎周期是 10ms，取它最稳。</summary>
        private const int CaptureLatencyMs = 10;

        private WasapiCapture wasapiCapture = null;
        private Action<byte[], int> sendAudio;
        /// <summary>采集是否已经在跑。共享采集池会让多路拉取都来调 Start，重复挂事件会把同一份数据发好几遍。</summary>
        private bool started;
        private void Data_Available(object s, DataAvailableEventArgs e)
        {
            sendAudio?.Invoke(e.Data, e.ByteCount);
        }
        public TcpAudioServer(MMDevice sourceDevice, Action<byte[], int> sendAudio)
        {
            this.sendAudio = sendAudio;
            if (sourceDevice.DataFlow == DataFlow.Render)
            {
                // 输出设备 扬声器、耳机
                // latency 是向 WASAPI 申请的缓冲时长。5ms 比共享模式的引擎周期还短，
                // 系统只能向上取整，反而更容易因为线程调度晚一拍而丢数据；10ms 是共享模式的一整个周期。
                wasapiCapture = new WasapiLoopbackCapture(latency: CaptureLatencyMs);
            }
            else
            {
                // 输入设备 麦克风
                wasapiCapture = new WasapiCapture(false, AudioClientShareMode.Shared, latency: CaptureLatencyMs);
            }
            wasapiCapture.Device = sourceDevice;
            wasapiCapture.Initialize();
        }
        public WaveFormat GetWaveFormat()
        {
            WaveFormat extensibleFormat = wasapiCapture.WaveFormat;
            return extensibleFormat;
        }
        /// <summary>开始采集。已经在跑时是空操作，所以共享它的每一路都可以放心地调。</summary>
        public void Start()
        {
            if (started) return;
            started = true;
            wasapiCapture.DataAvailable += Data_Available;
            wasapiCapture.Start();
        }
        
        public void Stop()
        {
            wasapiCapture.DataAvailable -= Data_Available;
            started = false;
            if (wasapiCapture.RecordingState != RecordingState.Stopped)
                wasapiCapture.Stop();
        }

        public void Dispose()
        {
            Stop();
            wasapiCapture.Dispose();
        }
    }
}
