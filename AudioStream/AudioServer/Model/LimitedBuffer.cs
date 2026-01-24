using System;
using System.Threading;
using System.Threading.Tasks;

namespace AudioStream.AudioServer.Model
{
    /// <summary>
    /// 带大小限制的BUff
    /// </summary>
    public class LimitedBuffer
    {
        private readonly byte[] _buffer;
        private readonly int _maxSize;
        private int _readPosition;
        private int _writePosition;
        private int _dataLength;
        private readonly object _lockObject = new object();

        /// <summary>
        /// 环形缓冲
        /// </summary>
        /// <param name="maxSize">最大缓冲区大小（字节）</param>
        public LimitedBuffer(int maxSize)
        {
            _maxSize = maxSize > 0 ? maxSize : throw new ArgumentException("大小必须大于0", nameof(maxSize));
            _buffer = new byte[maxSize];

        }

        /// <summary>
        /// 写入数据到环形缓冲区
        /// </summary>
        public void WriteToCircularBuffer(byte[] data, int offset, int count)
        {
            lock (_lockObject)
            {
                var dataCount = Math.Min(_maxSize, count);
                if (_writePosition + dataCount <= _maxSize)
                {
                    // 不需要环绕
                    Buffer.BlockCopy(data, offset, _buffer, _writePosition, dataCount);
                    _writePosition += dataCount;
                }
                else
                {
                    // 如果写入数据大于最大数据，仅存最后的数据
                    if (count > _maxSize)
                    {
                        offset = count - _maxSize;
                    }
                    // 需要环绕
                    int firstPart = _maxSize - _writePosition;
                    Buffer.BlockCopy(data, offset, _buffer, _writePosition, firstPart);
                    Buffer.BlockCopy(data, offset + firstPart, _buffer, 0, dataCount - firstPart);
                    _writePosition = dataCount - firstPart;
                    // 如果写入的数据超过了读取未知，将读取位置后移
                    if (_writePosition > _readPosition) _readPosition = _writePosition;
                }

                // 如果写入数据大于最大数据，仅存最后的数据
                _dataLength += dataCount;
            }
        }

        /// <summary>
        /// 从缓冲区读取数据
        /// </summary>
        public int Read(byte[] buffer, int offset, int count)
        {
            lock (_lockObject)
            {
                if (_dataLength == 0)
                    return 0;

                int bytesToRead = Math.Min(count, _dataLength);

                if (_readPosition + bytesToRead <= _maxSize)
                {
                    // 不需要环绕
                    Buffer.BlockCopy(_buffer, _readPosition, buffer, offset, bytesToRead);
                    _readPosition += bytesToRead;
                }
                else
                {
                    // 需要环绕
                    int firstPart = _maxSize - _readPosition;
                    Buffer.BlockCopy(_buffer, _readPosition, buffer, offset, firstPart);
                    Buffer.BlockCopy(_buffer, 0, buffer, offset + firstPart, bytesToRead - firstPart);
                    _readPosition = bytesToRead - firstPart;
                }

                _dataLength -= bytesToRead;
                return bytesToRead;
            }
        }

        /// <summary>
        /// 异步读取数据
        /// </summary>
        public async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
        {
            lock (_lockObject)
            {
                if (_dataLength == 0)
                    return 0;

                int bytesToRead = Math.Min(count, _dataLength);

                if (_readPosition + bytesToRead <= _maxSize)
                {
                    Buffer.BlockCopy(_buffer, _readPosition, buffer, offset, bytesToRead);
                    _readPosition += bytesToRead;
                }
                else
                {
                    int firstPart = _maxSize - _readPosition;
                    Buffer.BlockCopy(_buffer, _readPosition, buffer, offset, firstPart);
                    Buffer.BlockCopy(_buffer, 0, buffer, offset + firstPart, bytesToRead - firstPart);
                    _readPosition = bytesToRead - firstPart;
                }

                _dataLength -= bytesToRead;
                return bytesToRead;
            }
        }
        /// <summary>
        /// 获取当前缓冲区中的数据量
        /// </summary>
        public int AvailableData
        {
            get
            {
                lock (_lockObject)
                {
                    return _dataLength;
                }
            }
        }

    }

}
