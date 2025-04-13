using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace Common
{
    public class Tools
    {
        public static bool IsValidIPv4Address(string ipString)
        {
            if (string.IsNullOrEmpty(ipString))
            {
                return false;
            }
            if (IPAddress.TryParse(ipString, out IPAddress address))
            {
                return address.AddressFamily == AddressFamily.InterNetwork;
            }
            return false;
        }
        public static bool IsValidIPv6Address(string ipString)
        {
            if (string.IsNullOrEmpty(ipString))
            {
                return false;
            }
            if (IPAddress.TryParse(ipString, out IPAddress address))
            {
                return address.AddressFamily == AddressFamily.InterNetworkV6;
            }
            return false;
        }
        public static bool IsPrivateIPAddress(string ipString)
        {
            if (string.IsNullOrEmpty(ipString))
            {
                return false;
            }
            if (IPAddress.TryParse(ipString, out IPAddress ipAddress))
            {
                byte[] ipBytes = ipAddress.GetAddressBytes();
                return true;
            }
            return false;
        }
    }
}
