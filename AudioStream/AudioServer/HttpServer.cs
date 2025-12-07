
using AudioStream.AudioServer.HttpRoute;
using Common;
using EmbedIO;
using EmbedIO.WebApi;
using System;
using System.IO;
using System.Reflection;
using System.Threading.Tasks;

namespace AudioStream
{
    public class HttpServer : IDisposable
    {
        static WebServer webServer = new WebServer();
        static int _port = 12570;
        public HttpServer()
        {
            
        }
        public async void StartAsync()
        {
            try
            {
                webServer = new WebServer(o => o
                       .WithUrlPrefix("http://*:" + _port)
                       .WithMode(HttpListenerMode.EmbedIO))
                    .WithCors("*")
                   .WithWebApi("/api", m => m.WithController<DefaultController>())
                   .WithStaticFolder("/", "web", false);
                await webServer.RunAsync();
                Logger.Info("Http服务启动");

            }
            catch (Exception ex)
            {
                Logger.Error(ex.Message, ex);
            }

        }
        public void Dispose()
        {
            webServer.Dispose();
        }
    }
}
