using Common;
using Newtonsoft.Json;
using System;
using System.IO;

namespace AudioStream.AudioServer
{
    /// <summary>
    /// 本机在音频流转网络里的身份标识。
    ///
    /// 之所以要持久化、而不是每次启动随机生成：对端要靠它认人。
    /// 拉取方在握手时报上自己的身份，被拉的一侧据此判断「这是不是我自己」——
    /// 如果是自己，这路流必然绕回本机形成环路，必须在握手阶段就拒绝掉。
    /// 身份每次启动都变的话，这条判断就失效了。
    ///
    /// 读不到就新建、写不进也不报错：身份只影响环路识别，不该拦住音频通路。
    /// </summary>
    internal static class MachineIdentity
    {
        private static readonly object SyncRoot = new object();
        private static string _id;
        private static string _name;

        /// <summary>本机身份，32 位十六进制字符串，首次运行时生成并落盘。</summary>
        public static string Id
        {
            get
            {
                EnsureLoaded();
                return _id;
            }
        }

        /// <summary>给人看的名字，默认取计算机名；判定只认 <see cref="Id"/>，重名无所谓。</summary>
        public static string Name
        {
            get
            {
                EnsureLoaded();
                return _name;
            }
        }

        /// <summary>身份文件路径，和播放列表放在同一个目录下。</summary>
        public static string ConfigFilePath
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "yiyiooo",
                    "AudioStream",
                    "identity.json");
            }
        }

        private static void EnsureLoaded()
        {
            if (_id != null) return;
            lock (SyncRoot)
            {
                if (_id != null) return;
                var file = ReadFile();
                if (file == null || string.IsNullOrWhiteSpace(file.Id))
                {
                    file = new IdentityFile
                    {
                        Id = Guid.NewGuid().ToString("N"),
                        Name = Environment.MachineName
                    };
                    WriteFile(file);
                }
                if (string.IsNullOrWhiteSpace(file.Name))
                {
                    file.Name = Environment.MachineName;
                }
                _id = file.Id.Trim();
                _name = file.Name.Trim();
            }
        }

        private static IdentityFile ReadFile()
        {
            try
            {
                if (!File.Exists(ConfigFilePath)) return null;
                var text = File.ReadAllText(ConfigFilePath, System.Text.Encoding.UTF8);
                if (string.IsNullOrWhiteSpace(text)) return null;
                return JsonConvert.DeserializeObject<IdentityFile>(text);
            }
            catch (Exception e)
            {
                Logger.Error("读取本机身份失败，将重新生成：" + e.Message);
                return null;
            }
        }

        private static void WriteFile(IdentityFile file)
        {
            try
            {
                var dir = Path.GetDirectoryName(ConfigFilePath);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                {
                    Directory.CreateDirectory(dir);
                }
                File.WriteAllText(ConfigFilePath, JsonConvert.SerializeObject(file), System.Text.Encoding.UTF8);
            }
            catch (Exception e)
            {
                Logger.Error("保存本机身份失败，本次运行的身份下次启动会变：" + e.Message);
            }
        }

        private class IdentityFile
        {
            public string Id { get; set; }
            public string Name { get; set; }
        }
    }
}
