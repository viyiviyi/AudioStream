using CSCore;
using CSCore.Codecs.WAV;
using CSCore.CoreAudioAPI;
using CSCore.SoundIn;
using CSCore.SoundOut;
using CSCore.Streams;
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AudioStream
{
    public partial class Form1 : Form
    {
        TcpServer tcpServer = new TcpServer();
        public Form1()
        {
            InitializeComponent();
            InitServer.Init();
            Disposed += (s, e) =>
            {
                InitServer.Stop();
            };
        }


    }
}
