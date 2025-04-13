using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace AudioStream.AudioServer
{
    internal interface IPlayerRedirector : IDisposable
    {
        float Volume { get; set; }
    }
}
