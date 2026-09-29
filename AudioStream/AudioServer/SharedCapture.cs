using CSCore;
using CSCore.CoreAudioAPI;
using System;
using System.Collections.Generic;

namespace AudioStream
{
    /// <summary>
    /// 一个音频设备对应一份采集，多个拉取方共用它。
    ///
    /// 原先每来一条拉取连接就 new 一个 TcpAudioServer，两个人拉同一个扬声器就会开出两份
    /// WASAPI 环回采集：既浪费，又因为是同一个设备被重复打开而容易互相干扰。这里按设备号
    /// 收敛成一份，拉取方只是增减订阅者，最后一个订阅者走了才真正关掉设备。
    /// </summary>
    internal class SharedCapture : IDisposable
    {
        private readonly TcpAudioServer capture;
        private readonly List<Subscriber> subscribers = new List<Subscriber>();
        private readonly object syncRoot = new object();
        private bool started;
        private bool disposed;

        public SharedCapture(string deviceId, string deviceName, MMDevice device)
        {
            DeviceId = deviceId;
            DeviceName = deviceName;
            capture = new TcpAudioServer(device, Broadcast);
            Format = capture.GetWaveFormat();
        }

        /// <summary>设备的稳定标识，共享池以它作为键。</summary>
        public string DeviceId { get; private set; }

        /// <summary>设备名，只用于显示。</summary>
        public string DeviceName { get; private set; }

        /// <summary>采集格式。同一个设备的所有订阅者拿到的是同一份。</summary>
        public WaveFormat Format { get; private set; }

        public bool Disposed
        {
            get { return disposed; }
        }

        public int SubscriberCount
        {
            get { lock (syncRoot) { return subscribers.Count; } }
        }

        /// <summary>增加一个订阅者。采集真的开动要另外调 <see cref="Start"/>。</summary>
        public Subscriber Subscribe(Action<byte[], int> onData)
        {
            var subscriber = new Subscriber { OnData = onData };
            lock (syncRoot)
            {
                if (disposed) throw new ObjectDisposedException("SharedCapture");
                subscribers.Add(subscriber);
            }
            return subscriber;
        }

        public void Unsubscribe(Subscriber subscriber)
        {
            if (subscriber == null) return;
            lock (syncRoot)
            {
                subscribers.Remove(subscriber);
            }
        }

        /// <summary>让采集开始跑。已经在跑时是空操作，所以每条连接都可以放心地调它。</summary>
        public void Start()
        {
            lock (syncRoot)
            {
                if (disposed || started) return;
                started = true;
            }
            capture.Start();
        }

        private void Broadcast(byte[] data, int length)
        {
            Subscriber[] snapshot;
            lock (syncRoot)
            {
                snapshot = subscribers.ToArray();
            }
            for (int i = 0; i < snapshot.Length; i++)
            {
                var subscriber = snapshot[i];
                if (!subscriber.Active) continue;
                try
                {
                    var handler = subscriber.OnData;
                    if (handler != null) handler(data, length);
                }
                catch (Exception)
                {
                    // 一路出问题不能拖累其它路。标记它失效，由各自连接的清理逻辑收尾。
                    subscriber.Active = false;
                }
            }
        }

        public void Dispose()
        {
            lock (syncRoot)
            {
                if (disposed) return;
                disposed = true;
                subscribers.Clear();
            }
            capture.Dispose();
        }

        /// <summary>一个拉取方在这份采集上的订阅。连接断开时把它置无效。</summary>
        internal class Subscriber
        {
            public Action<byte[], int> OnData;

            /// <summary>采集线程和连接线程都会读写，所以用 volatile。</summary>
            public volatile bool Active = true;
        }
    }
}
