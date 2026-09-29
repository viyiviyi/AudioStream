using AudioStream.AudioServer;
using AudioStream.AudioServer.Model;
using Common;
using Common.Helper;
using CSCore;
using CSCore.CoreAudioAPI;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AudioStream
{
    /// <summary>
    /// 被拉取侧：监听 TCP，按对方的点单把某个音频设备采集出的 PCM 直接推过去。
    ///
    /// 这里同时是防止环路的第一道关口。握手时对方必须报上自己的身份，
    /// 一旦发现报的就是本机身份，说明这是一条「自己拉自己」的连接——
    /// 这种连接必然把本机的声音又绕回本机，直接拒绝，连设备都不必开。
    /// </summary>
    internal class TcpServer : IDisposable
    {
        /// <summary>
        /// 每条连接出站队列的字节上限。
        /// 队列是用来吸收「发送线程这一瞬间没被调度上」这种抖动的，正常应该常空；
        /// 积压超过这个量说明对端收不动了，再攒下去只会让延迟无限变大，宁可丢掉最旧的音频。
        /// 64KB 对 48kHz/32bit/立体声（约 384KB/s）相当于 170 毫秒。
        /// </summary>
        private const int MaxQueuedBytes = 64 * 1024;

        private TcpListener _listener;
        private int _port = 12670;
        private readonly List<TcpClient> _clients = new List<TcpClient>();
        private readonly Dictionary<TcpClient, ClientSession> _sessions = new Dictionary<TcpClient, ClientSession>();
        private readonly object _clientsLock = new object(); // For thread safety when accessing _clients
        /// <summary>按设备号收敛的采集池：同一个设备被多路拉取时只开一份采集。</summary>
        private readonly Dictionary<string, SharedCapture> _captures = new Dictionary<string, SharedCapture>(StringComparer.OrdinalIgnoreCase);
        // tcp服务状态
        private bool _isRunning = false;
        public TcpServer()
        {
        }

        /// <summary>实际监听的端口（默认端口被占用时会向后顺延）。</summary>
        public int Port
        {
            get { return _port; }
        }

        /// <summary>
        /// 判断「本机是否正把从别处拉来的音频播到这个设备上」。
        /// 由 InitServer 接到播放控制上：靠它拦住「一边采这个设备给别人、一边又往这个设备放别人的声音」这种点单。
        /// 没接上时一律当作没有冲突，不影响原有功能。
        /// </summary>
        public Func<string, bool> NetworkPlaybackProbe { get; set; }

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
                _listener.Server.Ttl = 5;
                _listener.Server.ReceiveBufferSize = 32 * 1024;
                _listener.Server.SendBufferSize = 512 * 1024;
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
                        client.SendBufferSize = 512 * 1024; // 发送缓冲足够大，避免音频发送时TCP背压导致卡顿
                        //client.SendTimeout = 50;
                        OnMessage(client);
                    }
                    catch (SocketException ex)
                    {
                        Logger.Info($"SocketException in AcceptTcpClientAsync: {ex.Message}");
                        // Handle socket exceptions (e.g., server stopped)
                        if (ex.SocketErrorCode == SocketError.Interrupted)
                        {
                            // This usually means the listener was stopped.  Break out of the loop.
                            break;
                        }
                        else
                        {
                            // Handle other socket errors as needed.  Consider logging.
                            Logger.Error($"Unhandled SocketException: {ex}", ex);
                        }
                    }
                    catch (Exception ex)
                    {
                        Logger.Error($"Exception in AcceptTcpClientAsync: ", ex);
                    }
                    Thread.Sleep(10);
                }
            }
            catch (SocketException ex)
            {
                Console.WriteLine($"SocketException during server startup: {ex.Message}");
                Logger.Error($"SocketException during server startup: {ex.Message}", ex);
            }
            finally
            {
                Stop(); // Ensure the server is stopped in case of exceptions.
            }
        }

        private void OnMessage(TcpClient client)
        {
            var stream = client.GetStream();
            var session = new ClientSession
            {
                Client = client,
                Stream = stream,
                Out = new OutgoingQueue(stream, MaxQueuedBytes),
                Item = new ClientItem
                {
                    ClientIp = ((IPEndPoint)client.Client.RemoteEndPoint).Address.ToString(),
                    ConnectedAt = DateTime.Now
                }
            };
            client.ReceiveTimeout = 1000 * 30;
            lock (_clientsLock)
            {
                _clients.Add(client);
                _sessions[client] = session;
            }
            Console.WriteLine($"Client connected: {session.Item.ClientIp}");
            Task.Run(() => ReadLoop(session));
        }

        private void ReadLoop(ClientSession session)
        {
            var reader = new FrameReader(session.Stream);
            try
            {
                while (_isRunning && session.Running && session.Client.Connected)
                {
                    FrameKind kind;
                    int count;
                    bool got;
                    try
                    {
                        got = reader.ReadNext(out kind, out count);
                    }
                    catch (Exception)
                    {
                        // 读超时、对方强拆连接都会走到这里。会话由 finally 收尾。
                        break;
                    }
                    if (!got) break;

                    // 这条连接的方向是「本机发音频、对方发命令」。收到音频帧说明对端不是本程序，
                    // 丢掉继续读——按帧读有个好处：丢一条不会让后面全部错位。
                    if (kind != FrameKind.Text) continue;

                    var line = reader.ReadText(count).Trim();
                    if (line.Length == 0) continue;
                    session.LastCommandAt = Environment.TickCount;
                    try
                    {
                        HandleCommand(session, line);
                    }
                    catch (Exception ex)
                    {
                        // 一条命令出错以前会让整条连接无声无息地断掉，日志里一个字都没有，
                        // 现象就只是「对端点单之后收不到音频」，完全看不出原因。这里至少要留下它。
                        Logger.Error("处理命令失败，断开这条连接：" + line, ex);
                        session.Running = false;
                    }
                }
            }
            finally
            {
                CloseSession(session);
            }
        }

        /// <summary>
        /// 处理一条命令。命令都以 <c>/</c> 开头，参数用 <c>/</c> 分隔，末尾换行已经去掉。
        /// </summary>
        private void HandleCommand(ClientSession session, string line)
        {
            // 握手：报身份。对端身份等于本机身份时这路流必定绕回自己，就地断开。
            if (line.StartsWith("/Hello/"))
            {
                var parts = line.Split(new[] { '/' }, 4);
                var machineId = parts.Length > 2 ? parts[2] : null;
                var pcName = parts.Length > 3 ? parts[3] : null;
                if (!string.IsNullOrEmpty(pcName))
                {
                    try { pcName = Uri.UnescapeDataString(pcName); }
                    catch (Exception) { /* 名字只是给人看的，解不出来就原样留着 */ }
                }
                session.Item.MachineId = machineId;
                session.Item.PcName = pcName;
                if (!string.IsNullOrEmpty(machineId) &&
                    string.Equals(machineId, MachineIdentity.Id, StringComparison.OrdinalIgnoreCase))
                {
                    Logger.Info($"拒绝来自 {session.Item.ClientIp} 的自连：对端身份与本机相同，这条连接会把本机声音绕回本机");
                    session.Item.Streaming = false;
                    SendText(session, "/Reject/self", true);
                    session.Running = false;
                    return;
                }
                SendText(session, "/Hello/" + MachineIdentity.Id + "/" + Uri.EscapeDataString(MachineIdentity.Name ?? ""));
                return;
            }

            if (line.StartsWith("/WaveFormat/"))
            {
                var segments = line.Split('/');
                var requestedId = segments.Length > 2 ? segments[2] : null;
                // 点单里的 "default"（默认输出设备，环回）与 "default-input"（默认输入设备）
                // 由本机这一侧解析，所以对方选的是「本机此刻的默认设备」：
                // 本机换了默认扬声器或默认麦克风，对方那条拉取自动跟着走，不必回来重配。
                string resolveError;
                var id = AudioDeviceHelper.ResolveSourceDeviceId(requestedId, out resolveError);
                if (id == null)
                {
                    // 默认设备此刻不存在（比如本机根本没插麦克风）。这是「此刻不行」，
                    // 要说得出原因，别把空设备号交给声卡去抛一个更难懂的异常。
                    Logger.Info($"拒绝来自 {session.Item.ClientIp} 的点单：{resolveError}（对方点的是 {requestedId}）");
                    session.Item.Streaming = false;
                    session.Item.Note = resolveError;
                    // 把原因一起带回去（要转义，原因里可能带斜杠）：对方界面上显示的
                    // 就是「本机此刻没有可用的默认输入设备」这种能照着处理的话，而不是一句笼统的拒绝。
                    SendText(session, "/Reject/nodevice/" + Uri.EscapeDataString(resolveError ?? ""), true);
                    session.Running = false;
                    return;
                }
                var device = AudioDeviceHelper.GetDeviceById(id);
                if (device == null)
                {
                    if (segments.Length > 3)
                    {
                        var deviceName = segments[3];
                        device = AudioDeviceHelper.GetDeviceByName(deviceName);
                    }
                }
                if (device == null)
                {
                    Logger.Info($"对端点单的音频设备不存在，断开：{line}");
                    session.Running = false;
                    session.Client.Close();
                    return;
                }

                // 授权判定：本机决定「谁能拉自己的哪个设备」。
                // 放在采播冲突检测之前——许不许可这一层没过，就不必再谈设备此刻忙不忙。
                var access = PullAuthorization.Decide(session.Item.MachineId, device.DeviceID);
                if (access == PullAccess.Deny)
                {
                    Logger.Info($"拒绝来自 {session.Item.ClientIp}（{session.Item.PcName}）的拉取：本机把「{device.FriendlyName}」设为永不允许");
                    session.Item.Streaming = false;
                    session.Item.Note = "永不允许";
                    SendText(session, "/Reject/denied", true);
                    session.Running = false;
                    return;
                }
                if (access == PullAccess.Ask)
                {
                    var approval = PullApprovalCenter.Create(session.Item.MachineId, session.Item.PcName,
                        session.Item.ClientIp, device.DeviceID, device.FriendlyName);
                    session.Item.ApprovalPending = true;
                    session.Item.Note = "等待本机确认";
                    bool approved;
                    try
                    {
                        approved = WaitApproval(session, approval);
                    }
                    finally
                    {
                        session.Item.ApprovalPending = false;
                        PullApprovalCenter.Abandon(approval);
                    }
                    if (!approved)
                    {
                        var timedOut = !approval.Decided;
                        Logger.Info($"{(timedOut ? "确认超时" : "本机拒绝")}，断开来自 {session.Item.ClientIp} 的拉取：{device.FriendlyName}");
                        session.Item.Streaming = false;
                        session.Item.Note = timedOut ? "确认超时" : "已被本机拒绝";
                        SendText(session, timedOut ? "/Reject/timeout" : "/Reject/denied", true);
                        session.Running = false;
                        return;
                    }
                    session.Item.Note = "已确认";
                }

                // 采播冲突：本机要把这个设备采给别人，同时又在往这个设备播从别处拉来的音频。
                // 对方的声音会被本机重新采一遍再送回去，形成环路，这种点单直接拒。
                var probe = NetworkPlaybackProbe;
                if (probe != null && probe(device.DeviceID))
                {
                    Logger.Info($"拒绝来自 {session.Item.ClientIp} 的点单：本机正把网络音频播放到「{device.FriendlyName}」，采它会把声音绕回去");
                    session.Item.Streaming = false;
                    SendText(session, "/Reject/loop", true);
                    session.Running = false;
                    return;
                }

                session.Item.SourceDeviceID = segments.Length > 2 ? segments[2] : null;
                session.Item.DeviceName = device.FriendlyName;
                // 同一个设备被多路拉取时共用一份采集，不再各开各的。
                // 打开采集本身也会失败：设备被别的程序独占、或者驱动不支持共享模式采集
                // （有些虚拟声卡的多声道端点就是这样）。以前这个异常会一路抛到 ReadLoop，
                // 连接无声无息地断掉，对端只看到「连接被重置」，完全看不出是设备打不开。
                SharedCapture capture;
                try
                {
                    capture = AcquireCapture(device);
                }
                catch (Exception ex)
                {
                    var reason = DescribeCaptureFailure(ex);
                    Logger.Error($"打开采集失败，拒绝来自 {session.Item.ClientIp} 的点单：{device.FriendlyName}", ex);
                    session.Item.Streaming = false;
                    session.Item.Note = "设备打不开";
                    SendText(session, "/Reject/unavailable/" + Uri.EscapeDataString(reason), true);
                    session.Running = false;
                    return;
                }
                session.Capture = capture;
                session.Subscriber = capture.Subscribe((data, len) => SendAudio(session, data, len));
                SendWaveFormat(session, capture.Format);
                return;
            }

            // 开始送音频
            if (line.StartsWith("/Start"))
            {
                if (session.Capture != null)
                {
                    session.Capture.Start();
                    session.Item.Streaming = true;
                }
                return;
            }

            // 暂停：对方不想听了，结束这条会话
            if (line.StartsWith("/Pause"))
            {
                session.Running = false;
                return;
            }

            // 心跳
            if (line.StartsWith("/Ping"))
            {
                if (Environment.TickCount - session.LastSendAt > 10000)
                {
                    // 回一条空文本帧而不是往流里塞几个零字节：以前那几个零字节会跟音频挤在一起，
                    // 对端只能靠「这一包不超过 32 字节」去猜，猜错就把音频流切出一个错位。
                    SendText(session, "/Pong");
                }
                return;
            }

            Logger.Info($"收到无法识别的命令，已忽略：{line}");
        }

        /// <summary>
        /// 挂着等本机主人裁决。分小段轮询而不是一觉睡到超时：
        /// 对端等不下去先挂了、或者本机正在退出时，这里能立刻收手，不必白等满整个超时。
        /// </summary>
        private static bool WaitApproval(ClientSession session, PullApprovalRequest approval)
        {
            var deadline = Environment.TickCount + Math.Max(1, approval.TimeoutSeconds) * 1000;
            while (true)
            {
                if (PullApprovalCenter.Wait(approval, 1)) return true;
                if (approval.Decided) return false;
                if (!session.Running || !session.Client.Connected) return false;
                if (Environment.TickCount - deadline >= 0) return false;
            }
        }

        /// <summary>
        /// 把一条文本命令放进这条连接的出站队列。
        /// <paramref name="flush"/> 为真时等它真的写出去再返回——拒绝类应答之后马上就要关连接，
        /// 不等一下就关，对方只会看到「连接被重置」，看不到拒绝原因。
        /// </summary>
        private static void SendText(ClientSession session, string text, bool flush = false)
        {
            var queue = session.Out;
            if (queue == null) return;
            queue.Enqueue(AudioFraming.EncodeText(text));
            if (flush) queue.WaitDrained(1000);
        }

        /// <summary>
        /// 把「打不开采集」的异常翻成用户看得懂的一句话。
        /// CSCore 抛上来的原文是「IAudioClient::Initialize caused an error: 0x8889000A, "Unknown HRESULT"」这种，
        /// 对用户等于没说。这几个 HRESULT 是共享模式采集最常见的几种失败，翻成中文才有指导意义。
        /// </summary>
        private static string DescribeCaptureFailure(Exception ex)
        {
            var message = ex == null ? null : ex.Message;
            if (!string.IsNullOrEmpty(message))
            {
                if (message.IndexOf("0x8889000A", StringComparison.OrdinalIgnoreCase) >= 0)
                    return "这块设备正被别的程序独占使用";
                if (message.IndexOf("0x88890007", StringComparison.OrdinalIgnoreCase) >= 0)
                    return "这块设备不允许共享模式采集，只能被一个程序独占";
                if (message.IndexOf("0x88890008", StringComparison.OrdinalIgnoreCase) >= 0)
                    return "这块设备的格式不支持共享模式采集";
                if (message.IndexOf("0x88890004", StringComparison.OrdinalIgnoreCase) >= 0)
                    return "这块设备已经失效（被拔掉或者驱动重装了）";
                if (!string.IsNullOrWhiteSpace(message)) return message;
            }
            return "设备打不开，可能被别的程序独占，或者驱动不支持共享模式采集";
        }

        /// <summary>取这个设备的共享采集，没有就现开一份。返回的对象由最后退订的连接负责关掉。</summary>
        private SharedCapture AcquireCapture(MMDevice device)
        {
            lock (_clientsLock)
            {
                SharedCapture capture;
                if (_captures.TryGetValue(device.DeviceID, out capture) && !capture.Disposed)
                {
                    return capture;
                }
                capture = new SharedCapture(device.DeviceID, device.FriendlyName, device);
                _captures[device.DeviceID] = capture;
                return capture;
            }
        }

        /// <summary>退掉一条连接的订阅；这个设备再没人听了，就把它关掉并从池子里摘掉。</summary>
        private void ReleaseCapture(ClientSession session)
        {
            var capture = session.Capture;
            var subscriber = session.Subscriber;
            session.Capture = null;
            session.Subscriber = null;
            if (capture == null) return;
            capture.Unsubscribe(subscriber);
            if (capture.SubscriberCount > 0) return;
            lock (_clientsLock)
            {
                SharedCapture current;
                if (_captures.TryGetValue(capture.DeviceId, out current) && ReferenceEquals(current, capture))
                {
                    _captures.Remove(capture.DeviceId);
                }
            }
            capture.Dispose();
        }

        /// <summary>把一段采集数据送给某个拉取方。数据是共享采集广播过来的，所以这里只管这一条连接自己活着没有。</summary>
        private void SendAudio(ClientSession session, byte[] data, int length)
        {
            // 采集回调偶尔会给一次 0 字节。把它当成一帧发出去毫无意义，
            // 只会让对方多处理一条空消息（历史实现里还因此被误判成心跳）。
            if (length <= 0) return;
            if (!_isRunning || !session.Running || !session.Client.Connected)
            {
                return;
            }
            // 对端超过 20 秒没发过任何命令，认定它已经掉线
            if (Environment.TickCount - session.LastCommandAt > 20000)
            {
                Logger.Info("长时间无数据传输，连接关闭");
                session.Running = false;
                return;
            }
            try
            {
                session.LastSendAt = Environment.TickCount;
                // 不再直接对着 socket 写：原来那个 WriteAsync 没有 await，
                // 上一批还没写完下一批就又发起了，两批数据并发写同一条连接、顺序可能错开——
                // 音频是连续采样，顺序一乱就是杂音。交给专用的发送线程顺序写。
                var queue = session.Out;
                if (queue != null) queue.Enqueue(AudioFraming.Encode(FrameKind.Audio, data, 0, length));
            }
            catch (Exception ex)
            {
                Logger.Error("发送数据出错，连接关闭", ex);
                session.Running = false;
            }
        }

        /// <summary>把采集格式回给拉取方。顺序是采样率、位深、声道数、编码，对方按同样的顺序读回去。</summary>
        private static void SendWaveFormat(ClientSession session, WaveFormat format)
        {
            using (MemoryStream formatStream = new MemoryStream())
            using (BinaryWriter writer = new BinaryWriter(formatStream))
            {
                writer.Write(format.SampleRate);
                writer.Write(format.BitsPerSample);
                writer.Write(format.Channels);
                var extensibleFormat = format as WaveFormatExtensible;
                if (extensibleFormat != null)
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
                    writer.Write((int)format.WaveFormatTag);
                }
                var payload = formatStream.ToArray();
                var queue = session.Out;
                if (queue != null) queue.Enqueue(AudioFraming.Encode(FrameKind.WaveFormat, payload, 0, payload.Length));
            }
        }

        /// <summary>本机当前正在采集（也就是正被别人拉取）的设备号。界面拿它显示，采播冲突检测也用它。</summary>
        public List<string> ActiveCaptureDeviceIds()
        {
            lock (_clientsLock)
            {
                return _captures.Values.Select(a => a.DeviceId).ToList();
            }
        }

        private void CloseSession(ClientSession session)
        {
            // 先停发送线程再关流：反过来的话，发送线程正好在写一个已经关掉的流，
            // 会抛出没人接的异常。
            var queue = session.Out;
            session.Out = null;
            if (queue != null)
            {
                try { queue.Dispose(); } catch (Exception) { }
            }
            try
            {
                session.Stream.Dispose();
            }
            catch (Exception)
            {
            }
            try
            {
                session.Client.Dispose();
            }
            catch (Exception)
            {
            }
            ReleaseCapture(session);
            lock (_clientsLock)
            {
                _clients.Remove(session.Client);
                _sessions.Remove(session.Client);
            }
        }

        public void Stop()
        {
            if (!_isRunning)
            {
                Logger.Info("Server is not running.");
                return;
            }
            _isRunning = false;
            Logger.Info("Stopping server...");
            // Stop listening for new connections
            try
            {
                _listener?.Stop(); // Stop the listener gracefully
            }
            catch (Exception ex)
            {
                Logger.Error("停止监听出错", ex);
            }
            // Close all client connections
            lock (_clientsLock)
            {
                foreach (var session in _sessions.Values)
                {
                    session.Running = false;
                    var queue = session.Out;
                    session.Out = null;
                    if (queue != null)
                    {
                        try { queue.Dispose(); } catch (Exception) { }
                    }
                    try
                    {
                        session.Client.Close();
                    }
                    catch (Exception ex)
                    {
                        Logger.Info($"Error closing client connection: {ex.Message}");
                    }
                }
                _clients.Clear();
                // 采集现在是多路共享的，逐个会话去停会互相踩，统一在这里关掉
                foreach (var capture in _captures.Values)
                {
                    try
                    {
                        capture.Dispose();
                    }
                    catch (Exception ex)
                    {
                        Logger.Info($"Error disposing capture: {ex.Message}");
                    }
                }
                _captures.Clear();
            }
            Logger.Info("Server stopped.");
        }

        /// <summary>
        /// 当前正在拉取本机音频的连接，界面直接展示这个列表。
        /// 顺带把每条连接的发送统计抄进展示项——队列有没有在丢帧，是本机这一侧
        /// 唯一能自己回答的问题，别让用户只能靠猜。
        /// </summary>
        public List<ClientItem> ClientItems()
        {
            lock (_clientsLock)
            {
                var items = new List<ClientItem>();
                foreach (var session in _sessions.Values)
                {
                    var item = session.Item;
                    var queue = session.Out;
                    if (item != null && queue != null)
                    {
                        item.SentAudioBytes = queue.SentAudioBytes;
                        item.DroppedBytes = queue.DroppedBytes;
                        // 折算成毫秒：用户看「丢了多少毫秒声音」比看字节数有概念
                        var capture = session.Capture;
                        var bytesPerSecond = (capture != null && capture.Format != null)
                            ? capture.Format.BytesPerSecond
                            : 0;
                        item.DroppedMs = bytesPerSecond > 0
                            ? (int)(queue.DroppedBytes * 1000L / bytesPerSecond)
                            : 0;
                    }
                    items.Add(item);
                }
                return items;
            }
        }

        public void Dispose()
        {
            Stop();
        }

        /// <summary>一条拉取连接的全部状态，替代原先散在方法外的几个局部变量。</summary>
        private class ClientSession
        {
            public TcpClient Client;
            public NetworkStream Stream;
            /// <summary>这条连接的出站队列与发送线程，断开时置空。</summary>
            public OutgoingQueue Out;
            public ClientItem Item;
            /// <summary>这条连接的共享采集，可能为空（还没点单就被断开）。</summary>
            public SharedCapture Capture;
            /// <summary>本连接在这份共享采集上的订阅，连接收尾时退掉。</summary>
            public SharedCapture.Subscriber Subscriber;
            /// <summary>置 false 后读循环会退出并清理。</summary>
            public volatile bool Running = true;
            /// <summary>最近一次收到对端命令的时间，用来判断对端是否还活着。</summary>
            public int LastCommandAt = Environment.TickCount;
            /// <summary>最近一次向对端写出数据的时间，心跳应答用得上。</summary>
            public int LastSendAt = Environment.TickCount;
        }

        /// <summary>
        /// 一条连接的出站队列，配一个专用发送线程。
        ///
        /// 为什么不让采集线程直接往 socket 写：写 socket 在对方收不动时会阻塞，
        /// 而调用点正是声卡的采集回调——把它堵住就是丢音频。所以这里解耦：
        /// 采集线程只管入队（很快），发送线程顺序写出去。
        ///
        /// 顺序也是靠这个保证的：同一时刻只有这一个线程在写这条连接。
        /// 原来那个没有 await 的 WriteAsync 会在上一批没写完时又发起下一批，
        /// 两批数据并发写同一条连接、顺序可能错开，音频听起来就是杂音。
        /// </summary>
        private class OutgoingQueue : IDisposable
        {
            private readonly NetworkStream stream;
            private readonly Queue<byte[]> queue = new Queue<byte[]>();
            private readonly object sync = new object();
            private readonly AutoResetEvent signal = new AutoResetEvent(false);
            private readonly Thread worker;
            private readonly int maxQueuedBytes;

            private int queuedBytes;
            private bool disposed;
            private volatile bool running = true;

            /// <summary>因为队列积压被丢掉的音频帧数与字节数。</summary>
            public long DroppedFrames;
            public long DroppedBytes;

            /// <summary>已经写出去的帧数与音频字节数。</summary>
            public long SentFrames;
            public long SentAudioBytes;

            public OutgoingQueue(NetworkStream stream, int maxQueuedBytes)
            {
                this.stream = stream;
                this.maxQueuedBytes = maxQueuedBytes;
                worker = new Thread(Loop);
                worker.IsBackground = true;
                worker.Name = "AudioStream 发送";
                worker.Start();
            }

            /// <summary>
            /// 入队一帧。音频帧在队列积压到上限时丢最旧的——宁可少放一小段，
            /// 也不要让延迟无限涨上去。控制帧（文本、格式）不丢：丢了对方就不知道发生了什么。
            /// </summary>
            public void Enqueue(byte[] frame)
            {
                if (frame == null || !running) return;
                var isAudio = frame.Length > AudioFraming.HeaderSize && frame[AudioFraming.HeaderSize - 1] == (byte)FrameKind.Audio;
                lock (sync)
                {
                    if (disposed) return;
                    if (isAudio)
                    {
                        while (queue.Count > 0 && queuedBytes + frame.Length > maxQueuedBytes)
                        {
                            var oldest = queue.Peek();
                            if (oldest.Length > AudioFraming.HeaderSize && oldest[AudioFraming.HeaderSize - 1] != (byte)FrameKind.Audio)
                            {
                                // 队首是控制帧，它必须发出去，不能为了腾地方把它丢掉
                                break;
                            }
                            queue.Dequeue();
                            queuedBytes -= oldest.Length;
                            DroppedFrames++;
                            DroppedBytes += Math.Max(0, oldest.Length - AudioFraming.HeaderSize);
                        }
                        if (queuedBytes + frame.Length > maxQueuedBytes)
                        {
                            DroppedFrames++;
                            DroppedBytes += Math.Max(0, frame.Length - AudioFraming.HeaderSize);
                            return;
                        }
                    }
                    queue.Enqueue(frame);
                    queuedBytes += frame.Length;
                }
                signal.Set();
            }

            private void Loop()
            {
                while (running)
                {
                    byte[] frame = null;
                    lock (sync)
                    {
                        if (queue.Count > 0)
                        {
                            frame = queue.Dequeue();
                            queuedBytes -= frame.Length;
                        }
                    }
                    if (frame == null)
                    {
                        signal.WaitOne(100);
                        continue;
                    }
                    try
                    {
                        stream.Write(frame, 0, frame.Length);
                        SentFrames++;
                        if (frame.Length > AudioFraming.HeaderSize && frame[AudioFraming.HeaderSize - 1] == (byte)FrameKind.Audio)
                        {
                            SentAudioBytes += frame.Length - AudioFraming.HeaderSize;
                        }
                    }
                    catch (Exception)
                    {
                        // 连接废了。读循环那边很快也会发现并收尾。
                        running = false;
                    }
                }
            }

            /// <summary>等队列里的东西都写出去。拒绝类应答要在关连接之前等它真的发出去。</summary>
            public bool WaitDrained(int timeoutMs)
            {
                var deadline = Environment.TickCount + timeoutMs;
                while (Environment.TickCount - deadline < 0)
                {
                    lock (sync) { if (queue.Count == 0) return true; }
                    Thread.Sleep(2);
                }
                lock (sync) { return queue.Count == 0; }
            }

            public void Dispose()
            {
                running = false;
                signal.Set();
                var workerThread = worker;
                if (workerThread != null && workerThread.IsAlive && workerThread != Thread.CurrentThread)
                {
                    try { workerThread.Join(500); } catch (Exception) { }
                }
                lock (sync)
                {
                    if (disposed) return;
                    disposed = true;
                    queue.Clear();
                    queuedBytes = 0;
                }
                try { signal.Dispose(); } catch (Exception) { }
            }
        }
    }
}
