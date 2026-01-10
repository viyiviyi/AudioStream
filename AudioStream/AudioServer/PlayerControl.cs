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

        public List<PlayerInfo> GetPlayerInfoList()
        {
            return playerInfos.Select(a=>a.Copy()).ToList();
        }

        private void SavePlayers()
        {
            try
            {
                if (!File.Exists(Path.GetDirectoryName(ConfigFilePath)))
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(ConfigFilePath));
                }
                File.WriteAllText(ConfigFilePath, JsonConvert.SerializeObject(playerInfos), System.Text.Encoding.UTF8);
            }
            catch (Exception e)
            {
                Logger.Error("保存播放列表失败" + "\n" + e.Message + "\n" + e.StackTrace);
            }
        }

        public PlayerInfo Add(string SourceDeviceID, string TargetDeiceID, string IP = null, string SourceDeviceName = "", string TargetDeiceName = "")
        {
            var info = new PlayerInfo()
            {
                IP = IP,
                SourceDeviceID = SourceDeviceID,
                TargetDeiceID = TargetDeiceID,
                SourceDeviceName = SourceDeviceName,
                TargetDeiceName = TargetDeiceName,
                ID = Guid.NewGuid(),
                Index = players.Count + 1
            };
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

        public bool Stop(string guid)
        {
            if(!playerIdToMap.ContainsKey(Guid.Parse(guid))) return true;
            var player = playerIdToMap[Guid.Parse(guid)];
            if (player != null)
            {
                player.Dispose();
                players.Remove(player);
                playerIdToMap.Remove(Guid.Parse(guid));
                SavePlayers();
                return true;
            }
            return false;
        }

        public bool Delete(string guid)
        {
            var idx = playerInfos.FindIndex(a => a.ID == Guid.Parse(guid));
            if (idx >= 0)
            {
                playerInfos.RemoveAt(idx);
            }
            if (playerIdToMap.ContainsKey(Guid.Parse(guid)))
            {
                var player = playerIdToMap[Guid.Parse(guid)];
                if (player != null)
                {
                    player.Dispose();
                    players.Remove(player);
                    playerIdToMap.Remove(Guid.Parse(guid));
                }
            }
            SavePlayers();
            return true;
        }

        public void Dispose()
        {
            foreach (var item in players)
            {
                item.Dispose();
            }
            players.Clear();
            playerIdToMap.Clear();
        }
    }
}
