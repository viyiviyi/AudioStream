using Common.Helper;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Linq;
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
            Mutex instance = new Mutex(true, "AudioStream", out bool createdNew);
            if (createdNew)
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                CreateTrayIcon();
                InitServer.Init();
                AutoStartHelper.SetMeStart(true);
                Application.Run();
                instance.ReleaseMutex();
            }
            else
            {
                InitServer.Stop();
                System.Environment.Exit(0);
                //Application.ExitThread();
                //Application.Exit();
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
