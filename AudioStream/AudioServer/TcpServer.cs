using Common.Helper;
using CSCore;
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;

namespace AudioStream
{
    internal class TcpServer : IDisposable
    {
        private TcpListener _listener;
        private int _port = 12670;
        private readonly List<TcpClient> _clients = new List<TcpClient>();
        private readonly List<TcpAudioServer> tcpAudioServers = new List<TcpAudioServer>();
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
                _listener.Start();
                _isRunning = true;
                Console.WriteLine($"Server started on port {_port}.");
                while (_isRunning)
                {
                    try
                    {
                        TcpClient client = await _listener.AcceptTcpClientAsync();
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
            try
            {
                await Task.Run(() =>
                {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    // 循环读取客户端发送的数据
                    while (_isRunning && clientStream.CanRead && (bytesRead = clientStream.Read(buffer, 0, buffer.Length)) > 0)
                    {
                        // 将接收到的字节数据转换为字符串
                        string dataReceived = Encoding.UTF8.GetString(buffer, 0, bytesRead);
                        Console.WriteLine($"Received: {dataReceived}");
                        bool play = false;
                        if (dataReceived.StartsWith("/Start"))
                        {
                            play = true;
                            audioServer.Start();
                        }
                        if (dataReceived.StartsWith("/Pause"))
                        {
                            audioServer.Stop();
                            play = false;
                        }
                        if (dataReceived.StartsWith("/WaveFormat/"))
                        {
                            var id = dataReceived.Split('/')[2];
                            var device = AudioDeviceHelper.GetDeviceById(id);
                            if (device != null)
                            {
                                audioServer = new TcpAudioServer(device, (data, len) =>
                                {
                                    clientStream.Write(data, 0, len);
                                });
                                tcpAudioServers.Add(audioServer);
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
                    }

                });
            }
            finally
            {
                audioServer?.Dispose();
                tcpAudioServers.Remove(audioServer);
                clientStream.Dispose();
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

        public void Dispose()
        {
            Stop();
        }
    }
}
