using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace AudioStream.AudioServer.Model
{
    public class DeviceInfo
    {
        public string Name { get; set; }
        public string ID { get; set; }
        public bool Default { set; get; }
    }
}
