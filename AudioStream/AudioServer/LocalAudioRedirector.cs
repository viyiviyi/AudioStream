using AudioStream.AudioServer;
using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundIn;
using CSCore.SoundOut;
using CSCore.Streams;
using System;

namespace AudioStream
{
    internal class LocalAudioRedirector : IPlayerRedirector
    {
        private WasapiCapture wasapiCapture = null;
        private WasapiOut wasapiOut = null;

        private float _Volume = 1;
        public float Volume
        {
            get => _Volume; set
            {
                if (wasapiOut == null)
                {
                    _Volume = value;
                }
                else
                {
                    wasapiOut.Volume = _Volume = value;
                }
            }
        }

        public LocalAudioRedirector(MMDevice sourceDevice, MMDevice targetDevice,float Volume)
        {
            _Volume = Volume;
            if (sourceDevice.DeviceID == targetDevice.DeviceID) return;
            if (sourceDevice.DataFlow == DataFlow.Render)
            {
                // 输出设备 扬声器、耳机
                wasapiCapture = new WasapiLoopbackCapture();
            }
            else
            {
                // 输入设备 麦克风
                wasapiCapture = new WasapiCapture();
            }
            wasapiCapture.Device = sourceDevice;
            wasapiCapture.Initialize();
            var soundInSource = new SoundInSource(wasapiCapture);

            wasapiOut = new WasapiOut();
            wasapiOut.Device = targetDevice;
            wasapiOut.Latency = 5;
            wasapiOut.Initialize(soundInSource.ToSampleSource().ToWaveSource());
            wasapiOut.Volume = this.Volume;
            wasapiCapture.DataAvailable += (s, e) =>
            {
                if (wasapiOut.PlaybackState != PlaybackState.Playing)
                    wasapiOut.Play();
            };
            wasapiCapture.Start();
        }
        public void SetDevice(MMDevice outputDevice)
        {
            if (wasapiOut == null) return;
            wasapiCapture.Stop();
            wasapiOut.Stop();
            wasapiOut.Device.Dispose();
            wasapiOut.Device = outputDevice;
            wasapiCapture.Start();
        }
        private void Stop()
        {
            if (wasapiOut != null && wasapiOut.PlaybackState != PlaybackState.Stopped)
                wasapiOut.Stop();
            if (wasapiCapture != null && wasapiCapture.RecordingState != RecordingState.Stopped)
                wasapiCapture.Stop();
        }

        public void Dispose()
        {
            Stop();
            if (wasapiOut != null) wasapiOut.Dispose();
            if (wasapiCapture != null) wasapiCapture.Dispose();
        }
    }
}
