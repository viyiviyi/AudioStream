using System;

namespace AudioStream.AudioServer.Model
{
    /// <summary>
    /// 一份音频数据供多个播放设备同时取用。
    ///
    /// 原先一路拉取只喂一个声卡，用的是单读写游标的 <see cref="LimitedBuffer"/>。
    /// 要让一路拉取同时喂多个声卡，每个声卡都得把同一份数据各自走一遍，
    /// 所以这里给每个读者一份独立的游标：写者只写一次，读者各读各的。
    ///
    /// 位置用「累计写入的绝对字节数」表示，而不是环形下标。这样「这个读者落后了多少」
    /// 一眼就能算出来，落后超过一圈就把它推到最新处（丢掉已经放不出来的旧数据），
    /// 不会出现某个卡住的声卡把整条缓冲拖死、连累其它声卡一起没声。
    /// </summary>
    public class AudioBroadcastBuffer
    {
        private readonly byte[] _buffer;
        private readonly int _maxSize;
        private readonly object _lockObject = new object();

        /// <summary>累计写入的字节数，同时充当绝对写位置。</summary>
        private long _written;

        /// <summary>累计写入的总字节数（含被读者跳过、被覆盖的），用于算收发量。</summary>
        private long _totalWritten;

        /// <summary>因为读者落后超过一整圈而被丢弃的字节数。正常运行时应当是 0。</summary>
        private long _droppedBytes;

        private bool _disposed;

        public AudioBroadcastBuffer(int maxSize)
        {
            if (maxSize <= 0) throw new ArgumentException("大小必须大于0", nameof(maxSize));
            _maxSize = maxSize;
            _buffer = new byte[maxSize];
        }

        /// <summary>缓冲区容量（字节）。</summary>
        public int MaxSize { get { return _maxSize; } }

        /// <summary>累计写入的总字节数。</summary>
        public long TotalWritten { get { lock (_lockObject) { return _totalWritten; } } }

        /// <summary>被丢弃的字节数（读者落后超过一整圈）。它不为 0 说明有读者被卡住过。</summary>
        public long DroppedBytes { get { lock (_lockObject) { return _droppedBytes; } } }

        /// <summary>
        /// 写入数据。超过一整圈的部分只有最后一段留得下来，前面的直接跳过——
        /// 与其让它挤掉别人，不如承认它已经放不出来了。
        /// </summary>
        public void Write(byte[] data, int offset, int count)
        {
            if (data == null || count <= 0) return;
            lock (_lockObject)
            {
                if (_disposed) return;
                if (count > _maxSize)
                {
                    offset += count - _maxSize;
                    count = _maxSize;
                }
                var start = (int)(_written % _maxSize);
                var first = Math.Min(count, _maxSize - start);
                Buffer.BlockCopy(data, offset, _buffer, start, first);
                if (count > first)
                {
                    Buffer.BlockCopy(data, offset + first, _buffer, 0, count - first);
                }
                _written += count;
                _totalWritten += count;
            }
        }

        /// <summary>
        /// 新开一个读者，每个播放设备一个。
        /// 从此刻的最新处开始，不要一上来就把缓冲里积压的旧声音补一遍——
        /// 那会让刚接上的设备先放一段几百毫秒前的声音。
        /// </summary>
        public Reader CreateReader()
        {
            lock (_lockObject)
            {
                return new Reader(this, _written);
            }
        }

        private int ReadFrom(Reader reader, byte[] buffer, int offset, int count)
        {
            lock (_lockObject)
            {
                if (_disposed || reader == null) return 0;
                var available = _written - reader.Position;
                if (available <= 0) return 0;
                // 落后超过一圈说明这个读者已经追不上了，从最近的地方重新接上
                if (available > _maxSize)
                {
                    _droppedBytes += available - _maxSize;
                    reader.Position = _written - _maxSize;
                    available = _maxSize;
                }
                var toRead = (int)Math.Min(count, available);
                var start = (int)(reader.Position % _maxSize);
                var first = Math.Min(toRead, _maxSize - start);
                Buffer.BlockCopy(_buffer, start, buffer, offset, first);
                if (toRead > first)
                {
                    Buffer.BlockCopy(_buffer, 0, buffer, offset + first, toRead - first);
                }
                reader.Position += toRead;
                return toRead;
            }
        }

        private long AvailableFor(Reader reader)
        {
            lock (_lockObject)
            {
                if (_disposed || reader == null) return 0;
                var available = _written - reader.Position;
                if (available <= 0) return 0;
                return Math.Min(available, _maxSize);
            }
        }

        public void Dispose()
        {
            lock (_lockObject) { _disposed = true; }
        }

        /// <summary>一个播放设备在这份数据上的读取游标。</summary>
        public class Reader
        {
            private readonly AudioBroadcastBuffer _owner;

            /// <summary>绝对读位置，与写者的 <see cref="_written"/> 同一坐标系。</summary>
            internal long Position;

            internal Reader(AudioBroadcastBuffer owner, long position)
            {
                _owner = owner;
                Position = position;
            }

            /// <summary>还有多少字节可以读。播放器靠它决定要不要起播。</summary>
            public long Available { get { return _owner.AvailableFor(this); } }

            public int Read(byte[] buffer, int offset, int count)
            {
                return _owner.ReadFrom(this, buffer, offset, count);
            }

            /// <summary>跳到最新位置，丢掉积压。输出设备被换掉重新开始时用得上。</summary>
            public void SkipToLatest()
            {
                lock (_owner._lockObject)
                {
                    Position = _owner._written;
                }
            }

            /// <summary>
            /// 丢弃若干字节（不拷贝到目标缓冲）。
            /// 两端声卡的晶振不可能完全一致，本地放得比对方采得快时缓冲会被慢慢抽干；
            /// 这里让消费侧偶尔多跳一帧，把水位拉回去，避免周期性欠载爆音。
            /// </summary>
            public int Skip(int bytes)
            {
                if (bytes <= 0) return 0;
                lock (_owner._lockObject)
                {
                    if (_owner._disposed) return 0;
                    var available = _owner._written - Position;
                    if (available <= 0) return 0;
                    var toSkip = (int)Math.Min(bytes, available);
                    Position += toSkip;
                    return toSkip;
                }
            }
        }
    }
}
