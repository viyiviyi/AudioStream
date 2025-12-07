using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundOut;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Runtime.Remoting.Contexts;
using System.Text;
using System.Threading.Tasks;

namespace AudioStream.AudioServer
{
    internal class NetAudioRedirector : IPlayerRedirector
    {
        private string _address;
        private WasapiOut wasapiOut;
        private Socket clientSocket;
        private NetworkStream stream;
        private WaveFormat waveFormat;
        private NetworkStreamSource soundInSource;
        public float Volume { get => wasapiOut != null ? wasapiOut.Volume : 1; set => wasapiOut.Volume = value; }
        public NetAudioRedirector(MMDevice outputDevice, string address,string sourceDeviceID = null)
        {
            _address = address;
            try
            {
                // 连接到远程设备
                Connect();
                // 获取远程设备的音频编码
                waveFormat = GetWaveFormatExtensible(sourceDeviceID);

                wasapiOut = new WasapiOut();
                wasapiOut.Device = outputDevice;
                wasapiOut.Latency = 1;
                stream = new NetworkStream(clientSocket);
                clientSocket.NoDelay = false;
                clientSocket.Send(Encoding.UTF8.GetBytes("/Start"));
                soundInSource = new NetworkStreamSource(stream, waveFormat);
                Console.WriteLine("WaveFormat: " + waveFormat.ToString());
                wasapiOut.Initialize(soundInSource.ToSampleSource().ToWaveSource());
                wasapiOut.Volume = 1;
                if (wasapiOut != null && wasapiOut.PlaybackState != PlaybackState.Playing)
                    wasapiOut.Play();
            }
            catch (Exception e)
            {
                Console.WriteLine(e.Message);
            }
        }

        public void SetDevice(MMDevice outputDevice)
        {
            if (wasapiOut == null) return;
            wasapiOut.Stop();
            wasapiOut.Device.Dispose();
            wasapiOut.Device = outputDevice;
            wasapiOut.Play();
        }

        private void Connect()
        {
            clientSocket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            int port = 12670;
            while (port < 12690)
            {
                try
                {
                    clientSocket.Connect(_address, port);
                    break;
                }
                catch (Exception)
                {
                    port++;
                }
            }
        }

        private WaveFormat GetWaveFormatExtensible(string sourceDeviceID = null)
        {
            byte[] waveFormatBytes = new byte[1024];
            clientSocket.Send(Encoding.UTF8.GetBytes("/WaveFormat/"+ sourceDeviceID??"0"));
            var len = clientSocket.Receive(waveFormatBytes);
            if (len > 36)
            {
                using (MemoryStream stream = new MemoryStream(waveFormatBytes))
                using (BinaryReader reader = new BinaryReader(stream))
                {
                    WaveFormatExtensible waveFormat = new WaveFormatExtensible(
                        reader.ReadInt32(),          // wFormatTag
                        reader.ReadInt32(),          // nChannels
                        reader.ReadInt32(),          // nSamplesPerSec
                        Guid.Parse(Encoding.ASCII.GetString(reader.ReadBytes(36)))
                    );
                    return waveFormat;
                }
            }else if (len > 12)
            {
                using (MemoryStream stream = new MemoryStream(waveFormatBytes))
                using (BinaryReader reader = new BinaryReader(stream))
                {
                    WaveFormat waveFormat = new WaveFormat(
                        reader.ReadInt32(),          // wFormatTag
                        reader.ReadInt32(),          // nChannels
                        reader.ReadInt32(),          // nSamplesPerSec
                        (AudioEncoding)reader.ReadInt32()
                    );
                    return waveFormat;
                }
            }
            return new WaveFormatExtensible(48000, 32, 2, Guid.Parse("00000003-0000-0010-8000-00aa00389b71"));
        }

        public void Dispose()
        {
            clientSocket?.Send(Encoding.UTF8.GetBytes("/Pause"));
            wasapiOut?.Dispose();
            wasapiOut = null;
            soundInSource?.Dispose();
            soundInSource = null;
            clientSocket?.Dispose();
            clientSocket = null;
        }
        private class NetworkStreamSource : IWaveSource
        {
            private readonly NetworkStream _stream;
            private readonly WaveFormat _waveFormat;
            public NetworkStreamSource(NetworkStream stream, WaveFormat waveFormat)
            {
                _stream = stream;
                _waveFormat = waveFormat;
            }
            public WaveFormat WaveFormat => _waveFormat;
            public long Length => -1; // Unknown length
            public long Position { get; set; } // Not supported

            public bool CanSeek => false;

            public int Read(byte[] buffer, int offset, int count)
            {
                try
                {
                    return _stream.Read(buffer, offset, count);
                }
                catch (Exception ex)
                {
                    return 0; // Indicate end of stream or error
                }
            }
            public void Dispose()
            {
                _stream.Dispose();
                // Do not dispose the stream here, as it's managed by the main class.
            }
        }
    }
}
