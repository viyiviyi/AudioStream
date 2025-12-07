
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
