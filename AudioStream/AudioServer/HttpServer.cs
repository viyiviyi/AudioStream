
using AudioStream.AudioServer.HttpRoute;
using Common;
using EmbedIO;
using EmbedIO.WebApi;
using System;

namespace AudioStream
{
    public class HttpServer : IDisposable
    {
        static WebServer webServer = new WebServer();
        public HttpServer()
        {
            
        }
        public async void StartAsync()
        {
            try
            {
                webServer = new WebServer(o => o
                       .WithUrlPrefix("http://*:12570")
                       .WithMode(HttpListenerMode.EmbedIO))
                   .WithWebApi("/api", m => m.WithController<DefaultController>());
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
