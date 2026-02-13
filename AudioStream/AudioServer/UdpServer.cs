using AudioStream.AudioServer.Model;
using Common;
using Common.Helper;
using CSCore;
using CSCore.XAudio2.X3DAudio;
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AudioStream
{
    internal class UdpServer : IDisposable
    {
        private UdpClient _udpListener;
        private int _port = 12670;
        private readonly ConcurrentDictionary<IPEndPoint, ClientSession> _clientSessions = new ConcurrentDictionary<IPEndPoint, ClientSession>();
        private readonly List<UdpAudioServer> udpAudioServers = new List<UdpAudioServer>();
        private readonly List<ClientItem> clientItems = new List<ClientItem>();
        private readonly object _clientsLock = new object();
        private bool _isRunning = false;
        private CancellationTokenSource _cancellationTokenSource;
        
        public UdpServer()
        {
        }
        
        public async void StartAsync()
        {
            if (_isRunning)
            {
                Console.WriteLine("UDP Server is already running.");
                return;
            }
            
            // 查找可用端口
            while (NetworkHelper.portInUse(_port, NetworkHelper.PortType.UDP) && _port < 12690)
            {
                _port++;
            }
            
            try
            {
                _cancellationTokenSource = new CancellationTokenSource();
                _udpListener = new UdpClient(_port);
                _udpListener.Client.ReceiveBufferSize = 1024 * 1024; // 1MB buffer
                _udpListener.Client.SendBufferSize = 1024 * 1024;
                _isRunning = true;
                
                Console.WriteLine($"UDP Server started on port {_port}.");
                
                // 启动接收循环
                _ = Task.Run(() => ReceiveLoop(_cancellationTokenSource.Token));
                
                // 启动心跳检查循环
                _ = Task.Run(() => HeartbeatCheckLoop(_cancellationTokenSource.Token));
            }
            catch (SocketException ex)
            {
                Console.WriteLine($"SocketException during UDP server startup: {ex.Message}");
                Logger.Error($"SocketException during UDP server startup: {ex.Message}", ex);
            }
        }
        
        private async Task ReceiveLoop(CancellationToken cancellationToken)
        {
            while (!cancellationToken.IsCancellationRequested && _isRunning)
            {
                try
                {
                    UdpReceiveResult result = await _udpListener.ReceiveAsync();
                    IPEndPoint clientEndPoint = result.RemoteEndPoint;
                    byte[] data = result.Buffer;
                    
                    // 更新客户端会话
                    var session = _clientSessions.GetOrAdd(clientEndPoint, ep => new ClientSession(ep));
                    session.LastActivityTime = DateTime.UtcNow;
                    
                    // 处理消息
                    ProcessMessage(clientEndPoint, data, session);
                }
                catch (SocketException ex)
                {
                    if (ex.SocketErrorCode == SocketError.Interrupted)
                    {
                        break;
                    }
                    Logger.Error($"UDP Receive error: {ex.Message}", ex);
                }
                catch (Exception ex)
                {
                    Logger.Error($"UDP Receive error: {ex.Message}", ex);
                }
                
                await Task.Delay(1, cancellationToken);
            }
        }
        
        private async Task HeartbeatCheckLoop(CancellationToken cancellationToken)
        {
            while (!cancellationToken.IsCancellationRequested && _isRunning)
            {
                try
                {
                    var now = DateTime.UtcNow;
                    var inactiveClients = new List<IPEndPoint>();
                    
                    foreach (var kvp in _clientSessions)
                    {
                        var session = kvp.Value;
                        // 如果30秒内没有活动，认为客户端已断开
                        if ((now - session.LastActivityTime).TotalSeconds > 30)
                        {
                            inactiveClients.Add(kvp.Key);
                            Logger.Info($"Client {kvp.Key} inactive for more than 30 seconds, removing.");
                        }
                    }
                    
                    foreach (var client in inactiveClients)
                    {
                        _clientSessions.TryRemove(client, out _);
                    }
                    
                    await Task.Delay(10000, cancellationToken); // 每10秒检查一次
                }
                catch (Exception ex)
                {
                    Logger.Error($"Heartbeat check error: {ex.Message}", ex);
                }
            }
        }
        
        private void ProcessMessage(IPEndPoint clientEndPoint, byte[] data, ClientSession session)
        {
            try
            {
                string message = Encoding.UTF8.GetString(data);
                Console.WriteLine($"UDP Received from {clientEndPoint}: {message}");
                
                if (message.StartsWith("/Start"))
                {
                    if (session.AudioServer != null)
                    {
                        session.AudioServer.Start();
                    }
                }
                else if (message.StartsWith("/Pause"))
                {
                    // 暂停处理
                }
                else if (message.StartsWith("/Ping"))
                {
                    // 心跳响应
                    SendToClient(clientEndPoint, Encoding.UTF8.GetBytes("Pong"));
                }
                else if (message.StartsWith("/WaveFormat/"))
                {
                    var parts = message.Split('/');
                    if (parts.Length >= 3)
                    {
                        var deviceId = parts[2];
                        if (deviceId.ToLower() == "default")
                        {
                            deviceId = AudioDeviceHelper.GetDefaultOutputDeviceId();
                        }
                        
                        var device = AudioDeviceHelper.GetDeviceById(deviceId);
                        if (device != null)
                        {
                            // 创建UDP音频服务器
                            var audioServer = new UdpAudioServer(device, (audioData, length) =>
                            {
                                SendAudioData(clientEndPoint, audioData, length);
                            });
                            
                            session.AudioServer = audioServer;
                            udpAudioServers.Add(audioServer);
                            
                            clientItems.Add(new ClientItem() 
                            { 
                                DeviceName = device.FriendlyName, 
                                ClientIp = clientEndPoint.Address.ToString() 
                            });
                            
                            // 发送音频格式信息
                            SendWaveFormat(clientEndPoint, audioServer.GetWaveFormat());
                        }
                        else
                        {
                            Logger.Error($"Device not found: {deviceId}");
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Logger.Error($"Error processing UDP message: {ex.Message}", ex);
            }
        }
        
        private void SendAudioData(IPEndPoint clientEndPoint, byte[] audioData, int length)
        {
            try
            {
                // 添加简单的包头：4字节长度 + 音频数据
                byte[] packet = new byte[4 + length];
                Buffer.BlockCopy(BitConverter.GetBytes(length), 0, packet, 0, 4);
                Buffer.BlockCopy(audioData, 0, packet, 4, length);
                
                _udpListener.Send(packet, packet.Length, clientEndPoint);
            }
            catch (Exception ex)
            {
                Logger.Error($"Error sending audio data: {ex.Message}", ex);
            }
        }
        
        private void SendWaveFormat(IPEndPoint clientEndPoint, WaveFormat waveFormat)
        {
            try
            {
                using (MemoryStream stream = new MemoryStream())
                using (BinaryWriter writer = new BinaryWriter(stream))
                {
                    writer.Write(waveFormat.SampleRate);
                    writer.Write(waveFormat.BitsPerSample);
                    writer.Write(waveFormat.Channels);
                    
                    if (waveFormat is WaveFormatExtensible extensibleFormat)
                    {
                        if (extensibleFormat.SubFormat == AudioSubTypes.Pcm)
                        {
                            writer.Write((int)AudioEncoding.Pcm);
                        }
                        else if (extensibleFormat.SubFormat == AudioSubTypes.IeeeFloat)
                        {
                            writer.Write((int)AudioEncoding.IeeeFloat);
                        }
                        else
                        {
                            writer.Write(Encoding.ASCII.GetBytes(extensibleFormat.SubFormat.ToString()));
                        }
                    }
                    else
                    {
                        writer.Write((int)waveFormat.WaveFormatTag);
                    }
                    
                    byte[] data = stream.ToArray();
                    SendToClient(clientEndPoint, data);
                }
            }
            catch (Exception ex)
            {
                Logger.Error($"Error sending wave format: {ex.Message}", ex);
            }
        }
        
        private void SendToClient(IPEndPoint clientEndPoint, byte[] data)
        {
            try
            {
                _udpListener.Send(data, data.Length, clientEndPoint);
            }
            catch (Exception ex)
            {
                Logger.Error($"Error sending to client: {ex.Message}", ex);
            }
        }
        
        public void Stop()
        {
            if (!_isRunning)
            {
                Logger.Info("UDP Server is not running.");
                return;
            }
            
            _isRunning = false;
            _cancellationTokenSource?.Cancel();
            
            foreach (var audioServer in udpAudioServers)
            {
                audioServer.Stop();
                audioServer.Dispose();
            }
            
            udpAudioServers.Clear();
            clientItems.Clear();
            _clientSessions.Clear();
            
            try
            {
                _udpListener?.Close();
            }
            catch (Exception ex)
            {
                Logger.Error($"Error closing UDP listener: {ex.Message}", ex);
            }
            
            Logger.Info("UDP Server stopped.");
        }
        
        public List<ClientItem> ClientItems()
        {
            return clientItems;
        }
        
        public void Dispose()
        {
            Stop();
            _cancellationTokenSource?.Dispose();
            _udpListener?.Dispose();
        }
        
        private class ClientSession
        {
            public IPEndPoint EndPoint { get; }
            public DateTime LastActivityTime { get; set; }
            public UdpAudioServer AudioServer { get; set; }
            
            public ClientSession(IPEndPoint endPoint)
            {
                EndPoint = endPoint;
                LastActivityTime = DateTime.UtcNow;
            }
        }
    }
}