using Common;
using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace AudioStream.AudioServer
{
    public class PlayerControl : IDisposable
    {
        private readonly List<PlayerInfo> playerInfos = new List<PlayerInfo>();
        private readonly List<Player> players = new List<Player>();
        private readonly Dictionary<Guid, Player> playerIdToMap = new Dictionary<Guid, Player>();
        private readonly string ConfigFilePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "yiyiooo", "AudioStream", "players.json");
        public PlayerControl()
        {
            if (File.Exists(ConfigFilePath))
            {
                try
                {
                   var txt=  File.ReadAllText(ConfigFilePath, System.Text.Encoding.UTF8);
                    var list = JsonConvert.DeserializeObject<List<PlayerInfo>>(txt);
                    if (list != null && list.Count > 0)
                    {
                        foreach (var item in list)
                        {
                            // 老配置里只有单个目标设备字段，先补成目标列表再往下走
                            item.NormalizeTargets();
                            playerInfos.Add(item);
                        }
                        foreach (var item in playerInfos)
                        {
                            if (item.Play)
                            {
                                Start(item);
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    Logger.Error("读取播放列表失败" + "\n" + e.Message + "\n" + e.StackTrace);
                }
            }
        }

        public Player GetPlayer(string guid)
        {
            if (!playerIdToMap.ContainsKey(Guid.Parse(guid))) return null;
            return playerIdToMap[Guid.Parse(guid)];
        }

        /// <summary>
        /// 本机是否正把「从别的机器拉来的音频」播放到这个设备上。
        /// 被拉取侧靠它判断：采这个设备会不会把对方的声音又绕回去。
        /// 本机设备之间的流转不算——那不构成跨机环路；对端离线、播放没起来也不算。
        /// </summary>
        public bool IsPlayingRemoteAudioTo(string deviceId)
        {
            if (string.IsNullOrEmpty(deviceId)) return false;
            foreach (var player in players.ToArray())
            {
                if (player == null) continue;
                if (player.PlaysRemoteAudioTo(deviceId)) return true;
            }
            return false;
        }

        public List<PlayerInfo> GetPlayerInfoList()
        {
            return playerInfos.Select(a=>a.Copy()).ToList();
        }

        /// <summary>
        /// 每一路播放此刻的真实状态。
        /// 配置里有几条不等于几条在响：对端没开、被拒绝、声卡打不开都要能看出来。
        /// </summary>
        public List<Model.PlayerStatus> GetStatusList()
        {
            return players.ToArray().Select(a => a.GetStatus()).ToList();
        }

        private void SavePlayers()
        {
            try
            {
                if (!File.Exists(Path.GetDirectoryName(ConfigFilePath)))
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(ConfigFilePath));
                }
                // 落盘前统一走一遍兜底，保证写出去的永远是完整形态，不留半迁移状态
                foreach (var info in playerInfos)
                {
                    info.NormalizeTargets();
                }
                File.WriteAllText(ConfigFilePath, JsonConvert.SerializeObject(playerInfos), System.Text.Encoding.UTF8);
            }
            catch (Exception e)
            {
                Logger.Error("保存播放列表失败" + "\n" + e.Message + "\n" + e.StackTrace);
            }
        }

        /// <summary>
        /// 老的单目标入口，留着给还没改过来的调用方用。
        /// </summary>
        public PlayerInfo Add(string SourceDeviceID, string TargetDeiceID, string IP = null, string SourceDeviceName = "", string TargetDeiceName = "")
        {
            return Add(IP, SourceDeviceID, SourceDeviceName, PlaybackPolicy.All, new[]
            {
                new PlayTarget { DeviceID = TargetDeiceID, DeviceName = TargetDeiceName }
            });
        }

        /// <summary>
        /// 新建一条拉取。目标是列表：全播时它们全都出声，主备时按传入顺序当优先级，第一个最优先。
        /// </summary>
        public PlayerInfo Add(string ip, string sourceDeviceID, string sourceDeviceName, PlaybackPolicy policy, IEnumerable<PlayTarget> targets)
        {
            var list = DistinctTargets(targets);
            if (list.Count == 0) return null;
            var info = new PlayerInfo()
            {
                IP = ip,
                SourceDeviceID = sourceDeviceID,
                SourceDeviceName = sourceDeviceName,
                Policy = policy,
                ID = Guid.NewGuid(),
                Index = playerInfos.Count + 1
            };
            foreach (var target in list)
            {
                info.Targets.Add(target);
            }
            if (Start(info))
            {
                return info;
            }
            else
            {
                Delete(info.ID.ToString());
                return null;
            }
        }

        /// <summary>
        /// 改一条已有记录的播放策略与目标设备。
        /// 这一路本来在跑就先收掉再按新配置起来，否则用户改完要等下次重启才生效。
        /// </summary>
        public bool Update(string guid, PlaybackPolicy policy, IEnumerable<PlayTarget> targets)
        {
            var id = Guid.Parse(guid);
            var info = playerInfos.FirstOrDefault(a => a.ID == id);
            if (info == null) return false;
            var list = DistinctTargets(targets);
            if (list.Count == 0) return false;
            info.Policy = policy;
            info.Targets.Clear();
            foreach (var target in list)
            {
                info.Targets.Add(target);
            }
            if (info.Play)
            {
                Start(info);
            }
            else
            {
                SavePlayers();
            }
            return true;
        }

        /// <summary>
        /// 同一块设备在一路里只能出现一次：重复了会开出两块一样的输出，声音直接翻倍。
        /// 顺带按传入顺序把优先级重排成 0、1、2……，界面里排出来的顺序就是主备顺序。
        /// </summary>
        private static List<PlayTarget> DistinctTargets(IEnumerable<PlayTarget> targets)
        {
            var result = new List<PlayTarget>();
            if (targets == null) return result;
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var target in targets)
            {
                if (target == null) continue;
                var key = string.IsNullOrEmpty(target.DeviceID) ? ("name:" + target.DeviceName) : target.DeviceID;
                if (string.IsNullOrEmpty(target.DeviceID) && string.IsNullOrEmpty(target.DeviceName)) continue;
                if (!seen.Add(key)) continue;
                result.Add(new PlayTarget
                {
                    DeviceID = target.DeviceID,
                    DeviceName = target.DeviceName,
                    Priority = result.Count
                });
            }
            return result;
        }
        public void SetVolume(string guid, float Volume)
        {
            GetPlayer(guid)?.SetVolume(Volume);
            SavePlayers();
        }

        public float GetVolume(string guid)
        {
            return GetPlayer(guid)?.GetVolume() ?? 1;
        }
        public bool Start(string guid)
        {
            var info = playerInfos.FirstOrDefault(a => a.ID == Guid.Parse(guid));
            if (info != null)
            {
                if (Start(info))
                    return true;
            }
            return false;
        }

        private bool Start(PlayerInfo info)
        {
            try
            {
                if (!playerInfos.Any(a => a.ID == info.ID))
                    playerInfos.Add(info);
                // 同一条记录再点一次「播放」时，先把上一份收掉。
                // 不然旧的那份还在后台重连，声卡上就会出现两份一模一样的播放。
                RemovePlayer(info.ID);
                var player = new Player(info);
                playerIdToMap[player.ID] = player;
                players.Add(player);
                player.Start();
                SavePlayers();
                return true;
            }
            catch (Exception e)
            {
                Logger.Error("播放失败", e);
                return false;
            }
        }

        /// <summary>把一个还在跑的播放实例摘掉并释放。没有就什么都不做。</summary>
        private void RemovePlayer(Guid id)
        {
            if (!playerIdToMap.ContainsKey(id)) return;
            var player = playerIdToMap[id];
            playerIdToMap.Remove(id);
            players.Remove(player);
            if (player != null) player.Dispose();
        }

        public bool Stop(string guid)
        {
            var id = Guid.Parse(guid);
            if (!playerIdToMap.ContainsKey(id)) return true;
            RemovePlayer(id);
            SavePlayers();
            return true;
        }

        public bool Delete(string guid)
        {
            var id = Guid.Parse(guid);
            var idx = playerInfos.FindIndex(a => a.ID == id);
            if (idx >= 0)
            {
                playerInfos.RemoveAt(idx);
            }
            RemovePlayer(id);
            SavePlayers();
            return true;
        }

        public void Dispose()
        {
            // 逐个收工，收的过程中它会把自己从这几个集合里摘出去，所以先取一份快照再遍历
            foreach (var item in players.ToArray())
            {
                RemovePlayer(item.ID);
            }
            players.Clear();
            playerIdToMap.Clear();
        }
    }
}
