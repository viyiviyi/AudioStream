
using AudioStream.AudioServer;

namespace AudioStream
{
    internal static class InitServer
    {
        public static TcpServer tcpServer = new TcpServer();
        static HttpServer httpServer = new HttpServer();
        public static PlayerControl playerControl = new PlayerControl();
        public static void Init()
        {
            // 被拉取侧要能问「本机是不是正把网络音频播到这个设备上」，好拦住会把声音绕回去的点单
            tcpServer.NetworkPlaybackProbe = playerControl.IsPlayingRemoteAudioTo;
            tcpServer.StartAsync();
            httpServer.StartAsync();
        }

        public static void Stop()
        {
            playerControl.Dispose();
            tcpServer.Dispose();
            httpServer.Dispose();
        }
    }
}
