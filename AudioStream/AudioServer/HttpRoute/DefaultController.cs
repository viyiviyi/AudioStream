using AudioStream.AudioServer.Model;
using Common;
using Common.Helper;
using CSCore.CoreAudioAPI;
using EmbedIO;
using EmbedIO.Routing;
using EmbedIO.WebApi;
using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms.VisualStyles;
using static AudioStream.TcpServer;

namespace AudioStream.AudioServer.HttpRoute
{
    public class DefaultController : WebApiController
    {
        /// <summary>
        /// 本机身份与监听信息。界面上「本机」那一栏用它，用户也能照着这里给出的地址告诉对方该填什么。
        /// </summary>
        [Route(HttpVerbs.Get, "/info")]
        public HttpResult<object> Info()
        {
            return new HttpResult<object>
            {
                Result = new
                {
                    MachineId = MachineIdentity.Id,
                    PcName = MachineIdentity.Name,
                    TcpPort = InitServer.tcpServer.Port,
                    HttpPort = HttpServer.Port,
                    Version = typeof(DefaultController).Assembly.GetName().Version.ToString(),
                    Addresses = LocalIPv4Addresses()
                }
            };
        }

        /// <summary>本机可供对方填写的 IPv4 地址，跳过回环与未启用的网卡。</summary>
        private static List<string> LocalIPv4Addresses()
        {
            var result = new List<string>();
            try
            {
                foreach (var item in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (item.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (item.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                    foreach (var address in item.GetIPProperties().UnicastAddresses)
                    {
                        if (address.Address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) continue;
                        result.Add(address.Address.ToString());
                    }
                }
            }
            catch (Exception e)
            {
                Logger.Error("枚举本机地址失败：" + e.Message);
            }
            return result;
        }

        /// <summary>
        /// 本机全部音频端点：输出在前、输入在后，各自标注是不是系统默认。
        /// 对方填了本机 IP 之后，界面就是拿这个列表当「来源设备」备选的，所以输入设备也必须列出来——
        /// 只列输出的话，麦克风永远拉不到。
        /// </summary>
        [Route(HttpVerbs.Get, "/devices")]
        public HttpResult<List<DeviceInfo>> Devices()
        {
            var result = new HttpResult<List<DeviceInfo>>();
            result.Success = true;
            result.Result = new List<DeviceInfo>();

            var defaultOutputId = AudioDeviceHelper.GetDefaultOutputDeviceId();
            var defaultInputId = AudioDeviceHelper.GetDefaultInputDeviceId();

            result.Result.AddRange(CollectDevices(DataFlow.Render, "output", defaultOutputId));
            result.Result.AddRange(CollectDevices(DataFlow.Capture, "input", defaultInputId));
            return result;
        }

        /// <summary>某个方向的端点列表。一侧枚举失败不该把另一侧的设备也一起吞掉。</summary>
        private static List<DeviceInfo> CollectDevices(DataFlow flow, string flowName, string defaultDeviceId)
        {
            var devices = new List<DeviceInfo>();
            var isOutput = flow == DataFlow.Render;
            try
            {
                var collection = isOutput ? AudioDeviceHelper.OutputDevices() : AudioDeviceHelper.InputDevices();
                foreach (var item in collection)
                {
                    devices.Add(new DeviceInfo
                    {
                        Name = item.FriendlyName,
                        ID = item.DeviceID,
                        Default = item.DeviceID == defaultDeviceId,
                        Flow = flowName
                    });
                }
            }
            catch (Exception e)
            {
                Logger.Error("枚举" + (isOutput ? "输出" : "输入") + "设备失败：" + e.Message);
            }
            return devices;
        }

        /// <summary>
        /// 配置里配了哪些拉取。这一层只回答「配了什么」，
        /// 「实际在不在响」看 /play-status——两者不是一回事。
        /// </summary>
        [Route(HttpVerbs.Get, "/players")]
        public HttpResult<List<PlayerView>> Players()
        {
            var views = InitServer.playerControl.GetPlayerInfoList().Select(PlayerView.From).ToList();
            return new HttpResult<List<PlayerView>>() { Result = views };
        }

        [Route(HttpVerbs.Get, "/clients")]
        public HttpResult<List<ClientItem>> Clients()
        {
            return new HttpResult<List<ClientItem>>() { Result = InitServer.tcpServer.ClientItems() };
        }

        /// <summary>
        /// 每一路播放此刻的真实状态：到底在不在响、对端是谁、没响是为什么。
        /// 「播放列表」只说明配了什么，这个接口说明实际发生了什么。
        /// </summary>
        [Route(HttpVerbs.Get, "/play-status")]
        public HttpResult<List<PlayerStatus>> PlayStatus()
        {
            return new HttpResult<List<PlayerStatus>>() { Result = InitServer.playerControl.GetStatusList() };
        }

        /// <summary>
        /// 本机当前正在采集（也就是正被别人拉取）的设备号。
        /// 同一个设备被多路拉取时这里只出现一次——采集是一份，多路共享。
        /// </summary>
        [Route(HttpVerbs.Get, "/captures")]
        public HttpResult<List<string>> Captures()
        {
            return new HttpResult<List<string>>() { Result = InitServer.tcpServer.ActiveCaptureDeviceIds() };
        }

        /// <summary>
        /// 冲突自检：这个设备此刻会不会因为「本机正把网络音频播到它上面」而被拒绝点单。
        /// 界面拿它提前告诉用户哪个设备被占住了，排查时也用得着。
        /// </summary>
        [Route(HttpVerbs.Get, "/probe")]
        public HttpResult<object> Probe()
        {
            var deviceId = HttpContext.Request.QueryString.Get("device");
            var probe = InitServer.tcpServer.NetworkPlaybackProbe;
            var device = string.IsNullOrEmpty(deviceId) ? null : AudioDeviceHelper.GetDeviceById(deviceId);
            return new HttpResult<object>
            {
                Result = new
                {
                    Device = deviceId,
                    Resolved = device == null ? null : device.DeviceID,
                    Same = device != null && device.DeviceID == deviceId,
                    Wired = probe != null,
                    Blocked = probe != null && probe(deviceId)
                }
            };
        }

        /// <summary>
        /// 拉取授权现状：默认档、规则列表、此刻等人点头的申请。
        /// 界面轮询这一个接口就够——三样东西每一样都短，拆三个接口只会让界面多几次往返。
        /// </summary>
        [Route(HttpVerbs.Get, "/grants")]
        public HttpResult<object> Grants()
        {
            return new HttpResult<object>
            {
                Result = new
                {
                    DefaultAccess = PullAuthorization.AccessText(PullAuthorization.DefaultAccess),
                    Grants = PullAuthorization.Grants().Select(g => PullGrantView.From(g)).ToList(),
                    Pending = PullApprovalCenter.Pending()
                }
            };
        }

        /// <summary>
        /// 本机最近的日志，最新的在最前面。
        /// 界面上有个日志面板，排查「为什么没声音」时不用再去翻 %LocalAppData% 下的文件。
        /// </summary>
        [Route(HttpVerbs.Get, "/logs")]
        public HttpResult<object> Logs()
        {
            var raw = HttpContext.Request.QueryString.Get("lines");
            int lines;
            if (!int.TryParse(raw, out lines) || lines <= 0) lines = 200;
            var tail = Logger.Tail(lines);
            tail.Reverse();
            return new HttpResult<object>
            {
                Result = new
                {
                    File = Logger.LogFilePath,
                    Count = tail.Count,
                    Lines = tail
                }
            };
        }

        /// <summary>改全局默认档：没有任何规则命中的拉取按哪一档走。</summary>
        [Route(HttpVerbs.Post, "/grants/default")]
        public HttpResult<object> SetDefaultAccess()
        {
            var value = HttpContext.Request.QueryString.Get("value");
            var access = PullAuthorization.ParseAccess(value);
            if (access == null) return Fail("认不出的档位：" + value + "。可用：无需确认 / 需确认 / 永不允许");
            PullAuthorization.SetDefault(access.Value);
            return new HttpResult<object> { Result = PullAuthorization.AccessText(access.Value) };
        }

        /// <summary>
        /// 写一条授权规则。机器号或设备号留空表示「任意」——
        /// 「这台机器拉什么都行」只要一条规则，不必给每块设备各写一遍。
        /// </summary>
        [Route(HttpVerbs.Post, "/grants")]
        public async Task<HttpResult<object>> SaveGrant()
        {
            var request = await ReadBodyAsync<PullGrantRequest>();
            if (request == null) return Fail("请求内容为空");
            var access = PullAuthorization.ParseAccess(request.Access);
            if (access == null) return Fail("认不出的档位：" + request.Access + "。可用：无需确认 / 需确认 / 永不允许");
            var grant = PullAuthorization.Upsert(request.MachineId, request.PcName, request.DeviceId,
                request.DeviceName, access.Value);
            return new HttpResult<object> { Result = PullGrantView.From(grant) };
        }

        /// <summary>删一条授权规则。</summary>
        [Route(HttpVerbs.Post, "/grants/delete")]
        public HttpResult<object> DeleteGrant()
        {
            var id = HttpContext.Request.QueryString.Get("id");
            if (string.IsNullOrEmpty(id)) return Fail("参数id不能为空");
            if (!PullAuthorization.Remove(id)) return Fail("找不到这条规则：" + id);
            return new HttpResult<object> { Result = id };
        }

        /// <summary>
        /// 对一条待确认的拉取申请表态。remember 为真时顺手把它记成永久规则，
        /// 免得同一台机器下次来还要再点一遍。
        /// </summary>
        [Route(HttpVerbs.Post, "/grants/decide")]
        public HttpResult<object> DecideGrant()
        {
            var query = HttpContext.Request.QueryString;
            var id = query.Get("id");
            if (string.IsNullOrEmpty(id)) return Fail("参数id不能为空");
            var approved = !string.Equals(query.Get("approved"), "false", StringComparison.OrdinalIgnoreCase);
            var remember = string.Equals(query.Get("remember"), "true", StringComparison.OrdinalIgnoreCase);
            if (!PullApprovalCenter.Decide(id, approved, remember))
            {
                return Fail("这条申请已经过期或者已经被处理过了");
            }
            return new HttpResult<object> { Result = new { Id = id, Approved = approved, Remember = remember } };
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
            if (players.Any(a => (a.IP ?? "") == (ip ?? "") && a.SourceDeviceID == sourceDeviceID
                && a.Targets != null && a.Targets.Any(t => t.DeviceID == targetDeviceID)))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "请勿重复添加"
                };
            }
            var added = InitServer.playerControl.Add(sourceDeviceID, targetDeviceID, ip, sourceDeviceName, targetDeviceName);
            if (added == null)
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "这一路没能建起来，请检查设备是否可用"
                };
            }
            return new HttpResult() { Result = PlayerView.From(added) };
        }

        /// <summary>
        /// 新建一条拉取，请求体是 PlayerRequest 的 JSON。
        /// 相比老的 /add_player，这里能一次带上多个播放目标和一个播放策略——
        /// 「一条来源同时播到客厅和书房」「音箱为主、耳机备用」都是在这里配的。
        /// </summary>
        [Route(HttpVerbs.Post, "/players")]
        public async Task<HttpResult<object>> CreatePlayer()
        {
            var request = await ReadBodyAsync<PlayerRequest>();
            string error;
            if (!Validate(request, out error)) return Fail(error);
            var info = InitServer.playerControl.Add(request.IP, request.SourceDeviceID, request.SourceDeviceName,
                ParsePolicy(request.Policy), request.Targets);
            if (info == null) return Fail("这一路没能建起来，请检查设备是否可用");
            return new HttpResult<object> { Result = PlayerView.From(info) };
        }

        /// <summary>
        /// 改一条已有拉取的播放策略与目标设备。
        /// 正在响的这一路会按新配置重新起来，所以中途会断一下再响。
        /// </summary>
        [Route(HttpVerbs.Post, "/players/update")]
        public async Task<HttpResult<object>> UpdatePlayer()
        {
            var id = HttpContext.Request.QueryString.Get("id");
            if (string.IsNullOrEmpty(id)) return Fail("参数id不能为空");
            var request = await ReadBodyAsync<PlayerRequest>();
            string error;
            if (!Validate(request, out error)) return Fail(error);
            if (!InitServer.playerControl.Update(id, ParsePolicy(request.Policy), request.Targets))
            {
                return Fail("找不到这条拉取，或者没能按新的设备重新起来");
            }
            return new HttpResult<object> { Result = id };
        }

        /// <summary>配置界面提交过来的内容校验。设备号是拿来开声卡的，缺了没法往下走。</summary>
        private static bool Validate(PlayerRequest request, out string error)
        {
            error = null;
            if (request == null)
            {
                error = "请求内容为空";
                return false;
            }
            if (string.IsNullOrEmpty(request.SourceDeviceID))
            {
                error = "请选择来源音频设备";
                return false;
            }
            if (request.Targets == null || request.Targets.Count == 0)
            {
                error = "请选择播放音频设备";
                return false;
            }
            return true;
        }

        /// <summary>策略认不出来就按全播走——这是最不容易让人意外的默认。</summary>
        private static PlaybackPolicy ParsePolicy(string text)
        {
            PlaybackPolicy policy;
            if (!string.IsNullOrWhiteSpace(text) && Enum.TryParse(text, true, out policy)) return policy;
            return PlaybackPolicy.All;
        }

        /// <summary>
        /// 自己把请求体读出来交给 Newtonsoft 解析。
        /// 不用框架的请求反序列化：少依赖一层 EmbedIO 的版本差异，出错也更看得清。
        /// </summary>
        private async Task<T> ReadBodyAsync<T>() where T : class
        {
            string text;
            using (var reader = new StreamReader(HttpContext.OpenRequestStream(), Encoding.UTF8))
            {
                text = await reader.ReadToEndAsync();
            }
            if (string.IsNullOrWhiteSpace(text)) return null;
            return JsonConvert.DeserializeObject<T>(text);
        }

        /// <summary>
        /// 失败应答。ResultError 顶层就带 Message，界面直接读它。
        /// </summary>
        private static HttpResult<object> Fail(string message, int code = 500)
        {
            return new ResultError
            {
                Success = false,
                Code = code,
                Message = message
            };
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

        [Route(HttpVerbs.Post, "/volume")]
        public HttpResult Volume()
        {
            var ctx = HttpContext;
            var id = ctx.Request.QueryString.Get("id");
            var val = ctx.Request.QueryString.Get("val");
            if (string.IsNullOrEmpty(id))
            {
                return new ResultError()
                {
                    Success = false,
                    Code = 500,
                    Message = "参数id不能为空"
                };
            }
            InitServer.playerControl.SetVolume(id, float.Parse(val));
            return new HttpResult() { Result = InitServer.playerControl.GetVolume(id) };
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
                    Success = false,
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
                    Success = false,
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
