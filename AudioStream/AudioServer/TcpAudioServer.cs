
using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundIn;
using System;
using System.Net.Sockets;
using System.Text.RegularExpressions;
using System.Threading;

namespace AudioStream
{
    internal class TcpAudioServer : IDisposable
    {
        private WasapiCapture wasapiCapture = null;
        private Action<byte[], int> sendAudio;
        private void Data_Available(object s, DataAvailableEventArgs e)
        {
            sendAudio?.Invoke(e.Data, e.ByteCount);
        }
        public TcpAudioServer(MMDevice sourceDevice, Action<byte[], int> sendAudio)
        {
            this.sendAudio = sendAudio;
            WaveFormat waveFormat = new WaveFormat(48000, 24, 2);
            if (sourceDevice.DataFlow == DataFlow.Render)
            {
                // 输出设备 扬声器、耳机
                wasapiCapture = new WasapiLoopbackCapture(latency: 10, waveFormat, ThreadPriority.AboveNormal);
            }
            else
            {
                // 输入设备 麦克风
                wasapiCapture = new WasapiCapture(false, AudioClientShareMode.Shared, latency:10, waveFormat);
            }
            wasapiCapture.Device = sourceDevice;
            wasapiCapture.Initialize();
        }
        public WaveFormat GetWaveFormat()
        {
            WaveFormat extensibleFormat = wasapiCapture.WaveFormat;
            return extensibleFormat;
        }
        public void Start()
        {
            wasapiCapture.DataAvailable += Data_Available;
            wasapiCapture.Start();
        }
        
        public void Stop()
        {
            wasapiCapture.DataAvailable -= Data_Available;
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
