using System;
using System.Threading.Tasks;
using Common;
using Common.Helper;

namespace AudioStream.AudioServer
{
    public class Player : IDisposable
    {
        private readonly PlayerInfo playerInfo;
        private IPlayerRedirector audioRedirector;
        public Guid ID { get => playerInfo.ID; }
        public Player(PlayerInfo info)
        {
            playerInfo = info;
        }

        public async void Start()
        {
            var device = AudioDeviceHelper.GetDeviceById(playerInfo.TargetDeiceID);
            if (device == null) return;
            playerInfo.Play = true;
            await Task.Run(() => {
                if (Tools.IsPrivateIPAddress(playerInfo.IP))
                {
                    audioRedirector = new NetAudioRedirector(device, playerInfo.IP, playerInfo.SourceDeviceID);
                }
                else
                {
                    var sourceDevice = AudioDeviceHelper.GetDeviceById(playerInfo.SourceDeviceID);
                    audioRedirector = new LocalAudioRedirector(sourceDevice, device);
                }
            });
        }

        public void SetVolume(float Volume)
        {
            audioRedirector.Volume = Volume;
        }

        public float GetVolume()
        {
            return audioRedirector.Volume;
        }

        public void Dispose()
        {
            playerInfo.Play = false;
            audioRedirector.Dispose();
        }
    }
}
