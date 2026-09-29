using System;
using System.IO;

namespace AudioStream.AudioServer
{
    /// <summary>
    /// 一条连接上跑的消息类型。
    ///
    /// 之所以要有类型和长度：TCP 是**字节流**，没有消息边界。原来靠「一次读到的包不超过 32 字节
    /// 那它就是心跳」这种长度猜测来区分控制包和音频，一旦内核把一个小包和后面的音频并在一次读里、
    /// 或者把一帧音频拆成两次读，这个猜测就会吃掉真正的音频字节——音频是定长采样，少几个字节
    /// 后面全部错位，听起来就是持续的杂音。所以每条消息自己带长度和类型，读多少字节是确定的。
    /// </summary>
    internal enum FrameKind : byte
    {
        /// <summary>采集到的 PCM 原始字节，收到就直接写进播放缓冲。</summary>
        Audio = 1,

        /// <summary>UTF-8 文本命令（/Hello、/Start、/Ping 之类），负载里不带换行。</summary>
        Text = 2,

        /// <summary>音频格式描述（二进制），点单之后由被拉取侧回一条。</summary>
        WaveFormat = 3
    }

    /// <summary>
    /// 帧的编解码。帧格式：[4 字节小端长度][1 字节类型][负载]，长度**包含**类型字节。
    /// </summary>
    internal static class AudioFraming
    {
        /// <summary>帧头：4 字节长度 + 1 字节类型。</summary>
        public const int HeaderSize = 5;

        /// <summary>
        /// 单帧负载上限。这是个防护值：连接对端若不是本程序（或者内存数据坏了），
        /// 长度字段可能是任意数，不拦住就会照着它去分配一大块内存。
        /// 正常音频帧在几 KB 量级，取 1MB 已经宽裕得多。
        /// </summary>
        public const int MaxPayload = 1024 * 1024;

        /// <summary>把一条消息编码成完整帧字节。</summary>
        public static byte[] Encode(FrameKind kind, byte[] payload, int offset, int count)
        {
            if (count < 0) count = 0;
            var frame = new byte[HeaderSize + count];
            var length = count + 1; // 长度含类型字节
            frame[0] = (byte)(length & 0xFF);
            frame[1] = (byte)((length >> 8) & 0xFF);
            frame[2] = (byte)((length >> 16) & 0xFF);
            frame[3] = (byte)((length >> 24) & 0xFF);
            frame[4] = (byte)kind;
            if (count > 0 && payload != null)
            {
                Buffer.BlockCopy(payload, offset, frame, HeaderSize, count);
            }
            return frame;
        }

        public static byte[] EncodeText(string text)
        {
            var bytes = System.Text.Encoding.UTF8.GetBytes(text ?? string.Empty);
            return Encode(FrameKind.Text, bytes, 0, bytes.Length);
        }

        /// <summary>
        /// 一次读满 count 个字节。TCP 一次 Read 只保证「至少 1 字节」，
        /// 原来那些「读一次就当读全了」的地方在跨网段时会拿到半条数据。
        /// </summary>
        public static bool ReadExact(Stream stream, byte[] buffer, int offset, int count)
        {
            var done = 0;
            while (done < count)
            {
                var read = stream.Read(buffer, offset + done, count - done);
                if (read <= 0) return false;
                done += read;
            }
            return true;
        }
    }

    /// <summary>
    /// 按帧从流里读消息，负载复用同一块缓冲，音频路径上不产生垃圾。
    /// 不是线程安全的：一条连接一个实例，只在它自己的读线程上用。
    /// </summary>
    internal sealed class FrameReader
    {
        private readonly Stream stream;
        private readonly byte[] header = new byte[AudioFraming.HeaderSize];
        private readonly byte[] payload;

        /// <param name="stream">数据流。</param>
        /// <param name="payloadCapacity">负载缓冲大小。比它大的帧会被整帧读掉并丢弃，以保住流的位置。</param>
        public FrameReader(Stream stream, int payloadCapacity = 128 * 1024)
        {
            if (stream == null) throw new ArgumentNullException("stream");
            this.stream = stream;
            this.payload = new byte[payloadCapacity > 0 ? payloadCapacity : 128 * 1024];
        }

        /// <summary>负载缓冲。读到帧后，负载就在 <c>Buffer[0..count)</c>。</summary>
        public byte[] Buffer { get { return payload; } }

        /// <summary>
        /// 读下一条消息。返回 false 表示连接结束或流已经不可信（这时只能断开重连）。
        /// 帧比缓冲大时返回 true 但 count 为 0——整帧已经被读掉，流的位置仍然是对的。
        /// </summary>
        public bool ReadNext(out FrameKind kind, out int count)
        {
            kind = FrameKind.Text;
            count = 0;
            if (!AudioFraming.ReadExact(stream, header, 0, AudioFraming.HeaderSize)) return false;

            var length = header[0] | (header[1] << 8) | (header[2] << 16) | (header[3] << 24);
            var payloadLength = length - 1; // 长度含类型字节
            if (payloadLength < 0 || payloadLength > AudioFraming.MaxPayload)
            {
                // 站在流的中间，长度字段已经不可信，再读下去只会把垃圾当音频
                return false;
            }
            kind = (FrameKind)header[4];

            if (payloadLength == 0) return true; // 空负载，负载在缓冲里忽略

            if (payloadLength > payload.Length)
            {
                // 缓冲装不下：分段读掉并丢弃，保证流的位置对得上，只是这一帧内容不要了
                var skip = new byte[Math.Min(8192, payloadLength)];
                var left = payloadLength;
                while (left > 0)
                {
                    var want = Math.Min(skip.Length, left);
                    var read = stream.Read(skip, 0, want);
                    if (read <= 0) return false;
                    left -= read;
                }
                return true;
            }

            if (!AudioFraming.ReadExact(stream, payload, 0, payloadLength)) return false;
            count = payloadLength;
            return true;
        }

        /// <summary>把缓冲里的负载当成 UTF-8 文本取出来。</summary>
        public string ReadText(int count)
        {
            if (count <= 0) return string.Empty;
            return System.Text.Encoding.UTF8.GetString(payload, 0, count);
        }
    }
}
