using AudioStream.AudioServer.Model;
using Common;
using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundOut;
using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
namespace AudioStream.AudioServer
{
    internal class NetAudioRedirector : IPlayerRedirector
    {
        private string _address;
        private WasapiOut wasapiOut;
        private Socket clientSocket;
        private LimitedBuffer audioBuffer;
        private WaveFormat waveFormat;
        private NetworkStreamSource soundInSource;
        private int maxDelaySize = 0;
        private int bufferTime = 64; // 缓冲毫秒数
        private Timer timer;
        private float _Volume = 1;
        private bool isRun = false;
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
                    try
                    {
                        wasapiOut.Volume = _Volume = value;
                    }
                    catch (Exception)
                    {
                    }
                }
            }
        }
        public NetAudioRedirector(MMDevice outputDevice, string address, string sourceDeviceID = null, float Volume = 1)
        {
            _Volume = Volume;
            _address = address;
            isRun = true;
            try
            {
                // 连接到远程设备
                Connect();
                // 获取远程设备的音频编码
                waveFormat = GetWaveFormatExtensible(sourceDeviceID);

                wasapiOut = new WasapiOut();
                wasapiOut.Device = outputDevice;
                wasapiOut.Latency = 1;
                maxDelaySize = (waveFormat.BytesPerSecond / 1000) * bufferTime; // 抓取那边最少抓取间隔是15ms，缓冲量不能小于15ms，否则卡顿严重
                audioBuffer = new LimitedBuffer(maxDelaySize);
                clientSocket.NoDelay = false;
                clientSocket.ReceiveBufferSize = 1024 * 1024;
                //clientSocket.ReceiveTimeout = 50;
                clientSocket.Send(Encoding.UTF8.GetBytes("/Start"));
                soundInSource = new NetworkStreamSource(audioBuffer, waveFormat);
                Console.WriteLine("WaveFormat: " + waveFormat.ToString());
                wasapiOut.Initialize(soundInSource.ToSampleSource().ToWaveSource());
                wasapiOut.Volume = this.Volume;
                timer = new Timer((t) =>
                {
                    try
                    {
                        clientSocket.Send(Encoding.UTF8.GetBytes("/Ping"));
                    }
                    catch (Exception ex)
                    {
                        Logger.Error($"心跳发送失败 {ex.Message}\n{ex.StackTrace}", ex);
                    }
                }, null, 1000, 10 * 1000);

                Task.Run(async () =>
                {
                    using (var _stream = new NetworkStream(clientSocket))
                    {
                        var buff = new byte[1024 * 1024];
                        var lastTime = Environment.TickCount;
                        var count = 0;
                        var lastR = Environment.TickCount;
                        while (isRun)
                        {
                            // 不要等待，Windows 系统时钟分辨率大概是15.6ms，会导致最小间隔实际是15.6ms
                            try
                            {
                                count++;
                                if (Environment.TickCount - lastR >= 1000)
                                {
                                    Console.WriteLine("获取数据次数 " + count);
                                    count = 0;
                                    lastR = Environment.TickCount;
                                }
                                var len = await _stream.ReadAsync(buff, 0, buff.Length);
                                if (len <= 32)
                                {
                                    if (len > 0)
                                    {
                                        lastTime = Environment.TickCount;
                                    }
                                    continue;
                                }
                                lastTime = Environment.TickCount;
                                audioBuffer.WriteToCircularBuffer(buff, 0, len);
                                if (wasapiOut != null && wasapiOut.PlaybackState != PlaybackState.Playing && audioBuffer.AvailableData > 0)
                                {
                                    wasapiOut.Play();
                                }
                            }
                            catch (Exception e)
                            {
                                continue;
                            }
                        }
                    }
                });
            }
            catch (Exception ex)
            {
                isRun = false;
                Logger.Error($"连接出错 {ex.Message}\n{ex.StackTrace}", ex);
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
            isRun = false;
            timer?.Dispose();
            timer = null;
            try
            {
                clientSocket?.Send(Encoding.UTF8.GetBytes("/Pause"));
            }
            catch
            {

            }
            clientSocket?.Dispose();
            clientSocket = null;
            wasapiOut?.Dispose();
            wasapiOut = null;
            soundInSource?.Dispose();
            soundInSource = null;
            clientSocket?.Dispose();
        }
        private class NetworkStreamSource : IWaveSource
        {
            private readonly LimitedBuffer _stream;
            private readonly WaveFormat _waveFormat;
            public NetworkStreamSource(LimitedBuffer stream, WaveFormat waveFormat)
            {
                _stream = stream;
                _waveFormat = waveFormat;
            }
            public WaveFormat WaveFormat => _waveFormat;
            public long Length => -1;
            public long Position { get; set; }

            public bool CanSeek => false;
            public int Read(byte[] buffer, int offset, int count)
            {
                try
                {
                    return _stream.Read(buffer, offset, count);
                }
                catch (Exception ex)
                {
                    return 0;
                }
            }
            public void Dispose()
            {
                //_stream.Dispose();
                // Do not dispose the stream here, as it's managed by the main class.
            }
        }
    }
}
