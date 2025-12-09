using AudioStream.AudioServer.Model;
using Common.Helper;
using CSCore;
using CSCore.XAudio2.X3DAudio;
using System;
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
    internal class TcpServer : IDisposable
    {
        private TcpListener _listener;
        private int _port = 12670;
        private readonly List<TcpClient> _clients = new List<TcpClient>();
        private readonly List<TcpAudioServer> tcpAudioServers = new List<TcpAudioServer>();
        private readonly List<ClientItem> clientItems = new List<ClientItem>();
        private readonly object _clientsLock = new object(); // For thread safety when accessing _clients
        private bool _isRunning = false;
        public TcpServer()
        {
        }
        public async void StartAsync()
        {
            if (_isRunning)
            {
                Console.WriteLine("Server is already running.");
                return;
            }
            while (NetworkHelper.portInUse(_port, NetworkHelper.PortType.TCP) && _port < 12690)
            {
                _port++;
            }
            try
            {
                _listener = new TcpListener(IPAddress.Any, _port);
                _listener.Server.NoDelay = true;
                _listener.Server.ReceiveBufferSize = 32 * 1024;
                _listener.Server.SendBufferSize = 32 * 1024;
                _listener.Start();
                _isRunning = true;
                Console.WriteLine($"Server started on port {_port}.");
                while (_isRunning)
                {
                    try
                    {
                        TcpClient client = await _listener.AcceptTcpClientAsync();
                        client.NoDelay = true;
                        client.ReceiveBufferSize = 32 * 1024;
                        client.SendBufferSize = 32 * 1024;
                        client.SendTimeout = 2;
                        OnMessage(client);
                        Console.WriteLine($"Client connected: {client.Client.RemoteEndPoint}");
                        lock (_clientsLock)
                        {
                            _clients.Add(client);
                        }
                    }
                    catch (SocketException ex)
                    {
                        Console.WriteLine($"SocketException in AcceptTcpClientAsync: {ex.Message}");
                        // Handle socket exceptions (e.g., server stopped)
                        if (ex.SocketErrorCode == SocketError.Interrupted)
                        {
                            // This usually means the listener was stopped.  Break out of the loop.
                            break;
                        }
                        else
                        {
                            // Handle other socket errors as needed.  Consider logging.
                            Console.WriteLine($"Unhandled SocketException: {ex}");
                        }
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"Exception in AcceptTcpClientAsync: {ex}");
                    }
                    Thread.Sleep(1);
                }
            }
            catch (SocketException ex)
            {
                Console.WriteLine($"SocketException during server startup: {ex.Message}");
            }
            finally
            {
                Stop(); // Ensure the server is stopped in case of exceptions.
            }
        }
        private async void OnMessage(TcpClient client)
        {
            TcpAudioServer audioServer = null;
            NetworkStream clientStream = client.GetStream();
            int _frameSize = 2048;
            byte[] _header = new byte[8];
            try
            {
                await Task.Run(() =>
                {
                    byte[] buffer = new byte[1024];
                    int bytesRead = 0;
                    var isRun = true;
                    var lastTime = Environment.TickCount;
                    // 循环读取客户端发送的数据
                    while (_isRunning && isRun && client.Connected)
                    {
                        try
                        {
                            bytesRead = clientStream.Read(buffer, 0, buffer.Length);
                        }
                        catch (Exception)
                        {
                            break;
                        }
                        if (bytesRead <= 0)
                        {
                            Thread.Sleep(1);
                            continue;
                        }
                        // 将接收到的字节数据转换为字符串
                        string dataReceived = Encoding.UTF8.GetString(buffer, 0, bytesRead);
                        Console.WriteLine($"Received: {dataReceived}");
                        if (dataReceived.StartsWith("/Start"))
                        {
                            lastTime = Environment.TickCount;
                            audioServer.Start();
                        }
                        if (dataReceived.StartsWith("/Pause"))
                        {
                            lastTime = Environment.TickCount;
                            break;
                        }
                        if (dataReceived.StartsWith("/Ping"))
                        {
                            lastTime = Environment.TickCount;
                        }
                        if (dataReceived.StartsWith("/WaveFormat/"))
                        {
                            lastTime = Environment.TickCount;
                            var id = dataReceived.Split('/')[2];
                            if (id.ToLower() == "default")
                            {
                                id = AudioDeviceHelper.GetDefaultOutputDeviceId();
                            }
                            var device = AudioDeviceHelper.GetDeviceById(id);
                            if (device != null)
                            {
                                audioServer = new TcpAudioServer(device, (data, len) =>
                                {
                                    if (!_isRunning)
                                    {
                                        isRun = false;
                                        return;
                                    }
                                    if (!client.Connected)
                                    {
                                        isRun = false;
                                        return;
                                    }
                                    if (Environment.TickCount - lastTime > 40000)
                                    {
                                        isRun = false;
                                        return;
                                    }
                                    try
                                    {
                                        if (clientStream.CanWrite)
                                        {
                                            clientStream.Write(data, 0, len);
                                            clientStream.Flush();
                                        }
                                    }
                                    catch (Exception)
                                    {
                                        isRun = false;
                                        return;
                                    }
                                });
                                tcpAudioServers.Add(audioServer);
                                clientItems.Add(new ClientItem() { DeviceName = device.FriendlyName, ClientIp = ((IPEndPoint)client.Client.RemoteEndPoint).Address.ToString() });
                                using (MemoryStream stream = new MemoryStream())
                                using (BinaryWriter writer = new BinaryWriter(stream))
                                {
                                    var extensibleFormat = audioServer.GetWaveFormat();
                                    writer.Write(extensibleFormat.SampleRate);
                                    writer.Write(extensibleFormat.BitsPerSample);
                                    writer.Write(extensibleFormat.Channels);
                                    if (extensibleFormat.SubFormat == AudioSubTypes.Pcm)
                                    {
                                        Console.WriteLine("音频格式: PCM");
                                        writer.Write((int)AudioEncoding.Pcm);
                                    }
                                    else if (extensibleFormat.SubFormat == AudioSubTypes.IeeeFloat)
                                    {
                                        Console.WriteLine("音频格式: IEEE Float");
                                        writer.Write((int)AudioEncoding.IeeeFloat);
                                    }
                                    else
                                    {
                                        Console.WriteLine("音频格式: 未知");
                                        writer.Write(Encoding.ASCII.GetBytes(extensibleFormat.SubFormat.ToString()));
                                    }
                                    clientStream.Write(stream.ToArray(), 0, (int)stream.Length);
                                    clientStream.Flush();
                                }
                            }
                            else
                            {
                                client.Close();
                                RemoveClient(client);
                            }
                        }
                        Thread.Sleep(1);
                    }

                });
            }
            finally
            {
                clientStream.Dispose();
                client.Dispose();
                audioServer?.Dispose();
                var idx = tcpAudioServers.IndexOf(audioServer);
                if (idx >= 0) clientItems.RemoveAt(idx);
                tcpAudioServers.Remove(audioServer);
            }
        }

        public void Stop()
        {
            if (!_isRunning)
            {
                Console.WriteLine("Server is not running.");
                return;
            }
            _isRunning = false;
            foreach (var audioServer in tcpAudioServers)
            {
                audioServer.Stop();
                audioServer.Dispose();
            }
            Console.WriteLine("Stopping server...");
            // Stop listening for new connections
            try
            {
                _listener?.Stop(); // Stop the listener gracefully
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error stopping listener: {ex.Message}");
            }
            // Close all client connections
            lock (_clientsLock)
            {
                foreach (TcpClient client in _clients)
                {
                    try
                    {
                        client.Close();
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"Error closing client connection: {ex.Message}");
                    }
                }
                _clients.Clear();
            }
            Console.WriteLine("Server stopped.");
        }

        private void RemoveClient(TcpClient client)
        {
            lock (_clientsLock)
            {
                _clients.Remove(client);
            }
        }

        public List<ClientItem> ClientItems()
        {
            return clientItems;
        }

        public void Dispose()
        {
            Stop();
        }
    }
}
