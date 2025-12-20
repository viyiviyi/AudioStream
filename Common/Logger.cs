using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;

namespace Common
{
    /// <summary>
    /// 简单的日志
    /// </summary>
    public class Logger
    {
        public enum logVer
        {
            debug, info, warn, error
        }

        static FileInfo logFileInfo;
        static string logsDirPath;
        static string loggerPath;
        static Logger()
        {
            ThreadPool.SetMaxThreads(30, 30);
            ThreadPool.SetMinThreads(1, 1);

            try
            {
                logsDirPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "yiyiooo", "AudioStream", "logs");
                if (!Directory.Exists(logsDirPath))
                {
                    Directory.CreateDirectory(logsDirPath);
                }
                loggerPath = logsDirPath + @"\logger.txt";
                logFileInfo = new FileInfo(loggerPath);

            }
            catch
            {
            }
        }

        public static logVer logLive = logVer.debug;

        /// <summary>
        /// 写入日志 日志所在目录：应用程序目录/logs/
        /// </summary>
        /// <param name="log"></param>
        /// <param name="tag"></param>
        private static void Write(string log, string tag = "info")
        {
            ThreadPool.QueueUserWorkItem((o) =>
            {
                Thread.CurrentThread.IsBackground = true;
                WriteLog(log, tag);
            });
        }
        private static int Count = 0;
        private static void WriteLog(string log, string tag = "info")
        {
            try
            {
                Monitor.Enter(logFileInfo);
                using (var w = new StreamWriter(loggerPath, true, Encoding.UTF8))
                {
                    w.WriteLine(tag + " | " + DateTime.Now + " | " + log);
                    w.Flush();
                    w.Close();
                    w.Dispose();
                }
                // 日志分页 当日志文件大于等于10M时创建一个新的日志文件
                if (!logFileInfo.Exists) return;
                if (logFileInfo.Length > 1024 * 1024 * 10)
                {
                    logFileInfo.MoveTo(logsDirPath + "/logger" + "." + DateTime.Now.ToString("yyMMdd HHmmss") + ".txt");
                    logFileInfo = new FileInfo(loggerPath);
                }
                Monitor.Exit(logFileInfo);
            }
            catch (Exception)
            {
                if (Interlocked.Increment(ref Count) == 3)
                {
                    Count = 0;
                    return;
                }
                WriteLog(log, tag);
            }
        }
        public static void Info(string msg)
        {
            Console.WriteLine("info" + " | " + DateTime.Now + " | " + GetCurrentMethodFullName(2) + " | " + msg);
            if (logLive <= logVer.info)
                Write(GetCurrentMethodFullName(2) + " | " + msg);
        }
        public static void Warn(string msg)
        {
            Console.WriteLine("warn" + " | " + DateTime.Now + " | " + GetCurrentMethodFullName(2) + " | " + msg);
            if (logLive <= logVer.warn)
                Write(GetCurrentMethodFullName(2) + " | " + msg, "warn");
        }
        public static void Error(string msg, Exception ex = null)
        {
            Console.WriteLine("error" + " | " + DateTime.Now + " | " + GetCurrentMethodFullName(2) + " | " + msg);
            if (ex != null)
                Console.Write("\nerror" + " | " + DateTime.Now + " | " + ex.StackTrace + "\n");
            if (logLive <= logVer.error)
            {
                Write(GetCurrentMethodFullName(2) + " | " + msg, "error");
                if (ex != null)
                    Write(ex.StackTrace, "error");
            }
        }
        public static void Debug(string msg)
        {
            Console.WriteLine("debug" + " | " + DateTime.Now + " | " + GetCurrentMethodFullName(2) + " | " + msg);
            if (logLive == logVer.debug)
                Write(GetCurrentMethodFullName(2) + " | " + msg, "debug");
        }
        internal static string GetCurrentMethodFullName(int depth)
        {
            try
            {
                StackTrace st = new StackTrace();
                string methodName = st.GetFrame(depth).GetMethod().Name;
                string className = st.GetFrame(depth).GetMethod().DeclaringType.ToString();
                return className + "." + methodName;
            }
            catch
            {
                return null;
            }
        }
    }
}
