using System;

namespace AudioStream.AudioServer
{
    /// <summary>
    /// 「音频通路本身是通的，只是这块播放设备开不出来」。
    ///
    /// 主备策略要靠它区分两类失败：打不开声卡说明可以换下一块候选试试，
    /// 而连不上对端、被对端拒绝这类失败，换多少块声卡都没用，应当立刻停下把原因报给用户。
    /// 光靠异常消息文本去猜属于哪一类太脆，所以单独立一个类型。
    /// </summary>
    internal class PlaybackDeviceException : Exception
    {
        public PlaybackDeviceException(string message, Exception innerException)
            : base(message, innerException)
        {
        }
    }
}
