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

        [Route(HttpVerbs.Post, "/add_player")]
        public HttpResult AddPlayer()
        {
            var ctx = HttpContext;
            var ip = ctx.Request.QueryString.Get("ip");
            var sourceDeviceID = ctx.Request.QueryString.Get("s_device");
            var targetDeviceID = ctx.Request.QueryString.Get("t_device");
            if (string.IsNullOrEmpty(targetDeviceID))
            {
                return new ResultError()
                {
                    Code = 500,
                    Message = "参数t_device(播放设备)不能为空"
                };
            }
            return new HttpResult() { Result = InitServer.playerControl.Add(sourceDeviceID, targetDeviceID, ip) };
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
                    Code = 500,
                    Message = "参数id不能为空"
                };
            }
            return new HttpResult() { Result = InitServer.playerControl.Start(id) };
        }

        [Route(HttpVerbs.Post, "/pause")]
        public HttpResult Pause()
        {
            var ctx = HttpContext;
            var id = ctx.Request.QueryString.Get("id");
            if (string.IsNullOrEmpty(id))
            {
                return new ResultError()
                {
                    Code = 500,
                    Message = "参数id不能为空"
                };
            }
            return new HttpResult()
            {
                Result = InitServer.playerControl.Stop(id)
            };
        }

        [Route(HttpVerbs.Post, "/del")]
        public HttpResult Del()
        {
            var ctx = HttpContext;
            var id = ctx.Request.QueryString.Get("id");
            if (string.IsNullOrEmpty(id))
            {
                return new ResultError()
                {
                    Code = 500,
                    Message = "参数id不能为空"
                };
            }
            return new HttpResult()
            {
                Result = InitServer.playerControl.Delete(id)
            };
        }
    }
}
