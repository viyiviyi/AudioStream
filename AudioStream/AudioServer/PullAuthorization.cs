using AudioStream.AudioServer.Model;
using Common;
using Newtonsoft.Json;
using Newtonsoft.Json.Converters;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace AudioStream.AudioServer
{
    /// <summary>
    /// 拉取授权的判定中心：本机决定「谁能拉我的哪个设备」。
    ///
    /// 判定是规则叠加出来的，不是一张平表：
    /// 先按「机器号 + 设备号」匹配到最具体的那条规则（精确机器精确设备 ＞ 精确机器任意设备
    /// ＞ 任意机器精确设备 ＞ 任意机器任意设备），都没匹配上才落到全局默认档。
    /// 这样既能写「这台机器可以拉这个麦克风」这种细规则，也能写「我信这台机器」这种粗规则。
    ///
    /// 规则落盘在 grants.json，和身份文件同目录。读不到就当没有规则——
    /// 授权坏了不该把音频通路整个拦住，退回默认档即可。
    /// </summary>
    internal static class PullAuthorization
    {
        /// <summary>规则里代表「任意」的写法。空串按任意处理。</summary>
        public const string AnyToken = "*";

        private static readonly object SyncRoot = new object();
        private static bool _loaded;
        private static PullAccess _defaultAccess = PullAccess.Allow;
        private static List<PullGrant> _grants = new List<PullGrant>();

        /// <summary>
        /// 没有规则命中时按哪一档走。
        /// 出厂是 Allow：升上来的老用户不能因为多了一条授权逻辑就突然全被拦住，
        /// 想收紧的人在配置页上把默认档改成「需确认」或「永不允许」即可。
        /// </summary>
        public static PullAccess DefaultAccess
        {
            get
            {
                EnsureLoaded();
                lock (SyncRoot) { return _defaultAccess; }
            }
        }

        /// <summary>规则文件路径，和身份文件放在同一个目录下。</summary>
        public static string ConfigFilePath
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "yiyiooo",
                    "AudioStream",
                    "grants.json");
            }
        }

        /// <summary>档位的中文名字，界面与日志共用一处，避免两边说法不一致。</summary>
        public static string AccessText(PullAccess access)
        {
            switch (access)
            {
                case PullAccess.Allow: return "无需确认";
                case PullAccess.Ask: return "需确认";
                case PullAccess.Deny: return "永不允许";
                default: return access.ToString();
            }
        }

        /// <summary>把界面传上来的档位文字解析回枚举，认不出来时返回 null。</summary>
        public static PullAccess? ParseAccess(string text)
        {
            if (string.IsNullOrWhiteSpace(text)) return null;
            var value = text.Trim();
            if (string.Equals(value, "无需确认", StringComparison.Ordinal)) return PullAccess.Allow;
            if (string.Equals(value, "需确认", StringComparison.Ordinal)) return PullAccess.Ask;
            if (string.Equals(value, "永不允许", StringComparison.Ordinal)) return PullAccess.Deny;
            PullAccess parsed;
            if (Enum.TryParse(value, true, out parsed)) return parsed;
            return null;
        }

        /// <summary>空串与 <c>*</c> 都表示「任意」。</summary>
        public static bool IsAny(string value)
        {
            return string.IsNullOrWhiteSpace(value) || value.Trim() == AnyToken;
        }

        /// <summary>当前的规则列表（副本，外部改不动内部状态）。</summary>
        public static List<PullGrant> Grants()
        {
            EnsureLoaded();
            lock (SyncRoot) { return _grants.Select(Clone).ToList(); }
        }

        /// <summary>改全局默认档。</summary>
        public static void SetDefault(PullAccess access)
        {
            EnsureLoaded();
            lock (SyncRoot) { _defaultAccess = access; }
            Save();
        }

        /// <summary>
        /// 写一条规则。机器号 + 设备号完全相同的已有规则会被就地改掉，不再堆第二条——
        /// 否则界面上会积出一串互相矛盾的重复项，判定时到底听谁的也说不清。
        /// </summary>
        public static PullGrant Upsert(string machineId, string pcName, string deviceId, string deviceName, PullAccess access)
        {
            EnsureLoaded();
            var machine = Normalize(machineId);
            var device = Normalize(deviceId);
            PullGrant result;
            lock (SyncRoot)
            {
                var existing = _grants.FirstOrDefault(g => Same(g.MachineId, machine) && Same(g.DeviceId, device));
                if (existing == null)
                {
                    existing = new PullGrant { Id = Guid.NewGuid().ToString("N") };
                    _grants.Add(existing);
                }
                // 姓名、设备名只用于显示，规则没给名字时不要把已有的名字抹成空
                existing.MachineId = machine;
                existing.PcName = string.IsNullOrWhiteSpace(pcName) ? existing.PcName : pcName.Trim();
                existing.DeviceId = device;
                existing.DeviceName = string.IsNullOrWhiteSpace(deviceName) ? existing.DeviceName : deviceName.Trim();
                existing.Access = access;
                existing.UpdatedAt = DateTime.Now;
                result = Clone(existing);
            }
            Save();
            return result;
        }

        /// <summary>按规则号删一条。返回是否真的删掉了。</summary>
        public static bool Remove(string id)
        {
            EnsureLoaded();
            if (string.IsNullOrWhiteSpace(id)) return false;
            bool removed;
            lock (SyncRoot)
            {
                removed = _grants.RemoveAll(g => string.Equals(g.Id, id.Trim(), StringComparison.OrdinalIgnoreCase)) > 0;
            }
            if (removed) Save();
            return removed;
        }

        /// <summary>
        /// 判定这次拉取该按哪一档处理。
        /// machineId 为空（对端没报身份）时只可能命中「任意机器」的规则，
        /// 不会误撞到某台具体机器的规则上。
        /// </summary>
        public static PullAccess Decide(string machineId, string deviceId)
        {
            EnsureLoaded();
            lock (SyncRoot)
            {
                PullGrant best = null;
                var bestLevel = 0;
                foreach (var grant in _grants)
                {
                    var level = MatchLevel(grant, machineId, deviceId);
                    if (level == 0) continue;
                    if (level > bestLevel ||
                        (level == bestLevel && best != null && grant.UpdatedAt >= best.UpdatedAt))
                    {
                        best = grant;
                        bestLevel = level;
                    }
                }
                if (best == null) return _defaultAccess;
                return best.Access;
            }
        }

        /// <summary>匹配的粗细程度：数字越大越具体，0 表示这条规则管不到这次拉取。</summary>
        private static int MatchLevel(PullGrant grant, string machineId, string deviceId)
        {
            var anyMachine = IsAny(grant.MachineId);
            var anyDevice = IsAny(grant.DeviceId);
            if (!anyMachine && !string.Equals(grant.MachineId, machineId, StringComparison.OrdinalIgnoreCase)) return 0;
            if (!anyDevice && !string.Equals(grant.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase)) return 0;
            if (!anyMachine && !anyDevice) return 4;
            if (!anyMachine) return 3;   // 认机器、不认设备
            if (!anyDevice) return 2;    // 认设备、不认机器
            return 1;                    // 全放开的粗规则
        }

        private static string Normalize(string value)
        {
            return IsAny(value) ? AnyToken : value.Trim();
        }

        private static bool Same(string a, string b)
        {
            return string.Equals(Normalize(a ?? string.Empty), Normalize(b ?? string.Empty), StringComparison.OrdinalIgnoreCase);
        }

        private static PullGrant Clone(PullGrant grant)
        {
            return new PullGrant
            {
                Id = grant.Id,
                MachineId = grant.MachineId,
                PcName = grant.PcName,
                DeviceId = grant.DeviceId,
                DeviceName = grant.DeviceName,
                Access = grant.Access,
                UpdatedAt = grant.UpdatedAt
            };
        }

        private static void EnsureLoaded()
        {
            if (_loaded) return;
            lock (SyncRoot)
            {
                if (_loaded) return;
                var file = ReadFile();
                if (file != null)
                {
                    _defaultAccess = file.DefaultAccess;
                    _grants = file.Grants ?? new List<PullGrant>();
                    foreach (var grant in _grants)
                    {
                        if (string.IsNullOrWhiteSpace(grant.Id)) grant.Id = Guid.NewGuid().ToString("N");
                    }
                }
                _loaded = true;
            }
        }

        private static GrantFile ReadFile()
        {
            try
            {
                if (!File.Exists(ConfigFilePath)) return null;
                var text = File.ReadAllText(ConfigFilePath, System.Text.Encoding.UTF8);
                if (string.IsNullOrWhiteSpace(text)) return null;
                return JsonConvert.DeserializeObject<GrantFile>(text);
            }
            catch (Exception e)
            {
                Logger.Error("读取拉取授权失败，本次按默认档处理：" + e.Message);
                return null;
            }
        }

        private static void Save()
        {
            lock (SyncRoot)
            {
                try
                {
                    var dir = Path.GetDirectoryName(ConfigFilePath);
                    if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                    {
                        Directory.CreateDirectory(dir);
                    }
                    var file = new GrantFile { DefaultAccess = _defaultAccess, Grants = _grants };
                    File.WriteAllText(ConfigFilePath, JsonConvert.SerializeObject(file, Formatting.Indented), System.Text.Encoding.UTF8);
                }
                catch (Exception e)
                {
                    Logger.Error("保存拉取授权失败，本次改动重启后会丢：" + e.Message);
                }
            }
        }

        private class GrantFile
        {
            /// <summary>写成名字而不是数字，规则文件是人会打开看的东西。</summary>
            [JsonConverter(typeof(StringEnumConverter))]
            public PullAccess DefaultAccess { get; set; }
            public List<PullGrant> Grants { get; set; }
        }
    }
}
