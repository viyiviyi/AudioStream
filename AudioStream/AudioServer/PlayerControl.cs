using Common;
using System;
using System.Collections.Generic;
using System.Linq;

namespace AudioStream.AudioServer
{
    public class PlayerControl : IDisposable
    {
        private readonly List<PlayerInfo> playerInfos = new List<PlayerInfo>();
        private readonly List<Player> players = new List<Player>();
        private readonly Dictionary<Guid, Player> playerIdToMap = new Dictionary<Guid, Player>();

        public Player GetPlayer(string guid)
        {
            return playerIdToMap[Guid.Parse(guid)];
        }

        public List<PlayerInfo> GetPlayerInfoList()
        {
            return playerInfos.Select(a=>a.Copy()).ToList();
        }

        public PlayerInfo Add(string SourceDeviceID,string TargetDeiceID,string IP = null)
        {
            var info = new PlayerInfo()
            {
                IP = IP,
                SourceDeviceID = SourceDeviceID,
                TargetDeiceID = TargetDeiceID,
                ID = Guid.NewGuid(),
                Index = players.Count + 1
            };
            if (Start(info))
                return info;
            return null;
        }
        public void SetVolume(string guid, float Volume)
        {
            GetPlayer(guid)?.SetVolume(Volume);
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
                playerInfos.Add(info);
                var player = new Player(info);
                playerIdToMap[player.ID] = player;
                players.Add(player);
                player.Start();
                return true;
            }
            catch (Exception e)
            {
                Logger.Error("播放失败",e);
                return false;
            }
        }

        public bool Stop(string guid)
        {
            var player = playerIdToMap[Guid.Parse(guid)];
            if (player != null)
            {
                player.Dispose();
                players.Remove(player);
                playerIdToMap.Remove(Guid.Parse(guid));
                return true;
            }
            return false;
        }

        public bool Delete(string guid)
        {
            var player = playerIdToMap[Guid.Parse(guid)];
            if (player != null)
            {
                player.Dispose();
                players.Remove(player);
                playerIdToMap.Remove(Guid.Parse(guid));
                return true;
            }
            return false;
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
