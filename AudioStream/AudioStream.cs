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
    public partial class AudioStream : Form
    {
        TcpServer tcpServer = new TcpServer();
        public AudioStream()
        {
            InitializeComponent();
            this.Visible = false;
            InitServer.Init();
            Disposed += (s, e) =>
            {
                InitServer.Stop();
            };
        }


    }
}
