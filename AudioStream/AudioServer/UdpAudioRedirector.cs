using AudioStream.AudioServer.Model;
using Common;
using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundOut;
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AudioStream.AudioServer
{
    internal class UdpAudioRedirector : IPlayerRedirector
    {
        private string _address;
        private WasapiOut wasapiOut;
        private UdpClient udpClient;
        private IPEndPoint serverEndPoint;
        private LimitedBuffer audioBuffer;
        private WaveFormat waveFormat;
        private NetworkStreamSource soundInSource;
        private int maxDelaySize = 0;
        private int bufferTime = 64; // 缓冲毫秒数
        private Timer timer;
        private float _Volume = 1;
        private bool isRun = false;
        private CancellationTokenSource cancellationTokenSource;
        
        public float Volume
        {
            get => _Volume;
            set
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
        
        public UdpAudioRedirector(MMDevice outputDevice, string address, string sourceDeviceID = null, float Volume = 1)
        {
            _Volume = Volume;
            _address = address;
            isRun = true;
            cancellationTokenSource = new CancellationTokenSource();
            
            try
            {
                // 连接到远程设备
                Connect();
                
                // 获取远程设备的音频编码
                waveFormat = GetWaveFormatExtensible(sourceDeviceID);
                
                wasapiOut = new WasapiOut();
                wasapiOut.Device = outputDevice;
                wasapiOut.Latency = 1;
                
                maxDelaySize = (waveFormat.BytesPerSecond / 1000) * bufferTime;
                audioBuffer = new LimitedBuffer(maxDelaySize);
                
                udpClient.Client.ReceiveBufferSize = 1024 * 1024;
                
                // 发送开始命令
                SendCommand("/Start");
                
                soundInSource = new NetworkStreamSource(audioBuffer, waveFormat);
                Console.WriteLine("WaveFormat: " + waveFormat.ToString());
                
                wasapiOut.Initialize(soundInSource.ToSampleSource().ToWaveSource());
                wasapiOut.Volume = this.Volume;
                
                // 心跳定时器
                timer = new Timer((t) =>
                {
                    try
                    {
                        SendCommand("/Ping");
                    }
                    catch (Exception ex)
                    {
                        Logger.Error($"心跳发送失败 {ex.Message}\n{ex.StackTrace}", ex);
                    }
                }, null, 1000, 10 * 1000);
                
                // 启动接收循环
                _ = Task.Run(() => ReceiveLoop(cancellationTokenSource.Token));
            }
            catch (Exception ex)
            {
                isRun = false;
                Logger.Error($"连接出错 {ex.Message}\n{ex.StackTrace}", ex);
            }
        }
        
        private async Task ReceiveLoop(CancellationToken cancellationToken)
        {
            while (!cancellationToken.IsCancellationRequested && isRun)
            {
                try
                {
                    var result = await udpClient.ReceiveAsync();
                    byte[] data = result.Buffer;
                    
                    // 处理数据包
                    if (data.Length >= 4)
                    {
                        int packetLength = BitConverter.ToInt32(data, 0);
                        
                        if (packetLength > 0 && data.Length >= 4 + packetLength)
                        {
                            // 音频数据包
                            byte[] audioData = new byte[packetLength];
                            Buffer.BlockCopy(data, 4, audioData, 0, packetLength);
                            audioBuffer.WriteToCircularBuffer(audioData, 0, packetLength);
                            
                            if (wasapiOut != null && wasapiOut.PlaybackState != PlaybackState.Playing && audioBuffer.AvailableData > 0)
                            {
                                wasapiOut.Play();
                            }
                        }
                        else if (packetLength == 0 && data.Length == 4)
                        {
                            // 心跳响应
                            Console.WriteLine("收到心跳响应");
                        }
                        else
                        {
                            // 可能是音频格式信息或其他控制信息
                            ProcessControlMessage(data);
                        }
                    }
                }
                catch (SocketException ex)
                {
                    if (ex.SocketErrorCode == SocketError.Interrupted)
                    {
                        break;
                    }
                    Logger.Error($"UDP接收错误: {ex.Message}", ex);
                }
                catch (Exception ex)
                {
                    Logger.Error($"UDP接收错误: {ex.Message}", ex);
                }
                
                await Task.Delay(1, cancellationToken);
            }
        }
        
        private void ProcessControlMessage(byte[] data)
        {
            try
            {
                // 尝试解析为控制消息
                string message = Encoding.UTF8.GetString(data);
                if (message == "Pong")
                {
                    Console.WriteLine("收到心跳响应: Pong");
                }
                else
                {
                    // 可能是音频格式信息
                    using (MemoryStream stream = new MemoryStream(data))
                    using (BinaryReader reader = new BinaryReader(stream))
                    {
                        if (data.Length >= 12)
                        {
                            int sampleRate = reader.ReadInt32();
                            int bitsPerSample = reader.ReadInt32();
                            int channels = reader.ReadInt32();
                            
                            // 这里可以根据需要处理音频格式信息
                            Console.WriteLine($"收到音频格式: {sampleRate}Hz, {bitsPerSample}bit, {channels}ch");
                        }
                    }
                }
            }
            catch
            {
                // 不是文本消息，忽略
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
            udpClient = new UdpClient();
            udpClient.Client.ReceiveBufferSize = 1024 * 1024;
            udpClient.Client.SendBufferSize = 1024 * 1024;
            
            int port = 12670;
            while (port < 12690)
            {
                try
                {
                    // 先尝试连接获取服务器端点
                    serverEndPoint = new IPEndPoint(IPAddress.Parse(_address), port);
                    
                    // 发送测试消息
                    SendCommand("/Ping");
                    
                    // 等待响应
                    var task = udpClient.ReceiveAsync();
                    if (task.Wait(1000))
                    {
                        Console.WriteLine($"连接到UDP服务器 {_address}:{port}");
                        break;
                    }
                    else
                    {
                        port++;
                    }
                }
                catch (Exception)
                {
                    port++;
                }
            }
            
            // 请求音频格式
            SendCommand($"/WaveFormat/0");
        }
        
        private void SendCommand(string command)
        {
            try
            {
                byte[] data = Encoding.UTF8.GetBytes(command);
                udpClient.Send(data, data.Length, serverEndPoint);
            }
            catch (Exception ex)
            {
                Logger.Error($"发送命令失败: {ex.Message}", ex);
            }
        }
        
        private WaveFormat GetWaveFormatExtensible(string sourceDeviceID = null)
        {
            // 发送请求并等待响应
            SendCommand($"/WaveFormat/{sourceDeviceID ?? "0"}");
            
            // 等待音频格式响应
            var task = udpClient.ReceiveAsync();
            if (task.Wait(3000))
            {
                var result = task.Result;
                byte[] waveFormatBytes = result.Buffer;
                
                if (waveFormatBytes.Length > 12)
                {
                    using (MemoryStream stream = new MemoryStream(waveFormatBytes))
                    using (BinaryReader reader = new BinaryReader(stream))
                    {
                        try
                        {
                            int sampleRate = reader.ReadInt32();
                            int bitsPerSample = reader.ReadInt32();
                            int channels = reader.ReadInt32();
                            
                            if (waveFormatBytes.Length > 16)
                            {
                                int encoding = reader.ReadInt32();
                                return new WaveFormat(sampleRate, bitsPerSample, channels, (AudioEncoding)encoding);
                            }
                            else
                            {
                                return new WaveFormatExtensible(sampleRate, bitsPerSample, channels, AudioSubTypes.Pcm);
                            }
                        }
                        catch (Exception ex)
                        {
                            Logger.Error($"解析音频格式失败: {ex.Message}", ex);
                        }
                    }
                }
            }
            
            // 默认格式
            return new WaveFormatExtensible(48000, 32, 2, AudioSubTypes.Pcm);
        }
        
        public void Dispose()
        {
            isRun = false;
            cancellationTokenSource?.Cancel();
            timer?.Dispose();
            timer = null;
            
            try
            {
                SendCommand("/Pause");
            }
            catch
            {
            }
            
            udpClient?.Close();
            udpClient = null;
            wasapiOut?.Dispose();
            wasapiOut = null;
            soundInSource?.Dispose();
            soundInSource = null;
            cancellationTokenSource?.Dispose();
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
                // Do not dispose the stream here, as it's managed by the main class.
            }
        }
    }
}