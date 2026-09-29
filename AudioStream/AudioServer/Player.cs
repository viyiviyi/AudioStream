using Common;
using Common.Helper;
using CSCore.CoreAudioAPI;
using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace AudioStream.AudioServer
{
    public class Player : IDisposable
    {
        /// <summary>看门狗的检查间隔。断了以后最多一秒就被发现，用户基本听不出停顿。</summary>
        private const int WatchdogIntervalMs = 1000;

        /// <summary>重连间隔的上限。对端关机一整晚，也只会在日志里留下稀疏的几条。</summary>
        private const int MaxBackoffSeconds = 5;

        private readonly PlayerInfo playerInfo;
        private readonly Timer watchdog;
        private readonly object startLock = new object();
        private IPlayerRedirector audioRedirector;
        private MMDeviceEnumerator deviceEnumerator;
        private volatile bool disposed;
        private volatile bool starting;
        private bool defaultHooked;

        /// <summary>
        /// 主备策略下上一次真正用起来的那块播放设备（配置里写的设备号）。
        /// 重连时从它开始试，它恢复了就继续用它，别让声卡抖一下就把声音换到另一个房间去。
        /// </summary>
        private string failoverTargetId;

        /// <summary>连续失败次数。连上了就清零，重连间隔靠它退避。</summary>
        private int attempts;

        /// <summary>下次允许重连的时刻。没连上时，看门狗在这之前只会空转。</summary>
        private DateTime nextRetryUtc = DateTime.MinValue;

        public Guid ID { get => playerInfo.ID; }

        /// <summary>
        /// 这一路此刻正往哪些本机设备上放（放好了的设备号）。
        /// 一条拉取可以有多个目标，所以是一份列表；采播冲突判定与状态上报都用它。
        /// </summary>
        public List<string> ResolvedTargetDeviceIds { get; private set; }

        /// <summary>这一路播的是不是从别的机器拉来的音频（而不是本机设备之间的流转）。</summary>
        public bool IsRemote
        {
            get { return !string.IsNullOrWhiteSpace(playerInfo.IP); }
        }

        /// <summary>
        /// 这一路是不是真的有从别的机器拉来的声音在响。
        /// 光是「配了这么一条」不算，连上了但还没收到数据也不算——对端没在出声时它并没有在放什么，
        /// 不能拿它当环路依据，否则用户会莫名点不了单。
        /// </summary>
        public bool IsPlayingRemoteAudio
        {
            get { return IsRemote && audioRedirector != null && audioRedirector.AudioFlowing; }
        }

        /// <summary>这一路此刻是否真的在出声。</summary>
        public bool IsPlaying
        {
            get { return audioRedirector != null && audioRedirector.IsActive; }
        }

        /// <summary>
        /// 这一路此刻所处的阶段，给界面用。
        /// 只报「在播/不在播」不够：用户需要区分「正在连」「连不上」「配置本身就不对」。
        /// </summary>
        public string State
        {
            get
            {
                if (disposed) return "stopped";
                if (IsPlaying) return "playing";
                if (starting) return "connecting";
                if (!string.IsNullOrEmpty(LastError)) return "error";
                return "idle";
            }
        }

        /// <summary>没播起来的原因，能播就是空字符串。界面靠它把「配了但没响」说清楚。</summary>
        public string LastError { get; private set; }

        /// <summary>对端的计算机名或身份，握手时才有。</summary>
        public string PeerName { get; private set; }

        /// <summary>连续失败了多少次。界面拿它说明「还在自动重试，不用管」。</summary>
        public int Attempts { get { return attempts; } }

        public Player(PlayerInfo info)
        {
            deviceEnumerator = new MMDeviceEnumerator();
            playerInfo = info;
            LastError = string.Empty;
            PeerName = string.Empty;
            ResolvedTargetDeviceIds = new List<string>();
            watchdog = new Timer(OnWatchdog, null, WatchdogIntervalMs, WatchdogIntervalMs);
        }

        public void OnDefaultChange(Object obj, DefaultDeviceChangedEventArgs args)
        {
            var defaultDeviceId = args.DeviceId;
            if (!string.IsNullOrWhiteSpace(defaultDeviceId))
            {
                var device = AudioDeviceHelper.GetDeviceById(defaultDeviceId);
                if (device != null)
                {
                    if (audioRedirector != null) audioRedirector.SetDevice(device);
                }
            }
        }

        /// <summary>开工。已经开过或已经收工就什么都不做。</summary>
        public void Start()
        {
            if (!TryBeginStart()) return;
            Task.Run(() => StartCore());
        }

        /// <summary>
        /// 占住「正在启动」这个位。看门狗靠它避开正在进行的这一轮，
        /// 否则重连和启动会同时去建同一条连接，声卡上就会出现两份播放。
        /// </summary>
        private bool TryBeginStart()
        {
            lock (startLock)
            {
                if (disposed || starting) return false;
                starting = true;
                return true;
            }
        }

        public void StartCore()
        {
            try
            {
                playerInfo.Play = true;
                // 先当作「这一轮还没失败」。下面两条启动路径谁失败谁负责写原因，
                // 结尾就靠它是不是空的来判断这一路到底起没起来。
                LastError = string.Empty;

                // 上一路不管是断了还是卡住了，先收掉再重来。重连走的也是这条路。
                StopRedirector();

                var remote = Tools.IsPrivateIPAddress(playerInfo.IP);

                // 本机流转才需要来源设备；从别的机器拉的时候，来源设备在对面那台机器上。
                MMDevice sourceDevice = null;
                string sourceDeviceId = null;
                if (!remote)
                {
                    string sourceError;
                    sourceDeviceId = ResolveSourceDeviceId(out sourceError);
                    if (string.IsNullOrEmpty(sourceDeviceId))
                    {
                        // 解析不出来的原因可能是「没指定」，也可能是「本机此刻没有默认输入设备」，
                        // 两种都得说清楚，不然用户只会看到一个不知道为什么失败的记录。
                        LastError = sourceError;
                        return;
                    }
                    sourceDevice = AudioDeviceHelper.GetDeviceById(sourceDeviceId);
                    if (sourceDevice == null)
                    {
                        sourceDevice = AudioDeviceHelper.GetDeviceByName(playerInfo.SourceDeviceName);
                    }
                    if (sourceDevice == null)
                    {
                        LastError = "本机找不到来源设备：" + playerInfo.SourceDeviceName;
                        return;
                    }
                    sourceDeviceId = sourceDevice.DeviceID;
                }

                // 策略决定怎么挑设备：全播是把所有目标一次交出去，主备是从高到低挑第一块能打开的。
                if (playerInfo.Policy == PlaybackPolicy.Failover)
                {
                    StartFailover(remote, sourceDevice, sourceDeviceId);
                }
                else
                {
                    StartAll(remote, sourceDevice, sourceDeviceId);
                }
                // 上面两条路各自把失败原因写进 LastError，没写就说明这一路真起来了
                if (!string.IsNullOrEmpty(LastError)) return;

                // 接上了就立刻把重试计数清零。等下一次看门狗巡检再清，界面会有一秒钟
                // 同时显示「正在播」和「已重试 1 次」，看着像自相矛盾。
                attempts = 0;
                nextRetryUtc = DateTime.MinValue;
            }
            catch (Exception e)
            {
                LastError = e.Message;
                Logger.Error("启动一路播放失败", e);
            }
            finally
            {
                lock (startLock) { starting = false; }
            }
        }

        /// <summary>
        /// 全播：把配置里的目标设备一次全交出去，能开几块就开几块。
        /// 某一块打不开不该拖累别的（这是 PlaybackGroup 的职责），全开不出来才算这一路失败。
        /// </summary>
        private void StartAll(bool remote, MMDevice sourceDevice, string sourceDeviceId)
        {
            var specs = new List<PlaybackTargetSpec>();
            var problems = new List<string>();
            var needsDefaultHook = false;
            var targets = playerInfo.Targets;
            if (targets != null)
            {
                for (int i = 0; i < targets.Count; i++)
                {
                    var target = targets[i];
                    bool followsDefault;
                    var spec = ResolveTarget(target, out followsDefault);
                    if (spec == null)
                    {
                        problems.Add(TargetText(target) + "：本机找不到这块设备");
                        continue;
                    }
                    // 本机流转时把声音放回它自己的来源设备，等于自己喂自己，跳过
                    if (!remote && SameDevice(spec.DeviceId, sourceDeviceId))
                    {
                        problems.Add(TargetText(target) + "：它就是来源设备");
                        continue;
                    }
                    if (followsDefault) needsDefaultHook = true;
                    specs.Add(spec);
                }
            }
            if (specs.Count == 0)
            {
                LastError = problems.Count > 0
                    ? "没有可用的播放设备：" + string.Join("、", problems.ToArray())
                    : "没有指定播放设备";
                return;
            }
            StartWith(specs, remote, sourceDevice, needsDefaultHook);
        }

        /// <summary>
        /// 主备：按优先级从高到低挑第一块能打开的声卡，这块打不开了就轮到下一块顶上。
        ///
        /// 起点是上一次真正用起来的那一块：它只是短暂被抢（比如别的程序临时独占）时，
        /// 恢复后还会继续用它，不会因为一次抖动就把声音挪到另一个房间去。
        /// </summary>
        private void StartFailover(bool remote, MMDevice sourceDevice, string sourceDeviceId)
        {
            var problems = new List<string>();
            var candidates = OrderedFailoverTargets();
            for (int i = 0; i < candidates.Count; i++)
            {
                var target = candidates[i];
                bool followsDefault;
                var spec = ResolveTarget(target, out followsDefault);
                if (spec == null)
                {
                    problems.Add(TargetText(target) + "：本机找不到这块设备");
                    continue;
                }
                if (!remote && SameDevice(spec.DeviceId, sourceDeviceId))
                {
                    problems.Add(TargetText(target) + "：它就是来源设备");
                    continue;
                }

                var one = new List<PlaybackTargetSpec>();
                one.Add(spec);
                try
                {
                    StartWith(one, remote, sourceDevice, followsDefault);
                    failoverTargetId = target.DeviceID;
                    return;
                }
                catch (PlaybackDeviceException e)
                {
                    // 只是这块声卡开不出来，换下一块接着试。网络类的失败不走这条路，
                    // 会直接冒泡出去把这一路记为失败——换多少块声卡也救不回一个连不上的对端。
                    StopRedirector();
                    problems.Add(TargetText(target) + "：" + e.Message);
                }
            }
            LastError = problems.Count > 0
                ? "没有可用的播放设备：" + string.Join("、", problems.ToArray())
                : "没有指定播放设备";
        }

        /// <summary>
        /// 主备策略下的候选顺序：按优先级升序，但把上次用起来的那一块提到最前面。
        /// 它不在配置里了（用户改过目标）或本来就是第一块时，直接按优先级来。
        /// </summary>
        private List<PlayTarget> OrderedFailoverTargets()
        {
            var all = playerInfo.Targets;
            if (all == null || all.Count == 0) return new List<PlayTarget>();
            var ordered = new List<PlayTarget>(all);
            ordered.Sort(delegate (PlayTarget a, PlayTarget b) { return a.Priority.CompareTo(b.Priority); });
            if (string.IsNullOrEmpty(failoverTargetId)) return ordered;

            var start = -1;
            for (int i = 0; i < ordered.Count; i++)
            {
                if (SameDevice(ordered[i].DeviceID, failoverTargetId)) { start = i; break; }
            }
            if (start <= 0) return ordered;

            var rotated = new List<PlayTarget>();
            for (int i = 0; i < ordered.Count; i++)
            {
                rotated.Add(ordered[(start + i) % ordered.Count]);
            }
            return rotated;
        }

        /// <summary>
        /// 用给定的一组目标设备把这一路真的拉起来。失败就把原因抛出去，
        /// 由调用方决定是换下一块设备再试，还是直接判这一路失败。
        /// </summary>
        private void StartWith(IList<PlaybackTargetSpec> specs, bool remote, MMDevice sourceDevice, bool followsDefault)
        {
            var ids = new List<string>();
            for (int i = 0; i < specs.Count; i++) ids.Add(specs[i].DeviceId);

            if (remote)
            {
                var net = new NetAudioRedirector(specs, playerInfo.IP, playerInfo.SourceDeviceID, playerInfo.Volume, playerInfo.SourceDeviceName);
                audioRedirector = net;
                PeerName = string.IsNullOrEmpty(net.RemotePcName) ? net.RemoteMachineId : net.RemotePcName;
                if (!net.IsActive)
                {
                    StopRedirector();
                    throw new InvalidOperationException("连上了对端，但这条路没能播起来");
                }
            }
            else
            {
                audioRedirector = new LocalAudioRedirector(sourceDevice, specs, playerInfo.Volume);
            }

            ResolvedTargetDeviceIds = ids;
            // 默认设备变了要跟着走，但只挂一次，重连不该越挂越多
            if (followsDefault && !defaultHooked)
            {
                deviceEnumerator.DefaultDeviceChanged += OnDefaultChange;
                defaultHooked = true;
            }
        }

        /// <summary>两个设备号指的是不是同一块设备。空值一律不算相等。</summary>
        private static bool SameDevice(string left, string right)
        {
            return !string.IsNullOrEmpty(left) && !string.IsNullOrEmpty(right)
                && string.Equals(left, right, StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>
        /// 把配置里写的来源设备解析成设备号。
        /// "default" 是此刻的系统默认输出设备（环回），"default-input" 是此刻的系统默认输入设备（麦克风），
        /// 其余原样返回。解析不出来时返回 null 并把原因写进 <paramref name="error"/>。
        /// </summary>
        private string ResolveSourceDeviceId(out string error)
        {
            return AudioDeviceHelper.ResolveSourceDeviceId(playerInfo.SourceDeviceID, out error);
        }

        /// <summary>
        /// 把配置里写的目标设备解析成能直接开的设备对象。
        /// 配置里写 "default" 时跟随当时的系统默认输出设备，并把这一槽标成「跟随默认」，
        /// 好让默认设备变了只换这一个，用户手工指定的那些不动。
        /// </summary>
        private static PlaybackTargetSpec ResolveTarget(PlayTarget target, out bool followsDefault)
        {
            followsDefault = false;
            if (target == null || string.IsNullOrEmpty(target.DeviceID)) return null;
            var deviceId = target.DeviceID;
            if (deviceId.ToLower() == "default")
            {
                followsDefault = true;
                deviceId = AudioDeviceHelper.GetDefaultOutputDeviceId();
            }
            var device = AudioDeviceHelper.GetDeviceById(deviceId);
            if (device == null)
            {
                device = AudioDeviceHelper.GetDeviceByName(target.DeviceName);
            }
            if (device == null) return null;
            return new PlaybackTargetSpec
            {
                DeviceId = device.DeviceID,
                DeviceName = string.IsNullOrEmpty(target.DeviceName) ? device.FriendlyName : target.DeviceName,
                FollowsDefault = followsDefault,
                Device = device
            };
        }

        /// <summary>目标设备在提示语里的名字。设备名可能缺失，退回设备号。</summary>
        private static string TargetText(PlayTarget target)
        {
            if (target == null) return "?";
            return string.IsNullOrEmpty(target.DeviceName) ? target.DeviceID : target.DeviceName;
        }

        /// <summary>
        /// 这一路此刻是不是正把从别处拉来的声音放给这块设备。
        /// 被拉取侧点单时靠它挡住「采自己正在放的东西」——那就是回路。
        /// </summary>
        public bool PlaysRemoteAudioTo(string deviceId)
        {
            if (string.IsNullOrEmpty(deviceId)) return false;
            if (!IsPlayingRemoteAudio) return false;
            var ids = ResolvedTargetDeviceIds;
            if (ids == null) return false;
            for (int i = 0; i < ids.Count; i++)
            {
                if (string.Equals(ids[i], deviceId, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }

        /// <summary>
        /// 看门狗：这一路没在响就按退避节奏重新拉起来。
        /// 对端重启、网线拔了又插、声卡被别的程序抢走，用户都不该需要自己去界面上点一下。
        /// </summary>
        private void OnWatchdog(object state)
        {
            try
            {
                if (disposed || starting) return;
                if (IsPlaying)
                {
                    attempts = 0;
                    nextRetryUtc = DateTime.MinValue;
                    return;
                }
                if (DateTime.UtcNow < nextRetryUtc) return;
                nextRetryUtc = DateTime.UtcNow.AddSeconds(BackoffSeconds(attempts));
                attempts++;
                if (TryBeginStart())
                {
                    Task.Run(() => StartCore());
                }
            }
            catch (Exception e)
            {
                Logger.Error("断线重连调度出错", e);
            }
        }

        /// <summary>重连间隔：先密后疏，避免对端长时间不在时把日志和网络刷爆。</summary>
        private static int BackoffSeconds(int attempts)
        {
            if (attempts <= 0) return 1;
            if (attempts == 1) return 2;
            if (attempts == 2) return 3;
            return MaxBackoffSeconds;
        }

        /// <summary>把当前这一路换掉，旧的连人带设备一起收干净。</summary>
        private void StopRedirector()
        {
            var old = audioRedirector;
            audioRedirector = null;
            if (old == null) return;
            try
            {
                old.Dispose();
            }
            catch (Exception e)
            {
                Logger.Error("停止上一路播放失败", e);
            }
        }

        /// <summary>把这一路的当前状态整理出来，供界面和排查使用。</summary>
        public Model.PlayerStatus GetStatus()
        {
            var left = (nextRetryUtc - DateTime.UtcNow).TotalSeconds;
            var target = playerInfo.PrimaryTarget;
            var redirector = audioRedirector;
            var status = new Model.PlayerStatus
            {
                ID = playerInfo.ID.ToString(),
                IP = playerInfo.IP,
                SourceDeviceID = playerInfo.SourceDeviceID,
                SourceDeviceName = playerInfo.SourceDeviceName,
                TargetDeiceID = ResolvedTargetDeviceIds.Count > 0
                    ? ResolvedTargetDeviceIds[0]
                    : (target == null ? null : target.DeviceID),
                TargetDeviceName = target == null ? null : target.DeviceName,
                Remote = IsRemote,
                Playing = IsPlaying,
                AudioFlowing = redirector != null && redirector.AudioFlowing,
                ActiveDeviceNames = redirector == null ? new List<string>() : redirector.ActiveDeviceNames,
                Peer = PeerName,
                Error = LastError,
                State = State,
                Attempts = attempts,
                NextRetryInSeconds = left <= 0 ? 0 : (int)Math.Ceiling(left),
                Policy = playerInfo.Policy.ToString(),
                Quality = redirector == null ? null : redirector.Quality,
                Targets = new List<PlayTarget>()
            };
            if (playerInfo.Targets != null)
            {
                for (int i = 0; i < playerInfo.Targets.Count; i++)
                {
                    status.Targets.Add(playerInfo.Targets[i].Copy());
                }
            }
            return status;
        }

        public void SetVolume(float Volume)
        {
            if (audioRedirector == null) return;
            audioRedirector.Volume = Volume;
            playerInfo.Volume = Volume;
        }

        public float GetVolume()
        {
            if (audioRedirector == null) return 1;
            return audioRedirector.Volume;
        }

        public void Dispose()
        {
            disposed = true;
            playerInfo.Play = false;
            watchdog.Dispose();
            StopRedirector();
            if (deviceEnumerator != null)
            {
                if (defaultHooked)
                {
                    deviceEnumerator.DefaultDeviceChanged -= OnDefaultChange;
                    defaultHooked = false;
                }
                deviceEnumerator.Dispose();
                deviceEnumerator = null;
            }
        }
    }
}
