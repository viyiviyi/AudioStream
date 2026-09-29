using AudioStream.AudioServer.Model;
using Common;
using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundOut;
using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
namespace AudioStream.AudioServer
{
    internal class NetAudioRedirector : IPlayerRedirector
    {
        /// <summary>单个端口的连接超时。同一个网段里通就是几毫秒，不通就是不通，没必要按系统默认的二十来秒干等。</summary>
        private const int ConnectTimeoutMs = 400;

        /// <summary>对端监听的端口区间，与 TcpServer 的顺延规则一致。</summary>
        private const int PortStart = 12670;
        private const int PortEnd = 12690;

        private string _address;
        private PlaybackGroup group;
        private Socket clientSocket;
        /// <summary>这一路连接的读取流。握手、格式协商、音频都从它按帧读。</summary>
        private NetworkStream readStream;
        /// <summary>按帧读的读取器，与 readStream 同一个生命周期。</summary>
        private FrameReader frameReader;
        private AudioBroadcastBuffer audioBuffer;
        private WaveFormat waveFormat;
        private int maxDelaySize = 0;

        /// <summary>
        /// 环形缓冲容量（毫秒）。它是能容纳多少抖动的上限，不是目标水位——
        /// 目标水位是 <see cref="TargetBufferMs"/>，两者不是一回事。
        /// </summary>
        private const int BufferCapacityMs = 120;

        /// <summary>
        /// 抖动缓冲的目标水位（毫秒）。端到端延迟主要由它决定：
        /// 采集 10 + 水位 20 + 声卡 10，约 40ms 量级，内网足够稳，听感上也不明显。
        /// 比原来那 20ms 容量 / 1ms 声卡的组合更保守，但原来那套的代价是周期性欠载爆音。
        /// 实测（本机流转 60 秒）稳定在零欠载、零溢出。
        /// </summary>
        private const int TargetBufferMs = 35;
        private Timer timer;
        private float _Volume = 1;
        private bool isRun = false;
        private string _remoteMachineId;
        private string _remotePcName;
        /// <summary>这一路是不是真的建起来了。对端连不上、握手被拒、播放器起不来时都保持 false。</summary>
        private volatile bool isActive;

        /// <summary>对端身份，握手时拿到；界面靠它显示这条流来自哪台机器。</summary>
        public string RemoteMachineId { get { return _remoteMachineId; } }

        /// <summary>对端计算机名，只用于显示。</summary>
        public string RemotePcName { get { return _remotePcName; } }

        /// <summary>
        /// 这一路是不是成功建起来了（连上、握手过了、播放设备也开出来了）。
        /// </summary>
        public bool IsActive { get { return isActive; } }

        /// <summary>
        /// 此刻是不是真的有声音在往外放。
        /// 被拉取侧靠它判断「本机到底有没有在播别人的声音」——刚连上还没收到数据时不算，
        /// 对方离线时更不算，否则用户会莫名其妙点不了单。
        /// </summary>
        public bool AudioFlowing { get { return isActive && group != null && group.IsActive; } }

        /// <summary>此刻真的在出声的设备名。一路喂多块声卡时可能有好几个。</summary>
        public List<string> ActiveDeviceNames
        {
            get { return group == null ? new List<string>() : group.ActiveDeviceNames; }
        }

        /// <summary>这一路的音质指标：缓冲水位、欠载次数、时钟校正量。</summary>
        public PlaybackQuality Quality
        {
            get { return group == null ? null : group.Quality; }
        }

        public float Volume
        {
            get { return _Volume; }
            set
            {
                _Volume = value;
                if (group != null) group.Volume = value;
            }
        }
        public NetAudioRedirector(IList<PlaybackTargetSpec> targets, string address, string sourceDeviceID = null, float Volume = 1, string sourceDeviceName = null)
        {
            _Volume = Volume;
            _address = address;
            isRun = true;
            try
            {
                // 连接到远程设备
                Connect();
                // 从这里开始所有收发都按帧走
                readStream = new NetworkStream(clientSocket);
                frameReader = new FrameReader(readStream);
                // 报上本机身份并核对对端：这一步会把「自己拉自己」挡在门外
                Handshake();
                // 获取远程设备的音频编码
                waveFormat = GetWaveFormatExtensible(sourceDeviceID, sourceDeviceName);

                // 容量按毫秒算，写成 double 再取整：整除会截断，容量会偏小
                maxDelaySize = Math.Max(waveFormat.BlockAlign * 8,
                    (int)(waveFormat.BytesPerSecond / 1000.0 * BufferCapacityMs));
                audioBuffer = new AudioBroadcastBuffer(maxDelaySize);
                clientSocket.NoDelay = true; // 禁用Nagle算法，降低小包/控制包延迟
                clientSocket.ReceiveBufferSize = 1024 * 1024;
                clientSocket.SendBufferSize = 128 * 1024; // 发送缓冲足够，避免TCP背压
                SendText("/Start"); // 告诉对端可以开始送音频了
                // 一块来源喂本机的多块声卡：数据只拉这一条连接，分发在本地做
                try
                {
                    group = new PlaybackGroup(audioBuffer, waveFormat, targets, _Volume,
                        PlaybackTuning.CreateForNetwork(TargetBufferMs));
                }
                catch (Exception e)
                {
                    // 网络这一层是通的（连上了、握手过了、格式也拿到了），只是这块声卡开不出来。
                    // 单独立个类型，好让主备策略换下一块设备再试，而不是把整条拉取判死。
                    throw new PlaybackDeviceException(e.Message, e);
                }
                Console.WriteLine("WaveFormat: " + waveFormat.ToString());
                // 走到这里才算是真的起来了：连上了、握手过了、播放设备也开出来了
                isActive = true;
                timer = new Timer((t) =>
                {
                    var socket = clientSocket;
                    if (socket == null) return;
                    try
                    {
                        SendText("/Ping");
                    }
                    catch (Exception)
                    {
                        // 心跳发不出去说明连接已经没了。读循环马上也会发现并退出，
                        // 这里再刷一条错误日志只会把日志淹掉，什么都不用做。
                    }
                }, null, 1000, 10 * 1000);

                Task.Run(() =>
                {
                    // 把这两个抓成局部变量：Cleanup 会把字段置空，循环里再去读字段就会撞上空引用
                    var reader = frameReader;
                    var playback = group;
                    var buffer = audioBuffer;
                    try
                    {
                        while (isRun)
                        {
                            FrameKind kind;
                            int count;
                            if (!reader.ReadNext(out kind, out count)) break;

                            if (kind == FrameKind.Text)
                            {
                                // /Pong 之类的应答，收到就说明连接还活着，内容不必处理。
                                // 以前这里靠「这一包不超过 32 字节」来认它，猜错的代价是吃掉音频字节。
                                continue;
                            }
                            if (kind != FrameKind.Audio || count <= 0) continue;

                            buffer.Write(reader.Buffer, 0, count);
                            // 各块声卡自己看游标里有没有东西可放，谁也不等谁
                            playback.Pump();
                        }
                    }
                    catch (Exception)
                    {
                        // 连接断了或流坏了。退出循环，让这一路不再是「正在播」，上层会安排重连。
                    }
                    isActive = false;
                });
            }
            catch (Exception ex)
            {
                // 中途失败也要把已经建起来的资源收干净：这一路会被反复重连，
                // 每次漏一个 socket 或声卡句柄，跑一晚上就能攒出几千个。
                isRun = false;
                Cleanup();
                Logger.Error($"连接出错 {ex.Message}\n{ex.StackTrace}", ex);
                throw;
            }
        }

        public void SetDevice(MMDevice outputDevice)
        {
            if (group == null) return;
            group.SetDevice(outputDevice);
        }

        /// <summary>
        /// 连上对端的音频端口。端口区间逐个试，每个端口的等待有上限。
        /// 试完都连不上就抛错——上层会按「网络类失败」安排重连，界面也能说出到底连的是谁。
        /// </summary>
        private void Connect()
        {
            for (int port = PortStart; port < PortEnd; port++)
            {
                bool timedOut;
                var socket = TryConnect(_address, port, ConnectTimeoutMs, out timedOut);
                if (socket != null)
                {
                    clientSocket = socket;
                    return;
                }
                // 连一个「端口没开」的 RST 都没等到，说明对端主机整体不可达（关机、断网、被防火墙丢弃）。
                // 这种情况下把剩下十几个端口挨个等一遍，只是把一次重连拖成八秒，毫无意义。
                if (timedOut) break;
            }
            throw new InvalidOperationException(
                "连不上 " + _address + " 的 " + PortStart + "-" + (PortEnd - 1) + " 端口，对方可能没开机或没运行本程序。");
        }

        /// <summary>
        /// 一次带超时的连接尝试。超时或出错都返回 null，让调用方接着试下一个端口。
        /// 每次都用新 socket：失败过的 socket 状态不可靠，复用只会带来更难查的怪问题。
        /// <paramref name="timedOut"/> 用来区分「对端明确拒绝」和「对端连回音都没有」，
        /// 前者的下一句是换端口接着试，后者则应该立刻放弃这一轮。
        /// </summary>
        private static Socket TryConnect(string address, int port, int timeoutMs, out bool timedOut)
        {
            timedOut = false;
            Socket socket = null;
            try
            {
                socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
                var task = socket.ConnectAsync(address, port);
                if (!task.Wait(timeoutMs))
                {
                    // 超时后那个 task 迟早还会带着异常结束，先派人把异常收掉，免得变成未观察异常
                    task.ContinueWith(t => { var ignored = t.Exception; }, TaskContinuationOptions.OnlyOnFaulted);
                    socket.Dispose();
                    timedOut = true;
                    return null;
                }
                return socket;
            }
            catch (Exception)
            {
                if (socket != null)
                {
                    try { socket.Dispose(); } catch (Exception) { }
                }
                return null;
            }
        }

        /// <summary>
        /// 握手：把自己的身份报给对端，并收下对端的身份。
        /// 对端若回绝（比如发现这是它自己），这里直接抛错，让上层把这条拉取记为失败，
        /// 而不是带着一条注定成环的连接继续往下走。
        /// </summary>
        private void Handshake()
        {
            clientSocket.ReceiveTimeout = 5000;
            clientSocket.SendTimeout = 5000;
            SendText("/Hello/" + MachineIdentity.Id + "/" + Uri.EscapeDataString(MachineIdentity.Name ?? ""));
            var reply = ReadTextFrame();
            if (reply.StartsWith("/Reject/"))
            {
                throw new InvalidOperationException(RejectText(reply));
            }
            if (reply.StartsWith("/Hello/"))
            {
                var parts = reply.Split(new[] { '/' }, 4);
                _remoteMachineId = parts.Length > 2 ? parts[2] : null;
                _remotePcName = parts.Length > 3 ? parts[3] : null;
                if (!string.IsNullOrEmpty(_remotePcName))
                {
                    try { _remotePcName = Uri.UnescapeDataString(_remotePcName); }
                    catch (Exception) { /* 名字只用于显示，解不出来就原样留着 */ }
                }
            }
            else if (!string.IsNullOrEmpty(reply))
            {
                Logger.Info("对端的握手应答无法识别，按继续处理：" + reply);
            }
        }

        /// <summary>
        /// 把对端的拒绝原因翻译成用户能照着处理的话。
        /// 拒绝是「此刻不行」而不是「永远不行」，所以要说清是什么占住了、怎么办。
        /// </summary>
        private static string RejectText(string reply)
        {
            if (reply.StartsWith("/Reject/self"))
            {
                return "对端认出这是它自己。如果填的是本机地址，请把 IP 留空，改成本机设备之间流转。";
            }
            if (reply.StartsWith("/Reject/loop"))
            {
                return "对端拒绝：那个设备上此刻正播着从别处拉来的网络音频，再采一遍会绕成回路。等它播完或换个设备再试。";
            }
            if (reply.StartsWith("/Reject/nodevice"))
            {
                // 对端会把它那边的真实原因捎回来（第 4 段，转义过），带上它比一句笼统的拒绝有用得多。
                var reason = RejectReason(reply);
                if (!string.IsNullOrEmpty(reason))
                {
                    return "对端拒绝：" + reason + "。请让对方确认那个默认设备在，或者改选一个具体设备。";
                }
                return "对端拒绝：对端此刻没有可用的默认设备。请让对方确认那个默认设备在，或者改选一个具体设备。";
            }
            if (reply.StartsWith("/Reject/unavailable"))
            {
                // 设备找到了、也放行了，但这块设备此刻打不开。原因同样由对端捎回来。
                var reason = RejectReason(reply);
                if (!string.IsNullOrEmpty(reason))
                {
                    return "对端拒绝：" + reason + "。请让对方换一块设备，或者关掉占用它的程序。";
                }
                return "对端拒绝：那块设备此刻打不开，可能被别的程序独占，或者驱动不支持共享模式采集。";
            }
            if (reply.StartsWith("/Reject/denied"))
            {
                return "对端拒绝：本机没有被允许拉取那个设备。请让对方在它的配置页「拉取授权」里放行，或者换个设备。";
            }
            if (reply.StartsWith("/Reject/timeout"))
            {
                return "对端把这次拉取挂起来等人确认，但一直没人确认，等超时了。请让对方在它机器上点一下批准，然后再试。";
            }
            return "对端拒绝了这条拉取（" + reply + "）。";
        }

        /// <summary>从 /Reject/xxx/原因 这种应答里取回对端写的具体原因，没带就返回空串。</summary>
        private static string RejectReason(string reply)
        {
            var segments = reply.Split(new[] { '/' }, 4);
            if (segments.Length < 4) return string.Empty;
            try { return Uri.UnescapeDataString(segments[3]); }
            catch (Exception) { return segments[3]; }
        }

        /// <summary>发一条文本命令。连接已经建好，直接同步写一帧即可——这条连接上只有心跳和握手会写，没有并发。</summary>
        private void SendText(string text)
        {
            var frame = AudioFraming.EncodeText(text);
            clientSocket.Send(frame, 0, frame.Length, SocketFlags.None);
        }

        /// <summary>
        /// 读一条文本帧。读不到文本（对方断了、或者回的是别的类型）就返回空串，
        /// 由调用方按自己的语义处理。
        /// </summary>
        private string ReadTextFrame()
        {
            FrameKind kind;
            int count;
            if (!frameReader.ReadNext(out kind, out count)) return string.Empty;
            if (kind != FrameKind.Text) return string.Empty;
            return frameReader.ReadText(count);
        }

        private WaveFormat GetWaveFormatExtensible(string sourceDeviceID = null, string sourceDeviceName = null)
        {
            SendText("/WaveFormat/" + (sourceDeviceID ?? "0") + "/" + (sourceDeviceName ?? "0"));
            FrameKind kind;
            int count;
            if (!frameReader.ReadNext(out kind, out count))
            {
                throw new InvalidOperationException("对端还没给出音频格式就把连接断开了。");
            }
            // 点单可能被回绝：这时对端回的是文本而不是格式。不认出来就会把一串字符
            // 当成采样率去解析，最后拿着一个垃圾格式硬播，问题现场完全看不出原因。
            if (kind == FrameKind.Text)
            {
                var text = frameReader.ReadText(count).Trim();
                if (text.StartsWith("/Reject/")) throw new InvalidOperationException(RejectText(text));
                throw new InvalidOperationException("对端没有给出音频格式，回的是：" + text);
            }
            if (kind != FrameKind.WaveFormat || count <= 0)
            {
                throw new InvalidOperationException("对端回的帧类型不对：期望音频格式，收到 " + kind);
            }
            var waveFormatBytes = frameReader.Buffer;
            if (count > 36)
            {
                using (MemoryStream stream = new MemoryStream(waveFormatBytes, 0, count))
                using (BinaryReader reader = new BinaryReader(stream))
                {
                    // 对端写入顺序是 采样率、位深、声道数、编码，这里按同样的顺序读回来
                    WaveFormatExtensible waveFormat = new WaveFormatExtensible(
                        reader.ReadInt32(),          // 采样率
                        reader.ReadInt32(),          // 位深
                        reader.ReadInt32(),          // 声道数
                        Guid.Parse(Encoding.ASCII.GetString(reader.ReadBytes(36)))
                    );
                    return waveFormat;
                }
            }
            else if (count > 12)
            {
                using (MemoryStream stream = new MemoryStream(waveFormatBytes, 0, count))
                using (BinaryReader reader = new BinaryReader(stream))
                {
                    WaveFormat waveFormat = new WaveFormat(
                        reader.ReadInt32(),          // 采样率
                        reader.ReadInt32(),          // 位深
                        reader.ReadInt32(),          // 声道数
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
            // 主动收工时告诉对端一声，让它那边早点把采集放掉；失败清理时则不必，连接本来就是坏的。
            try
            {
                var socket = clientSocket;
                if (socket != null) SendText("/Pause");
            }
            catch (Exception)
            {
            }
            Cleanup();
        }

        /// <summary>
        /// 释放这一路占着的运行时资源，可重复调用。
        /// 成功建起来的、建到一半失败的，都从这里走同一条收尾路径，免得两处逻辑慢慢长歪。
        /// </summary>
        private void Cleanup()
        {
            isActive = false;

            var t = timer;
            timer = null;
            if (t != null)
            {
                try { t.Dispose(); } catch (Exception) { }
            }

            var socket = clientSocket;
            clientSocket = null;
            if (socket != null)
            {
                try { socket.Dispose(); } catch (Exception) { }
            }

            // 读取流与读取器一起放掉：读线程此刻可能正卡在 ReadNext 上，
            // 关掉流会让它立刻以异常收场，不会一直吊着。
            var stream = readStream;
            readStream = null;
            frameReader = null;
            if (stream != null)
            {
                try { stream.Dispose(); } catch (Exception) { }
            }

            var playback = group;
            group = null;
            if (playback != null)
            {
                try { playback.Dispose(); } catch (Exception) { }
            }

            var buffer = audioBuffer;
            audioBuffer = null;
            if (buffer != null)
            {
                try { buffer.Dispose(); } catch (Exception) { }
            }
        }
    }
}
