using AudioStream.AudioServer.Model;
using Common;
using CSCore;
using CSCore.CoreAudioAPI;
using CSCore.SoundOut;
using System;
using System.Collections.Generic;

namespace AudioStream.AudioServer
{
    /// <summary>
    /// 一个目标播放设备：要在哪块声卡上出声，以及它是否跟着系统默认设备走。
    /// </summary>
    internal class PlaybackTargetSpec
    {
        /// <summary>设备号。写 "default" 时 <see cref="Device"/> 取的是当时的系统默认设备。</summary>
        public string DeviceId { get; set; }

        /// <summary>设备名，只用于显示和报错。</summary>
        public string DeviceName { get; set; }

        /// <summary>这块是不是「跟随系统默认输出设备」的那个槽。默认设备变了只换它。</summary>
        public bool FollowsDefault { get; set; }

        /// <summary>已经解析好的设备对象。为 null 表示没解析出来。</summary>
        public MMDevice Device { get; set; }
    }

    /// <summary>
    /// 播放侧的调参集合。放在一处，好让「网络拉过来的」和「本机设备之间流转的」两套链路用同一组口径，
    /// 也让延迟与音质的取舍只在一个地方改。
    /// </summary>
    internal class PlaybackTuning
    {
        /// <summary>
        /// 声卡请求的缓冲时长。1ms 这种值会让声卡稍微调度慢一点就欠载爆音；
        /// 10ms 是共享模式一个引擎周期，既不会再被调度抖动打穿，也不额外增加延迟。
        /// </summary>
        public int OutputLatencyMs = 10;

        /// <summary>
        /// 抖动缓冲的目标水位（毫秒）。它直接决定端到端延迟的一大半。
        /// 采集/网络批次本身有 15.6ms 的粒度，水位天然会上下摆这么多，
        /// 所以目标值不能压到「摆动的下限低于声卡一次请求」的位置，否则就会欠载。
        /// </summary>
        public int TargetBufferMs = 30;

        /// <summary>缓冲空时先等这么久再补静音：网络抖动造成的短空档靠等就能过去，不必立刻出静音。</summary>
        public int UnderrunWaitMs = 15;

        /// <summary>
        /// 两次时钟漂移修正之间的最小间隔。太频繁地丢/插帧会听出失真，间隔太长又追不上漂移。
        /// 这个值必须配着 <see cref="DriftMaxRatio"/> 一起看：修正能力 = 单次限幅 / 间隔，
        /// 实测虚拟声卡这类设备的实际漂移能到千 ppm 量级，能力要明显大于它；
        /// 但同时频率不能高——每一次修正都是一次丢/补帧，频率高到每秒几十次就是持续的噪声。
        /// </summary>
        public int DriftIntervalMs = 120;

        /// <summary>
        /// 水位判据的平滑时间常数（毫秒）。
        /// 采集与播放都是 10~20ms 一批，瞬时水位天然上下摆十几毫秒——那是批次粒度造成的，不是时钟差。
        /// 用它做时间加权平滑，摆动被平均掉，剩下的才是真正的漂移。
        /// 不看平滑值时，两端时钟其实同步也会被摆动不断推过死区，变成一直在丢帧。
        /// </summary>
        public int DriftSmoothMs = 1000;

        /// <summary>
        /// 丢帧跳变处的交叉淡化时长（毫秒）。2ms 足够把一个硬切抹成人耳听不出的过渡，
        /// 又短到混音本身不会带来可闻的音染。
        /// </summary>
        public double FadeMilliseconds = 2.0;

        /// <summary>
        /// 平滑水位与目标的偏差小于这个值时一律不动它。
        /// 判据已经是平滑过的水位（批次摆动被滤掉了），所以这里的死区只用来挡残余的估计噪声，
        /// 不必像看瞬时水位时给得那么大。
        /// </summary>
        public int DriftDeadZoneMs = 5;

        /// <summary>
        /// 是否用重采样吸收两端时钟差。开着就不再做离散的丢帧/补帧，波形不会被切开。
        /// </summary>
        public bool ResampleEnabled = true;

        /// <summary>
        /// 重采样比率最多偏离 1 多少（0.03 = 3%）。
        /// 它决定这套机制能吸收多大的时钟差；超出这个范围的部分仍由溢出丢弃兜底。
        /// 真实双机一般在百 ppm 量级，但本机测试用的虚拟声卡实测能到 1.4%，
        /// 上限比这个宽一点才不至于顶住。只有偏差真的这么大时才会真的跑在那个比率上，
        /// 平时它只跟着实际时钟差走。
        /// </summary>
        public double ResampleMaxAdj = 0.03;

        /// <summary>每次修正走偏差的多大比例。比例控制才能让水位贴着目标，而不是偏在一侧。</summary>
        public double DriftGain = 0.3;

        /// <summary>单次修正最多动这一次读请求的多大比例。限住它，跳变才听不出来。</summary>
        public double DriftMaxRatio = 0.06;

        /// <summary>本机流转用一套默认值：不经过网络，不需要给网络抖动留余量。</summary>
        public static PlaybackTuning CreateDefault()
        {
            return new PlaybackTuning();
        }

        /// <summary>网络拉取用的一套：抖动缓冲目标按对方网络给的水位走。</summary>
        public static PlaybackTuning CreateForNetwork(int targetBufferMs)
        {
            var tuning = new PlaybackTuning();
            if (targetBufferMs > 0) tuning.TargetBufferMs = targetBufferMs;
            return tuning;
        }
    }

    /// <summary>
    /// 一块输出声卡上的音质计数。用户听不出「这是网络抖动还是声卡时钟漂移」，
    /// 但这两个数字能：欠载次数一直涨说明缓冲不够，漂移修正次数一直涨说明两端时钟在跑偏。
    /// </summary>
    internal class PlaybackQualityStats
    {
        /// <summary>读请求次数。</summary>
        public long ReadCalls;

        /// <summary>真正喂给声卡的字节数。</summary>
        public long BytesServed;

        /// <summary>缓冲空、只能补静音的次数。它一直涨就是周期性的咔哒声。</summary>
        public long Underruns;

        /// <summary>补出去的静音字节数。</summary>
        public long SilenceBytes;

        /// <summary>为把水位拉高而重复的字节数（本地放得比对方采得快时用）。</summary>
        public long DriftRepeatBytes;

        /// <summary>为把水位压低而跳过的字节数（本地放得比对方采得慢时用）。</summary>
        public long DriftDropBytes;

        /// <summary>
        /// 时钟漂移修正的次数（无论补还是丢）。
        /// 单看毫秒数看不出问题：同样的总量，摊成每秒几十次是持续噪声，摊成每秒几次几乎听不出来。
        /// 所以次数要单独记——两端时钟同步时这个数应当接近不涨。
        /// </summary>
        public long DriftCorrections;

        /// <summary>最近一次量到的缓冲水位（毫秒）。</summary>
        public int LevelMs;

        /// <summary>
        /// 当前重采样比率相对 1.0 的偏离（百万分之一）。
        /// 它稳定在一个非零值上是正常的：那正说明它一直在替两端声卡的时钟差跑腿。
        /// </summary>
        public int ResamplePpm;

        public void Reset()
        {
            ReadCalls = 0;
            BytesServed = 0;
            Underruns = 0;
            SilenceBytes = 0;
            DriftRepeatBytes = 0;
            DriftDropBytes = 0;
            DriftCorrections = 0;
            LevelMs = 0;
            ResamplePpm = 0;
        }
    }

    /// <summary>
    /// 一份音频数据往多个声卡上同时放。
    ///
    /// 一路拉取现在可以有多个播放目标，但数据只有一份：网络那头只拉一条连接、本机那台设备只采一次，
    /// 分发在本地做。<see cref="AudioBroadcastBuffer"/> 负责让每个声卡各读各的游标，
    /// 这里负责把声卡一个个建起来、有数据了把它们拉起来、以及某块声卡打不开时别拖垮其它块。
    ///
    /// 一块声卡起不来（被独占、被拔了）不当作整条失败：其它声卡照常出声，
    /// 界面上能看出是哪一块没起来。全都起不来才由上层判为这一路失败。
    /// </summary>
    internal class PlaybackGroup : IDisposable
    {
        private readonly AudioBroadcastBuffer buffer;
        private readonly List<Slot> outputs = new List<Slot>();
        private readonly object syncRoot = new object();

        /// <summary>起播门槛的宽限期：这么久还没攒够门槛也先播起来（只要有数据）。</summary>
        private const int StartGraceMs = 800;
        private readonly PlaybackTuning tuning;
        private float volume = 1;
        private volatile bool disposed;

        /// <summary>创建过程中攒下来的失败原因，用于「一块都没起来」时向上报。</summary>
        public string LastError { get; private set; }

        /// <summary>每毫秒的字节数。质量计数是拿字节数攒的，换算成毫秒才好让人判断严重程度。</summary>
        public int BytesPerMs { get; private set; }

        /// <summary>抖动缓冲容量（毫秒）。</summary>
        public int CapacityMs { get; private set; }

        /// <summary>
        /// 这一路此刻的音质指标。没有计数时返回 null，界面按「还没数据」处理。
        /// </summary>
        public Model.PlaybackQuality Quality
        {
            get
            {
                var bytesPerMs = BytesPerMs <= 0 ? 1 : BytesPerMs;
                return Model.PlaybackQuality.From(AggregateStats(), bytesPerMs,
                    tuning.TargetBufferMs, CapacityMs, buffer == null ? 0 : buffer.DroppedBytes);
            }
        }

        public PlaybackGroup(AudioBroadcastBuffer buffer, WaveFormat format, IList<PlaybackTargetSpec> targets, float volume)
            : this(buffer, format, targets, volume, null)
        {
        }

        public PlaybackGroup(AudioBroadcastBuffer buffer, WaveFormat format, IList<PlaybackTargetSpec> targets, float volume, PlaybackTuning tuning)
        {
            if (buffer == null) throw new ArgumentNullException("buffer");
            this.buffer = buffer;
            this.volume = volume;
            this.tuning = tuning == null ? PlaybackTuning.CreateDefault() : tuning;
            LastError = string.Empty;
            if (format != null && format.BytesPerSecond > 0)
            {
                BytesPerMs = Math.Max(1, (int)(format.BytesPerSecond / 1000.0));
                CapacityMs = (int)(buffer.MaxSize / (double)BytesPerMs);
            }
            if (targets != null)
            {
                for (int i = 0; i < targets.Count; i++)
                {
                    Add(targets[i], format);
                }
            }
            if (outputs.Count == 0)
            {
                var message = string.IsNullOrEmpty(LastError) ? "没有可用的播放设备" : LastError;
                // 一块都没开出来，把自己已经建起来的收干净再抛：上层会按失败重连，
                // 留着半截对象只会让声卡句柄越攒越多
                Dispose();
                throw new InvalidOperationException(message);
            }
        }

        /// <summary>此刻是否至少有一块声卡真的在出声。</summary>
        public bool IsActive
        {
            get
            {
                foreach (var output in Snapshot())
                {
                    try
                    {
                        if (output.Output != null && output.Output.PlaybackState == PlaybackState.Playing) return true;
                    }
                    catch (Exception)
                    {
                    }
                }
                return false;
            }
        }

        /// <summary>真正在出声的声卡数量，给状态用。</summary>
        public int ActiveCount
        {
            get
            {
                var count = 0;
                foreach (var output in Snapshot())
                {
                    try
                    {
                        if (output.Output != null && output.Output.PlaybackState == PlaybackState.Playing) count++;
                    }
                    catch (Exception)
                    {
                    }
                }
                return count;
            }
        }

        /// <summary>此刻真的在出声的设备名。全播策略下可能有好几个。</summary>
        public List<string> ActiveDeviceNames
        {
            get
            {
                var names = new List<string>();
                foreach (var output in Snapshot())
                {
                    try
                    {
                        if (output.Output != null && output.Output.PlaybackState == PlaybackState.Playing)
                        {
                            names.Add(output.DeviceName);
                        }
                    }
                    catch (Exception)
                    {
                    }
                }
                return names;
            }
        }

        /// <summary>已经建起来的声卡数量（含暂时没出声的）。</summary>
        public int OutputCount
        {
            get { lock (syncRoot) { return outputs.Count; } }
        }

        public float Volume
        {
            get { return volume; }
            set
            {
                volume = value;
                foreach (var output in Snapshot())
                {
                    try
                    {
                        if (output.Output != null) output.Output.Volume = value;
                    }
                    catch (Exception)
                    {
                        // 某块声卡不吃这个音量就跳过，别影响别的
                    }
                }
            }
        }

        /// <summary>
        /// 有数据来了就调用它。每块声卡各自判断自己的游标里有没有东西可读，
        /// 有就起播——某块声卡卡住或起晚了，不该拖着别的声卡一起不出声。
        /// </summary>
        public void Pump()
        {
            if (disposed) return;
            foreach (var output in Snapshot())
            {
                try
                {
                    if (output.Output == null) continue;
                    if (output.Output.PlaybackState == PlaybackState.Playing) continue;
                    if (output.Reader == null) continue;
                    var available = output.Reader.Available;
                    if (available < output.StartThresholdBytes)
                    {
                        // 攒够半个目标水位再起播，起播那一下才不至于立刻欠载。
                        // 但不能无限等：对端一次只推一小段时，死等门槛会一直不出声。
                        // 宽限期一过，手上只要有数据就先响起来，欠载由补静音兜着（不会停播）。
                        if (available <= 0) continue;
                        if (Environment.TickCount - output.CreatedAt < StartGraceMs) continue;
                    }
                    output.Output.Play();
                }
                catch (Exception e)
                {
                    output.Error = e.Message;
                    Logger.Error("起播一块播放设备失败：" + output.DeviceName, e);
                }
            }
        }

        /// <summary>
        /// 系统默认输出设备换了。只换那些「跟随默认」的槽，用户手工指定的设备不动。
        /// </summary>
        public void SetDevice(MMDevice device)
        {
            if (device == null || disposed) return;
            foreach (var output in Snapshot())
            {
                if (!output.FollowsDefault) continue;
                try
                {
                    if (output.Output != null)
                    {
                        output.Output.Stop();
                        output.Output.Device = device;
                    }
                    output.Device = device;
                    // 换设备会有一段空档，新的那块从当前最新的数据接上，不要补放积压
                    if (output.Reader != null) output.Reader.SkipToLatest();
                }
                catch (Exception e)
                {
                    output.Error = e.Message;
                    Logger.Error("切换跟随默认的输出设备失败", e);
                }
            }
        }

        /// <summary>把每一块声卡的质量计数汇总成一份，供状态上报。水位取各块里最大的那个。</summary>
        public PlaybackQualityStats AggregateStats()
        {
            var total = new PlaybackQualityStats();
            foreach (var output in Snapshot())
            {
                var item = output.Stats;
                if (item == null) continue;
                total.ReadCalls += item.ReadCalls;
                total.BytesServed += item.BytesServed;
                total.Underruns += item.Underruns;
                total.SilenceBytes += item.SilenceBytes;
                total.DriftRepeatBytes += item.DriftRepeatBytes;
                total.DriftDropBytes += item.DriftDropBytes;
                total.DriftCorrections += item.DriftCorrections;
                if (item.LevelMs > total.LevelMs) total.LevelMs = item.LevelMs;
                // 多块声卡各自跑各自的比率，报最「吃力」的那一块
                if (Math.Abs(item.ResamplePpm) > Math.Abs(total.ResamplePpm)) total.ResamplePpm = item.ResamplePpm;
            }
            return total;
        }

        /// <summary>把每一块输出此刻的情况列出来，供状态上报与排查。</summary>
        public List<Slot> Snapshot()
        {
            lock (syncRoot)
            {
                return new List<Slot>(outputs);
            }
        }

        private void Add(PlaybackTargetSpec target, WaveFormat format)
        {
            if (target == null || target.Device == null)
            {
                LastError = "本机找不到播放设备：" + (target == null ? "?" : target.DeviceName);
                return;
            }
            var output = new Slot
            {
                DeviceId = target.DeviceId,
                DeviceName = string.IsNullOrEmpty(target.DeviceName) ? target.Device.DeviceID : target.DeviceName,
                FollowsDefault = target.FollowsDefault,
                Device = target.Device
            };
            try
            {
                var reader = buffer.CreateReader();
                var source = new BroadcastReaderSource(reader, format, tuning, output.Stats);
                output.Reader = reader;
                // 攒到目标水位的一半再起播：一起播就欠载的话，开头那段只能是噼啪声
                output.StartThresholdBytes = Math.Max(format.BlockAlign,
                    (long)(format.BytesPerSecond / 1000.0 * tuning.TargetBufferMs / 2));
                output.CreatedAt = Environment.TickCount;
                output.Output = new WasapiOut();
                output.Output.Device = target.Device;
                // 缓冲只有 1ms 时，声卡线程但凡被系统调度慢一点就直接欠载，听感是持续的噼啪声。
                // 20ms 是稳态与延迟之间的折中，端到端仍然落在内网可接受的范围里。
                output.Output.Latency = tuning.OutputLatencyMs;
                output.Output.Initialize(source.ToSampleSource().ToWaveSource());
                output.Output.Volume = volume;
            }
            catch (Exception e)
            {
                output.Error = e.Message;
                LastError = "打不开播放设备「" + output.DeviceName + "」：" + e.Message;
                Logger.Error("打不开播放设备：" + output.DeviceName, e);
                // 建到一半失败的也留着：界面上要看得出是哪一块没起来
                if (output.Output != null)
                {
                    try { output.Output.Dispose(); } catch (Exception) { }
                    output.Output = null;
                }
            }
            lock (syncRoot)
            {
                outputs.Add(output);
            }
        }

        public void Dispose()
        {
            disposed = true;
            List<Slot> snapshot;
            lock (syncRoot)
            {
                snapshot = new List<Slot>(outputs);
                outputs.Clear();
            }
            foreach (var output in snapshot)
            {
                try
                {
                    if (output.Output != null) output.Output.Dispose();
                }
                catch (Exception)
                {
                }
                output.Output = null;
                output.Reader = null;
            }
        }

        /// <summary>一块输出声卡的运行情况。</summary>
        internal class Slot
        {
            public string DeviceId;
            public string DeviceName;

            /// <summary>是不是跟随系统默认设备的那个槽。</summary>
            public bool FollowsDefault;

            /// <summary>设备对象。注意它的所有权跟着 WasapiOut，不要在这里单独释放。</summary>
            public MMDevice Device;

            public WasapiOut Output;
            public AudioBroadcastBuffer.Reader Reader;

            /// <summary>这块声卡的音质计数，供状态上报。</summary>
            public PlaybackQualityStats Stats = new PlaybackQualityStats();

            /// <summary>攒到这个字节数才起播。太早起播等于一起播就欠载，开头就是一段噼啪。</summary>
            public long StartThresholdBytes;

            /// <summary>这块输出建起来的时刻，用来给起播门槛加一个宽限期。</summary>
            public int CreatedAt;

            /// <summary>这块声卡出错时的原因，正常为空。</summary>
            public string Error;
        }

        /// <summary>
        /// 把广播缓冲里某个读者的数据当作一个音频源交给 CSCore。
        /// 每个输出设备一份，各自持有独立游标，互不影响。
        ///
        /// 这里同时兜住两件事，两件都是「声音里有杂音」的直接来源：
        /// 一是缓冲空了不能返回 0——CSCore 会把 0 当成流结束，于是停播、再被拉起来，反复起停就是持续的咔哒声；
        /// 二是两端声卡的晶振不可能一致，本地放得比对方采得快时缓冲会被慢慢抽干，
        /// 不管的话每隔十来秒就会因为被抽干而爆一次音，所以按水位偶尔少消费/多跳一帧，把水位按住在目标附近。
        /// </summary>
        private class BroadcastReaderSource : IWaveSource
        {
            private readonly AudioBroadcastBuffer.Reader reader;
            private readonly WaveFormat waveFormat;
            private readonly PlaybackTuning tuning;
            private readonly PlaybackQualityStats stats;
            private readonly int blockAlign;
            private readonly double bytesPerMs;

            /// <summary>上一次做漂移修正的时刻，用来节流：修正太频繁反而会听出失真。</summary>
            private int lastDriftAt;

            /// <summary>时间加权平滑后的水位（字节）。漂移判据用它而不是瞬时值。</summary>
            private double smoothedLevel = -1;

            /// <summary>上一次更新平滑水位的时刻，用来算时间权重。</summary>
            private int lastLevelAt;

            /// <summary>已经决定要少消费的字节数，下一次读的时候兑现。</summary>
            private int pendingRepeatBytes;

            /// <summary>
            /// 跳变淡化用的「上一块输出」的尾部样本。
            /// 丢帧是在两段本不相邻的音频之间硬切，切口本身就是一声极短的咔哒；
            /// 把切口前的尾巴存下来，下一块开头与它交叉淡化，听感上就只剩一次极短的过渡。
            /// </summary>
            private readonly byte[] fadeTail;

            /// <summary>每个样本几个字节（4 = 32 位浮点、2 = 16 位整数、0 = 该格式不做淡化）。</summary>
            private readonly int fadeBytesPerSample;

            /// <summary>淡化用多少采样帧。</summary>
            private readonly int fadeFrames;

            /// <summary>声道数，算样本偏移用。</summary>
            private readonly int channels;

            /// <summary>刚跳过一次，下一块开头要与上一块的尾巴交叉淡化。</summary>
            private bool fadePending;

            /// <summary>
            /// 重采样用的输入暂存（帧对齐）。从广播缓冲读出来的帧先放这儿，
            /// 输出侧按浮点读指针在帧之间插值取，取不完的留到下一轮。
            /// </summary>
            private byte[] reservoir;

            /// <summary>暂存里现有多少帧。</summary>
            private int reservoirFrames;

            /// <summary>下一次插值要从暂存的第几帧开始（浮点，单位是帧）。</summary>
            private double readPos;

            /// <summary>
            /// 当前重采样比率：每输出一帧要消耗多少输入帧。
            /// 1.0 表示两端速率一致；略大于 1 表示本地放得慢（多消耗一点把水位压下去），
            /// 略小于 1 表示本地放得快（少消耗一点把水位抬起来）。
            /// 它是连续微调的，不像丢/补帧那样会切开波形。
            /// </summary>
            private double resampleRatio = 1.0;

            /// <summary>上一次往状态里写比率的时刻，用来节流。</summary>
            private int lastRatioReportAt;

            /// <summary>上一次输出的最后一帧。缓冲被抽干时拿它顶上，比补静音自然。</summary>
            private readonly byte[] lastFrame;

            public BroadcastReaderSource(AudioBroadcastBuffer.Reader reader, WaveFormat waveFormat,
                PlaybackTuning tuning, PlaybackQualityStats stats)
            {
                this.reader = reader;
                this.waveFormat = waveFormat;
                this.tuning = tuning;
                this.stats = stats;
                this.blockAlign = Math.Max(1, waveFormat.BlockAlign);
                this.bytesPerMs = waveFormat.BytesPerSecond / 1000.0;
                this.channels = Math.Max(1, waveFormat.Channels);
                this.fadeBytesPerSample = ResolveFadeBytesPerSample(waveFormat);
                this.fadeFrames = fadeBytesPerSample > 0
                    ? Math.Max(1, (int)(waveFormat.SampleRate * tuning.FadeMilliseconds / 1000.0))
                    : 0;
                this.fadeTail = fadeFrames > 0 ? new byte[fadeFrames * blockAlign] : null;
                this.lastFrame = new byte[blockAlign];
            }

            public WaveFormat WaveFormat { get { return waveFormat; } }

            public long Length { get { return -1; } }

            public long Position { get; set; }

            public bool CanSeek { get { return false; } }

            public int Read(byte[] buffer, int offset, int count)
            {
                if (count <= 0) return 0;
                stats.ReadCalls++;

                // 缓冲不够就先等一小会儿。网络抖动造成的短空档靠等就能过去，
                // 实在等不到才补静音——补静音至少不会让声卡停播。
                if (reader.Available < count && tuning.UnderrunWaitMs > 0)
                {
                    var deadline = Environment.TickCount + tuning.UnderrunWaitMs;
                    while (reader.Available < count)
                    {
                        if (Environment.TickCount - deadline >= 0) break;
                        System.Threading.Thread.Sleep(1);
                    }
                }

                var level = reader.Available;
                stats.LevelMs = bytesPerMs > 0 ? (int)(level / bytesPerMs) : 0;
                AdjustDrift(level, count);

                // 能重采样就用重采样：它不切开波形，只把播放速率连续微调一丁点，
                // 两端时钟的差异被它悄悄吸收，听感上留不下痕迹。
                // 格式不支持时退回「丢/补一帧 + 交叉淡化」那条老路。
                if (fadeBytesPerSample > 0 && tuning.ResampleEnabled)
                {
                    try
                    {
                        return ReadResampled(buffer, offset, count);
                    }
                    catch (Exception e)
                    {
                        // 异常会被 WasapiOut 内部吞掉，表现成「读了一两次就再也不读了」，
                        // 现场什么都看不到，所以必须在这里留痕
                        Logger.Error("重采样读取失败（请求 " + count + " 字节 帧对齐 " + blockAlign
                            + " 声道 " + channels + " 暂存 " + reservoirFrames
                            + " 帧 读指针 " + readPos + " 比率 " + resampleRatio + "）", e);
                        throw;
                    }
                }

                var produced = ProduceInto(buffer, offset, count, level);
                FinishBlock(buffer, offset, produced);
                return produced;
            }

            /// <summary>
            /// 重采样读取：输出帧数恒定，输入按 <see cref="resampleRatio"/> 连续消耗。
            ///
            /// 这是「丢帧」的替代方案。丢帧是每隔一阵把一段波形切掉再接上，切口本身就是噪声；
            /// 重采样则是让本地播放速率极微地跟随对方时钟跑（比如快 0.02%），
            /// 音高变化小到听不出来，却没有一处波形是不连续的。
            /// </summary>
            private int ReadResampled(byte[] buffer, int offset, int count)
            {
                var outFrames = count / blockAlign;
                if (outFrames <= 0)
                {
                    Array.Clear(buffer, offset, count);
                    return count;
                }

                // 这一轮最远的输出帧落在暂存的第几帧，插值还要用到它的下一帧
                var need = (int)Math.Ceiling(readPos + (outFrames - 1) * resampleRatio) + 2;
                EnsureReservoir(need);

                var frames = reservoirFrames;
                if (frames < 2)
                {
                    // 真的一点数据都没有了。这里不能补静音——静音在音乐里是一个听得见的空洞；
                    // 重复上一帧听感上只是极短的一顿，比空洞不明显得多。
                    FillFromLastFrame(buffer, offset, count);
                    stats.Underruns++;
                    stats.SilenceBytes += count;
                    stats.BytesServed += count;
                    return count;
                }

                for (var i = 0; i < outFrames; i++)
                {
                    var p = readPos + i * resampleRatio;
                    var i0 = (int)p;
                    var frac = p - i0;
                    if (i0 < 0)
                    {
                        i0 = 0;
                        frac = 0;
                    }
                    // 已经到暂存末尾：拿最后一帧顶上，别越界
                    if (i0 > frames - 2)
                    {
                        i0 = frames - 2;
                        frac = 0;
                    }
                    var srcA = i0 * blockAlign;
                    var srcB = srcA + blockAlign;
                    var dst = offset + i * blockAlign;
                    if (fadeBytesPerSample == 4)
                    {
                        for (var c = 0; c < channels; c++)
                        {
                            var at = c * 4;
                            var a = BitConverter.ToSingle(reservoir, srcA + at);
                            var b = BitConverter.ToSingle(reservoir, srcB + at);
                            var mixed = BitConverter.GetBytes((float)(a + (b - a) * frac));
                            Buffer.BlockCopy(mixed, 0, buffer, dst + at, 4);
                        }
                    }
                    else
                    {
                        for (var c = 0; c < channels; c++)
                        {
                            var at = c * 2;
                            var a = BitConverter.ToInt16(reservoir, srcA + at);
                            var b = BitConverter.ToInt16(reservoir, srcB + at);
                            var mixed = (short)(a + (b - a) * frac);
                            buffer[dst + at] = (byte)(mixed & 0xFF);
                            buffer[dst + at + 1] = (byte)((mixed >> 8) & 0xFF);
                        }
                    }
                }

                // 读指针前进：这一轮输出吃掉了 outFrames * ratio 帧输入。
                // 少了这一步就变成每轮都从暂存第 0 帧重新插值、一帧都不消费，
                // 广播缓冲的游标不动，水位会一直顶在容量上。
                //
                // 但前进量不能超过暂存里真有的帧数：数据还没送到时（下游慢、网络空档），
                // 若照旧全速前进，指针会跑到暂存前面去，下一轮拿它算出的偏移就是负数，
                // 读缓冲会直接抛异常把这条播放线程弄死——现象是「读了一两次就再也不出声」。
                var advance = outFrames * resampleRatio;
                if (advance > reservoirFrames) advance = reservoirFrames;
                readPos += advance;

                // 把已经整帧用掉的部分从暂存里挪走，小数部分留到下一轮
                var consumed = (int)readPos;
                if (consumed > reservoirFrames) consumed = reservoirFrames;
                if (consumed > 0)
                {
                    var keep = (reservoirFrames - consumed) * blockAlign;
                    if (keep > 0)
                    {
                        Buffer.BlockCopy(reservoir, consumed * blockAlign, reservoir, 0, keep);
                    }
                    reservoirFrames -= consumed;
                    readPos -= consumed;
                    if (readPos < 0) readPos = 0;
                }

                stats.BytesServed += count;
                SaveLastFrame(buffer, offset, count);
                return count;
            }

            /// <summary>
            /// 用上一次输出的最后一帧把这一段填上。
            /// 缓冲被抽干时补静音会造成一个听得见的空洞；重复一帧只是把声音多拖十几毫秒，
            /// 在有内容的音频里比空洞自然得多。
            /// </summary>
            private void FillFromLastFrame(byte[] buffer, int offset, int count)
            {
                if (lastFrame == null)
                {
                    Array.Clear(buffer, offset, count);
                    return;
                }
                for (var done = 0; done < count; done += blockAlign)
                {
                    var n = Math.Min(blockAlign, count - done);
                    Buffer.BlockCopy(lastFrame, 0, buffer, offset + done, n);
                }
            }

            /// <summary>记下这一块输出的最后一帧，欠载时与下一轮都可能用到。</summary>
            private void SaveLastFrame(byte[] buffer, int offset, int count)
            {
                if (lastFrame == null || count < blockAlign) return;
                Buffer.BlockCopy(buffer, offset + count - blockAlign, lastFrame, 0, blockAlign);
            }

            /// <summary>
            /// 按「平滑水位与目标的偏差」调整重采样比率。
            ///
            /// 偏差在死区内就把比率放回 1.0；在外就按比例给一个极小的速率修正（限幅在 ResampleMaxAdj 以内），
            /// 并且对比率本身再做一次平滑——比率自己跳动同样会带出音高抖动。
            /// 稳态时这个比率会自动停在等于两端真实时钟差的位置上，水位也就稳住了。
            /// </summary>
            private void UpdateResampleRatio(double diff, long target, int now)
            {
                var deadZone = bytesPerMs * tuning.DriftDeadZoneMs;
                var wanted = 1.0;
                if (Math.Abs(diff) > deadZone)
                {
                    var adj = diff / Math.Max(1.0, (double)target) * tuning.DriftGain;
                    if (adj > tuning.ResampleMaxAdj) adj = tuning.ResampleMaxAdj;
                    if (adj < -tuning.ResampleMaxAdj) adj = -tuning.ResampleMaxAdj;
                    wanted = 1.0 + adj;
                }
                resampleRatio += (wanted - resampleRatio) * 0.1;
                if (resampleRatio < 0.5) resampleRatio = 0.5;
                if (resampleRatio > 1.5) resampleRatio = 1.5;

                // 把当前比率报出去（每秒最多一次）：用户看到的不是一个涨不停的计数，
                // 而是一个稳定的 ppm 值——那正说明它在持续替两端时钟跑差。
                if (now - lastRatioReportAt >= 1000)
                {
                    lastRatioReportAt = now;
                    stats.ResamplePpm = (int)Math.Round((resampleRatio - 1.0) * 1000000.0);
                }
            }

            /// <summary>保证暂存里至少有 needFrames 帧，不够就从广播缓冲补。</summary>
            private void EnsureReservoir(int needFrames)
            {
                if (needFrames <= reservoirFrames) return;
                var needBytes = needFrames * blockAlign;
                if (reservoir == null || reservoir.Length < needBytes)
                {
                    // 留出余量，免得每次调用都重新分配
                    Array.Resize(ref reservoir, Math.Max(needBytes * 2, blockAlign * 16));
                }
                var have = reservoirFrames * blockAlign;
                var read = reader.Read(reservoir, have, reservoir.Length - have);
                reservoirFrames += read / blockAlign;
            }

            /// <summary>
            /// 把这一轮的输出凑满 count 字节，返回恒为 count。
            /// 三条路径：按漂移修正少消费一段、正常读满、读不满就补上。
            /// </summary>
            private int ProduceInto(byte[] buffer, int offset, int count, long level)
            {
                // 上一轮判定本地放得偏快，这一轮少消费一段：把尾部的帧重复几遍凑满输出。
                if (pendingRepeatBytes > 0)
                {
                    var repeat = Math.Min(pendingRepeatBytes, Math.Max(0, count - blockAlign));
                    repeat = repeat / blockAlign * blockAlign;
                    pendingRepeatBytes = 0;
                    if (level >= count && repeat >= blockAlign)
                    {
                        var got = reader.Read(buffer, offset, count - repeat);
                        if (got >= blockAlign)
                        {
                            var filled = got;
                            while (filled < count)
                            {
                                var n = Math.Min(blockAlign, count - filled);
                                Buffer.BlockCopy(buffer, offset + got - blockAlign, buffer, offset + filled, n);
                                filled += n;
                            }
                            stats.BytesServed += count;
                            stats.DriftRepeatBytes += repeat;
                            return count;
                        }
                    }
                }

                var read = reader.Read(buffer, offset, count);
                if (read >= count)
                {
                    stats.BytesServed += count;
                    return count;
                }

                if (read > 0)
                {
                    // 只差一点点：拿已有数据的最后一帧补满，比插一段静音听感更连续
                    var fill = count - read;
                    var frame = Math.Min(blockAlign, read);
                    for (var done = 0; done < fill; )
                    {
                        var n = Math.Min(frame, fill - done);
                        Buffer.BlockCopy(buffer, offset + read - frame, buffer, offset + read + done, n);
                        done += n;
                    }
                    stats.Underruns++;
                    stats.BytesServed += count;
                    return count;
                }

                // 真的一点数据都没有：补静音并把欠载记下来。
                // 这个数字一直涨就说明抖动缓冲还是不够，用户听到的就是一阵阵的咔哒。
                Array.Clear(buffer, offset, count);
                stats.Underruns++;
                stats.SilenceBytes += count;
                stats.BytesServed += count;
                return count;
            }

            /// <summary>
            /// 输出之后收尾：刚跳过一段就把新开头与上一块的尾巴交叉淡化（把硬切抹平），
            /// 然后把这轮的尾巴留给下一轮。
            /// </summary>
            private void FinishBlock(byte[] buffer, int offset, int count)
            {
                if (fadePending)
                {
                    ApplyFade(buffer, offset, count);
                    fadePending = false;
                }
                SaveTail(buffer, offset, count);
            }

            /// <summary>上一块输出的最后 fadeFrames 帧留一份，供下一次跳变时淡化用。</summary>
            private void SaveTail(byte[] buffer, int offset, int count)
            {
                if (fadeTail == null) return;
                if (count < fadeTail.Length)
                {
                    Array.Clear(fadeTail, 0, fadeTail.Length);
                    Buffer.BlockCopy(buffer, offset, fadeTail, 0, count);
                    return;
                }
                Buffer.BlockCopy(buffer, offset + count - fadeTail.Length, fadeTail, 0, fadeTail.Length);
            }

            /// <summary>把这一块开头的 fadeFrames 帧与上一块的尾巴线性交叉淡化。</summary>
            private void ApplyFade(byte[] buffer, int offset, int count)
            {
                if (fadeTail == null || fadeFrames <= 0) return;
                var frames = Math.Min(fadeFrames, count / blockAlign);
                if (frames <= 0) return;
                for (var f = 0; f < frames; f++)
                {
                    var w = (f + 1f) / frames;
                    var keep = 1f - w;
                    for (var c = 0; c < channels; c++)
                    {
                        var idx = f * blockAlign + c * fadeBytesPerSample;
                        if (fadeBytesPerSample == 4)
                        {
                            var mixed = BitConverter.ToSingle(fadeTail, idx) * keep
                                + BitConverter.ToSingle(buffer, offset + idx) * w;
                            var bytes = BitConverter.GetBytes(mixed);
                            Buffer.BlockCopy(bytes, 0, buffer, offset + idx, 4);
                        }
                        else if (fadeBytesPerSample == 2)
                        {
                            var mixed = (short)(BitConverter.ToInt16(fadeTail, idx) * keep
                                + BitConverter.ToInt16(buffer, offset + idx) * w);
                            buffer[offset + idx] = (byte)(mixed & 0xFF);
                            buffer[offset + idx + 1] = (byte)((mixed >> 8) & 0xFF);
                        }
                    }
                }
            }

            /// <summary>能安全做淡化的样本宽度：32 位浮点与 16 位整数。其它格式退回硬切（返回 0）。</summary>
            private static int ResolveFadeBytesPerSample(WaveFormat format)
            {
                var extensible = format as WaveFormatExtensible;
                var isFloat = extensible != null
                    ? extensible.SubFormat == AudioSubTypes.IeeeFloat
                    : format.WaveFormatTag == AudioEncoding.IeeeFloat;
                if (isFloat && format.BitsPerSample == 32) return 4;
                if (!isFloat && format.BitsPerSample == 16) return 2;
                return 0;
            }

            /// <summary>
            /// 按水位做时钟漂移修正。水位高说明本地放得慢（跳过一段），低说明放得快（下一轮少消费一段）。
            ///
            /// 用比例控制而不是「越过某个倍数才动一下」：后者会让水位长期偏在一侧，
            /// 等它越线时已经攒了十几毫秒，只能一次丢一大块，听起来就是咔哒。
            /// 这里每次只走偏差的 <see cref="PlaybackTuning.DriftGain"/>，并限幅在一次读请求的
            /// <see cref="PlaybackTuning.DriftMaxRatio"/> 以内，水位贴着目标慢慢收敛，跳变小到听不出来。
            /// </summary>
            private void AdjustDrift(long level, int requestBytes)
            {
                if (bytesPerMs <= 0 || blockAlign <= 0) return;

                var now = Environment.TickCount;
                UpdateSmoothedLevel(level, now);

                // 一次修正的最小粒度是一帧，请求比一帧还小时没有可动的空间
                if (requestBytes <= blockAlign) return;

                var target = (long)bytesPerMs * tuning.TargetBufferMs;
                var diff = smoothedLevel - target;

                // 重采样路径：不做离散的丢/补，只把播放比率连续改一丁点
                if (fadeBytesPerSample > 0 && tuning.ResampleEnabled)
                {
                    UpdateResampleRatio(diff, target, now);
                    return;
                }

                // 死区按批次粒度给。判据用的是平滑水位，所以这里挡掉的是「平滑之后仍然偏离」的部分，
                // 也就是真的在漂，而不是批次造成的摆动。
                if (Math.Abs(diff) <= (long)bytesPerMs * tuning.DriftDeadZoneMs) return;

                if (now - lastDriftAt < tuning.DriftIntervalMs) return;

                var want = (long)(Math.Abs(diff) * tuning.DriftGain);
                var frames = (int)(want / blockAlign);
                if (frames < 1) frames = 1;
                var maxFrames = (int)(requestBytes * tuning.DriftMaxRatio / blockAlign);
                if (maxFrames < 1) maxFrames = 1;
                if (frames > maxFrames) frames = maxFrames;
                var amount = frames * blockAlign;

                if (diff > 0)
                {
                    var skipped = reader.Skip(amount);
                    if (skipped > 0)
                    {
                        stats.DriftDropBytes += skipped;
                        stats.DriftCorrections++;
                        lastDriftAt = now;
                        // 跳过的一段和后面接上的那段本不相邻，这里标记一下让下一块开头做淡化
                        fadePending = true;
                    }
                }
                else
                {
                    pendingRepeatBytes = amount;
                    stats.DriftCorrections++;
                    lastDriftAt = now;
                }
            }

            /// <summary>
            /// 把瞬时水位并进时间加权的平滑值。
            /// 按「距上次过了多少毫秒」算权重而不是按调用次数，这样声卡一次要 10ms 还是 20ms 都不影响平滑的含义。
            /// </summary>
            private void UpdateSmoothedLevel(long level, int now)
            {
                if (smoothedLevel < 0)
                {
                    smoothedLevel = level;
                    lastLevelAt = now;
                    return;
                }
                var dt = now - lastLevelAt;
                if (dt <= 0)
                {
                    // 同一毫秒内的多次调用：并一点点进去，别让权重退化成 0
                    smoothedLevel += (level - smoothedLevel) * 0.01;
                    return;
                }
                lastLevelAt = now;
                var alpha = 1 - Math.Exp(-dt / (double)Math.Max(1, tuning.DriftSmoothMs));
                smoothedLevel += (level - smoothedLevel) * alpha;
            }

            public void Dispose()
            {
                // 缓冲与游标归 PlaybackGroup 与上层所有，这里不释放
            }
        }
    }
}
