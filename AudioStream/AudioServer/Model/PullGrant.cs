using Newtonsoft.Json;
using Newtonsoft.Json.Converters;
using System;

namespace AudioStream.AudioServer
{
    /// <summary>
    /// 本机允许别人怎么拉自己的设备。三档，语义互斥：
    /// 直接放行 / 每次问一次 / 永不允许。
    /// </summary>
    public enum PullAccess
    {
        /// <summary>不用问，直接拉走。</summary>
        Allow,

        /// <summary>下回拉的时候在配置页上问一次，批准了才给。</summary>
        Ask,

        /// <summary>永不允许，连接就地断掉。</summary>
        Deny
    }

    /// <summary>
    /// 一条授权规则：某台机器（或任意机器）拉本机某个设备（或任意设备）时的档位。
    /// 机器号与设备号留空表示「任意」，这是留着写粗粒度规则用的——
    /// 比如「我信任书房那台机器，它想拉哪个设备都放行」只要一条规则。
    /// </summary>
    public class PullGrant
    {
        /// <summary>规则自身的标识，界面上删除它用。</summary>
        public string Id { get; set; }

        /// <summary>对端的本机身份。空或 <c>*</c> 表示任意机器。</summary>
        public string MachineId { get; set; }

        /// <summary>对端的计算机名，只用于显示。</summary>
        public string PcName { get; set; }

        /// <summary>本机设备号。空或 <c>*</c> 表示任意设备。</summary>
        public string DeviceId { get; set; }

        /// <summary>本机设备名，只用于显示。</summary>
        public string DeviceName { get; set; }

        /// <summary>这一档的许可。</summary>
        [JsonConverter(typeof(StringEnumConverter))]
        public PullAccess Access { get; set; }

        /// <summary>最后一次改动时间。同一优先级的多条规则冲突时，取最新的那条。</summary>
        public DateTime UpdatedAt { get; set; }
    }

    /// <summary>
    /// 给界面看的授权规则。档位用中文字符串给出——
    /// 接口那层不认枚举转换特性，直接把枚举丢出去会变成数字。
    /// </summary>
    public class PullGrantView
    {
        public string Id { get; set; }
        public string MachineId { get; set; }
        public string PcName { get; set; }
        public string DeviceId { get; set; }
        public string DeviceName { get; set; }
        public string Access { get; set; }
        public string Scope { get; set; }
        public DateTime UpdatedAt { get; set; }

        public static PullGrantView From(PullGrant grant)
        {
            if (grant == null) return null;
            var anyMachine = PullAuthorization.IsAny(grant.MachineId);
            var anyDevice = PullAuthorization.IsAny(grant.DeviceId);
            return new PullGrantView
            {
                Id = grant.Id,
                MachineId = grant.MachineId,
                PcName = grant.PcName,
                DeviceId = grant.DeviceId,
                DeviceName = grant.DeviceName,
                Access = PullAuthorization.AccessText(grant.Access),
                Scope = anyMachine && anyDevice ? "所有机器 / 所有设备"
                    : anyMachine ? "所有机器 / " + (grant.DeviceName ?? grant.DeviceId)
                    : anyDevice ? (grant.PcName ?? grant.MachineId) + " / 所有设备"
                    : (grant.PcName ?? grant.MachineId) + " / " + (grant.DeviceName ?? grant.DeviceId),
                UpdatedAt = grant.UpdatedAt
            };
        }
    }

    /// <summary>
    /// 配置界面提交上来的一条授权规则。
    /// 档位用文字传，不用枚举：界面说的是「需确认」，让它在服务端解析成枚举，比让界面猜数字稳。
    /// </summary>
    public class PullGrantRequest
    {
        /// <summary>对端机器身份，留空表示任意机器。</summary>
        public string MachineId { get; set; }

        /// <summary>对端计算机名，只用于显示。</summary>
        public string PcName { get; set; }

        /// <summary>本机设备号，留空表示任意设备。</summary>
        public string DeviceId { get; set; }

        /// <summary>本机设备名，只用于显示。</summary>
        public string DeviceName { get; set; }

        /// <summary>档位文字：无需确认 / 需确认 / 永不允许。</summary>
        public string Access { get; set; }
    }
}
