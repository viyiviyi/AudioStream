
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
            // 被拉取侧要能问「本机是不是正把『这台机器送来的』音频播到这个设备上」，
            // 好拦住会把它自己的声音绕回去的点单。机器号是必须的：只看设备会把第三方也一起挡掉。
            tcpServer.NetworkPlaybackProbe = playerControl.IsLoopFor;
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
