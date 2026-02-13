using AudioStream.AudioServer.Model;
using Common.Helper;
using EmbedIO;
using EmbedIO.Routing;
using EmbedIO.WebApi;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms.VisualStyles;
using static AudioStream.TcpServer;

namespace AudioStream.AudioServer.HttpRoute
{
    public class DefaultController : WebApiController
    {
        [Route(HttpVerbs.Get, "/devices")]
        public HttpResult<List<DeviceInfo>> Devices()
        {
            var result = new HttpResult<List<DeviceInfo>>();
            result.Success = true;
            result.Result = new List<DeviceInfo>();
            var defaultDeviceID = AudioDeviceHelper.GetDefaultOutputDeviceId();
            foreach (var item in AudioDeviceHelper.OutputDevices())
            {
                result.Result.Add(new DeviceInfo
                {
                    Name = item.FriendlyName,
                    ID = item.DeviceID,
                    Default = item.DeviceID == defaultDeviceID
                });
            }
            return result;
        }

        [Route(HttpVerbs.Get, "/players")]
        public HttpResult<List<PlayerInfo>> Players()
        {
            return new HttpResult<List<PlayerInfo>>() { Result= InitServer.playerControl.GetPlayerInfoList() };
        }

        [Route(HttpVerbs.Get, "/clients")]
        public HttpResult<List<ClientItem>> Clients()
        {
            // 返回TCP和UDP客户端列表
            var tcpClients = InitServer.tcpServer.ClientItems();
            var udpClients = InitServer.udpServer.ClientItems();
            var allClients = new List<ClientItem>();
            allClients.AddRange(tcpClients);
            allClients.AddRange(udpClients);
            return new HttpResult<List<ClientItem>>() { Result = allClients };
        }

        [Route(HttpVerbs.Post, "/add_player")]
        public HttpResult AddPlayer()
        {
            var ctx = HttpContext;
            var ip = ctx.Request.QueryString.Get("ip");
            var sourceDeviceID = ctx.Request.QueryString.Get("s_device");
            var targetDeviceID = ctx.Request.QueryString.Get("t_device");
            var sourceDeviceName = ctx.Request.QueryString.Get("s_device_name");
            var targetDeviceName = ctx.Request.QueryString.Get("t_device_name");
            var useUdpStr = ctx.Request.QueryString.Get("use_udp");
            bool useUdp = false;
            
            if (!string.IsNullOrEmpty(useUdpStr))
            {
                bool.TryParse(useUdpStr, out useUdp);
            }
            
            if (string.IsNullOrEmpty(targetDeviceID))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数t_device(播放设备)不能为空"
                };
            }
            if (string.IsNullOrEmpty(sourceDeviceID))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数s_device(播放设备)不能为空"
                };
            }
            var players = InitServer.playerControl.GetPlayerInfoList();
            if (players.Any(a => a.IP == ip && a.TargetDeiceID == targetDeviceID && a.SourceDeviceID == sourceDeviceID))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "请勿重复添加"
                };
            }
            
            // 修改Add方法以支持useUdp参数
            var playerInfo = InitServer.playerControl.Add(sourceDeviceID, targetDeviceID, ip, sourceDeviceName, targetDeviceName);
            if (playerInfo != null)
            {
                playerInfo.UseUdp = useUdp;
            }
            return new HttpResult() { Result = playerInfo };
        }

        [Route(HttpVerbs.Post, "/play")]
        public HttpResult Play()
        {
            var ctx = HttpContext;
            var id = ctx.Request.QueryString.Get("id");
            if (string.IsNullOrEmpty(id))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数id不能为空"
                };
            }
            return new HttpResult() { Result = InitServer.playerControl.Start(id) };
        }

        [Route(HttpVerbs.Post, "/stop")]
        public HttpResult Stop()
        {
            var ctx = HttpContext;
            var id = ctx.Request.QueryString.Get("id");
            if (string.IsNullOrEmpty(id))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数id不能为空"
                };
            }
            return new HttpResult() { Result = InitServer.playerControl.Stop(id) };
        }

        [Route(HttpVerbs.Post, "/delete")]
        public HttpResult Delete()
        {
            var ctx = HttpContext;
            var id = ctx.Request.QueryString.Get("id");
            if (string.IsNullOrEmpty(id))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数id不能为空"
                };
            }
            return new HttpResult() { Result = InitServer.playerControl.Delete(id) };
        }

        [Route(HttpVerbs.Post, "/set_volume")]
        public HttpResult SetVolume()
        {
            var ctx = HttpContext;
            var id = ctx.Request.QueryString.Get("id");
            var volumeStr = ctx.Request.QueryString.Get("volume");
            if (string.IsNullOrEmpty(id))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数id不能为空"
                };
            }
            if (string.IsNullOrEmpty(volumeStr))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数volume不能为空"
                };
            }
            if (!float.TryParse(volumeStr, out float volume))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数volume格式错误"
                };
            }
            InitServer.playerControl.SetVolume(id, volume);
            return new HttpResult() { Result = true };
        }

        [Route(HttpVerbs.Get, "/get_volume")]
        public HttpResult<float> GetVolume()
        {
            var ctx = HttpContext;
            var id = ctx.Request.QueryString.Get("id");
            if (string.IsNullOrEmpty(id))
            {
                return new HttpResult<float>() { Success = false, Code = 500, Message = "参数id不能为空" };
            }
            return new HttpResult<float>() { Result = InitServer.playerControl.GetVolume(id) };
        }

        [Route(HttpVerbs.Get, "/set_udp")]
        public HttpResult SetUdp()
        {
            var ctx = HttpContext;
            var id = ctx.Request.QueryString.Get("id");
            var useUdpStr = ctx.Request.QueryString.Get("use_udp");
            
            if (string.IsNullOrEmpty(id))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数id不能为空"
                };
            }
            
            bool useUdp = false;
            if (!string.IsNullOrEmpty(useUdpStr))
            {
                bool.TryParse(useUdpStr, out useUdp);
            }
            
            // 获取播放器并更新UDP设置
            var player = InitServer.playerControl.GetPlayer(id);
            if (player != null)
            {
                // 这里需要重新启动播放器以应用UDP设置
                // 先停止
                InitServer.playerControl.Stop(id);
                
                // 获取播放器信息并更新UseUdp
                var players = InitServer.playerControl.GetPlayerInfoList();
                var playerInfo = players.FirstOrDefault(p => p.ID.ToString() == id);
                if (playerInfo != null)
                {
                    playerInfo.UseUdp = useUdp;
                    // 重新启动
                    InitServer.playerControl.Start(id);
                }
            }
            
            return new HttpResult() { Result = true };
        }
    }
}