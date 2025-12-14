using AudioStream.Properties;
using Common;
using Common.Helper;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Security.Policy;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AudioStream
{
    internal static class Program
    {
        private static NotifyIcon trayIcon;
        /// <summary>
        /// 应用程序的主入口点。
        /// </summary>
        [STAThread]
        static void Main()
        {
            //Thread.Sleep(30 * 1000);
            Environment.CurrentDirectory = Path.GetDirectoryName(Application.ExecutablePath);
            Console.WriteLine(Environment.CurrentDirectory);
            #region 保持只有一个进程
            Mutex instance = new Mutex(true, "AudioStream", out bool createdNew);
            if (!createdNew)
            {
                Process currentProcess = Process.GetCurrentProcess();
                foreach (Process item in Process.GetProcessesByName(currentProcess.ProcessName))
                {
                    if (item.Id != currentProcess.Id && (item.StartTime - currentProcess.StartTime).TotalMilliseconds <= 0)
                    {
                        item.Kill();
                        break;
                    }
                }
            }
            Application.ApplicationExit += (s, d) =>
            {
                instance.ReleaseMutex();
            };
            #endregion
            WaitForNetworkConnection();
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            CreateTrayIcon();
            InitServer.Init();
            AutoStartHelper.SetMeStart(true);
            Application.Run();
        }
        private static void WaitForNetworkConnection(int timeoutSeconds = 30)
        {
            DateTime startTime = DateTime.Now;

            while (!NetworkInterface.GetIsNetworkAvailable())
            {
                if ((DateTime.Now - startTime).TotalSeconds > timeoutSeconds)
                {
                    // 超时处理
                    Logger.Error("计算机网络状态异常，无法启动程序。");
                    break;
                }

                Thread.Sleep(1000); // 等待1秒
            }
        }
        private static void CreateTrayIcon()
        {
            trayIcon = new NotifyIcon()
            {
                Icon = Icon.ExtractAssociatedIcon("icon.ico"), // 设置图标，可以使用自己的图标文件
                Text = "音频流转",           // 鼠标悬停时显示的文本
                Visible = true                  // 必须设置为true才能显示
            };

            // 添加右键菜单
            ContextMenuStrip contextMenu = new ContextMenuStrip();
            
            contextMenu.Items.Add("配置", null, (s,e)=> OnOpenConfigPage());
            contextMenu.Items.Add("退出", null, OnExit);

            trayIcon.ContextMenuStrip = contextMenu;

            //双击事件（可选）
            trayIcon.DoubleClick += (s, e) => OnOpenConfigPage();
        }

        private static void OnOpenConfigPage()
        {
            Process.Start(new ProcessStartInfo()
            {
                FileName = "http://localhost:12570",
                UseShellExecute = true
            });
        }

        private static void OnExit(object sender, EventArgs e)
        {
            InitServer.Stop();
            Application.Exit();
        }
    }
}
