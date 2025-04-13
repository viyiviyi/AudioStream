using System.Web;

namespace Common.Helper
{
    public class UrlHelper
    {
        public static string GetUrlAegByKey(string key, string url)
        {
            var val = "";
            if (url.IndexOf('?') < 0) return val;
            if (url.IndexOf('?') == url.Length - 1) return val;
            var query = url.Split('?')[1];
            var queryList = query.Split('&');
            foreach (var s in queryList)
            {
                if (s.IndexOf('=') < 0) continue;
                if (s.StartsWith(key))
                {
                    var ls = s.Split('=');
                    if (ls.Length > 1) return HttpUtility.UrlDecode(ls[1]);
                }
            }
            return val;
        }
    }
}
