using AudioStream.AudioServer;

namespace AudioStream
{
    internal static class InitServer
    {
        public static TcpServer tcpServer = new TcpServer();
        public static UdpServer udpServer = new UdpServer();
        static HttpServer httpServer = new HttpServer();
        public static PlayerControl playerControl = new PlayerControl();
        
        public static void Init()
        {
            tcpServer.StartAsync();
            udpServer.StartAsync();
            httpServer.StartAsync();
        }

        public static void Stop()
        {
            playerControl.Dispose();
            tcpServer.Dispose();
            udpServer.Dispose();
            httpServer.Dispose();
        }
    }
}