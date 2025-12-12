using Common;
using Common.Helper;
using CSCore.CoreAudioAPI;
using System;
using System.Threading.Tasks;

namespace AudioStream.AudioServer
{
    public class Player : IDisposable
    {
        private readonly PlayerInfo playerInfo;
        private IPlayerRedirector audioRedirector;
        private MMDeviceEnumerator deviceEnumerator;
        public Guid ID { get => playerInfo.ID; }
        public Player(PlayerInfo info)
        {
            deviceEnumerator = new MMDeviceEnumerator();
            playerInfo = info;
        }

        public void OnDefaultChange(Object obj, DefaultDeviceChangedEventArgs args)
        {
            var defaultDeviceId = args.DeviceId;
            if (!string.IsNullOrWhiteSpace(defaultDeviceId))
            {
                var device = AudioDeviceHelper.GetDeviceById(defaultDeviceId);
                if (device != null)
                {
                    audioRedirector.SetDevice(device);
                }
            }
        }

        public async void Start()
        {
            var deviceId = playerInfo.TargetDeiceID;
            if (deviceId.ToLower() == "default")
            {
                deviceId = AudioDeviceHelper.GetDefaultOutputDeviceId();
                deviceEnumerator.DefaultDeviceChanged += OnDefaultChange;
            }
            var device = AudioDeviceHelper.GetDeviceById(deviceId);
            if (device == null) return;
            playerInfo.Play = true;
            await Task.Run(() =>
            {
                if (Tools.IsPrivateIPAddress(playerInfo.IP))
                {
                    audioRedirector = new NetAudioRedirector(device, playerInfo.IP, playerInfo.SourceDeviceID, playerInfo.Volume);
                }
                else
                {
                    var sourceDeviceId = playerInfo.SourceDeviceID;
                    if (sourceDeviceId.ToLower() == "default")
                    {
                        sourceDeviceId = AudioDeviceHelper.GetDefaultOutputDeviceId();
                    }
                    if (sourceDeviceId == deviceId) return;
                    var sourceDevice = AudioDeviceHelper.GetDeviceById(sourceDeviceId);
                    audioRedirector = new LocalAudioRedirector(sourceDevice, device, playerInfo.Volume);
                }
            });
        }

        public void SetVolume(float Volume)
        {
            if (audioRedirector == null) return;
            audioRedirector.Volume = Volume;
            playerInfo.Volume = Volume;
        }

        public float GetVolume()
        {
            if (audioRedirector == null) return 1;
            return audioRedirector.Volume;
        }

        public void Dispose()
        {
            playerInfo.Play = false;
            if (audioRedirector != null)
                audioRedirector.Dispose();
            if (deviceEnumerator != null)
            {
                deviceEnumerator.DefaultDeviceChanged -= OnDefaultChange;
                deviceEnumerator.Dispose();
            }
        }
    }
}
