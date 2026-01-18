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
using System.Web.Hosting;
namespace AudioStream.AudioServer
{
    internal class NetAudioRedirector : IPlayerRedirector
    {
        private string _address;
        private WasapiOut wasapiOut;
        private Socket clientSocket;
        private Stream stream;
        private WaveFormat waveFormat;
        private NetworkStreamSource soundInSource;
        private int maxDelaySize = 0;
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
                maxDelaySize = (waveFormat.BytesPerSecond / 1000) * 20; // 抓取那边最少抓取间隔是15ms，缓冲量不能小于15ms，否则卡顿严重
                stream = new MemoryStream();
                clientSocket.NoDelay = false;
                clientSocket.ReceiveBufferSize = 32 * 1024;
                //clientSocket.ReceiveTimeout = 50;
                clientSocket.Send(Encoding.UTF8.GetBytes("/Start"));
                soundInSource = new NetworkStreamSource(stream, waveFormat);
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
                if (wasapiOut != null && wasapiOut.PlaybackState != PlaybackState.Playing)
                    wasapiOut.Play();
                Task.Run(() =>
                {
                    using (var _stream = new NetworkStream(clientSocket))
                    {
                        var buff = new byte[1024 * 1024];
                        var lastTime = Environment.TickCount;
                        while (isRun)
                        {
                            try
                            {
                                var len = _stream.Read(buff, 0, buff.Length);
                                if (len <= 32)
                                {
                                    if (Environment.TickCount - lastTime > 20000)
                                    {
                                        clientSocket.Send(Encoding.UTF8.GetBytes("←_←"));
                                    }
                                    if (len > 0)
                                    {
                                        lastTime = Environment.TickCount;
                                    }
                                    Thread.Sleep(1);
                                    continue;
                                }
                                lastTime = Environment.TickCount;
                                var data = new byte[Math.Min(len, maxDelaySize)];
                                Buffer.BlockCopy(buff, len - data.Length, data, 0, data.Length);

                                lock (_stream)
                                {
                                    // 检查当前缓冲区大小
                                    long currentSize = stream.Length;
                                    long currentPosition = stream.Position;
                                    //Console.WriteLine($"{currentSize} {currentPosition} {len} {data.Length} {maxDelaySize}");

                                    // 如果有延迟
                                    if (currentSize > maxDelaySize)
                                    {
                                        // 当有延迟时需要保留的最多数据量
                                        long maxKeep = Math.Max(0, Math.Min(currentSize - currentPosition, maxDelaySize));
                                        if (maxKeep > 0)
                                        {
                                            var cache = new byte[maxKeep];
                                            stream.Position = stream.Length - maxKeep;
                                            stream.Read(cache, 0, cache.Length);
                                            stream.Position = 0;
                                            stream.SetLength(0);
                                            stream.Write(cache, 0, cache.Length);
                                        }
                                        stream.Position = 0;
                                        stream.SetLength(maxKeep);
                                    }
                                    var position = stream.Position;
                                    stream.Position = stream.Length;
                                    stream.Write(data, 0, data.Length);
                                    stream.Position = position;
                                    if (wasapiOut != null && wasapiOut.PlaybackState != PlaybackState.Playing && stream.Length > maxDelaySize / wasapiOut.Latency * 5)
                                    {
                                        wasapiOut.Play();
                                    }
                                }
                                // 更合理的延迟
                                Thread.Sleep(1); // 增加延迟，减少CPU使用率
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
            private readonly Stream _stream;
            private readonly WaveFormat _waveFormat;
            public NetworkStreamSource(Stream stream, WaveFormat waveFormat)
            {
                _stream = stream;
                _waveFormat = waveFormat;
            }
            public WaveFormat WaveFormat => _waveFormat;
            public long Length => -1;
            public long Position { get=> _stream.Position; set=> _stream.Position=value; }

            public bool CanSeek => false;

            public int Read(byte[] buffer, int offset, int count)
            {
                try
                {
                    lock (_stream)
                    {
                        return _stream.Read(buffer, offset, count);
                    }
                }
                catch (Exception ex)
                {
                    return 0;
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
