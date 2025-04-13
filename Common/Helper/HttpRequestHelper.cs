
using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading.Tasks;

namespace Common.Helper
{
    public class ResultError: HttpResult
    {
        public int Code { get; set; }
        public string Message { get; set; }
        public string Details { get; set; }
        public Object ValidationErrors { get; set; }
    }
    public class HttpResult<T>
    {
        public T Result { get; set; }
        public bool Success { get; set; } = true;
        public ResultError error { get; set; }
    }
    public class HttpResult: HttpResult<Object>
    {
        public Object Result = null;
        public bool Success { get; set; } = true;
        public ResultError error { get; set; }
    }
    public class HttpRequestHelper
    {
        private static string accessToken = "";
       

        static public async Task<HttpResult<T>> Get<T>(string url, string contentType = "application/json;charset=utf-8", Dictionary<string, string> header = null)
        {
            string result = null;
            result = await Task.Run(() =>
            {
                return httpRequest(url, contentType: contentType, header: header);
            });
            if (string.IsNullOrWhiteSpace(result)) return new HttpResult<T>()
            {
                error = new ResultError()
                {
                    Message = "网络请求出错"
                }
            };
            return JsonConvert.DeserializeObject<HttpResult<T>>(result);
        }
        static public async Task<HttpResult<T>> Post<T>(string url, Object json = null, string contentType = "application/json;charset=utf-8", Dictionary<string, string> header = null)
        {
            var jsonStr = JsonConvert.SerializeObject(json);
            string result = null;
            result = await Task.Run(() =>
            {
                return httpRequest(url, jsonStr, "POST", contentType: contentType, header: header);
            });

            if (string.IsNullOrWhiteSpace(result)) return new HttpResult<T>()
            {
                error = new ResultError()
                {
                    Message = "网络请求出错"
                }
            };
            return JsonConvert.DeserializeObject<HttpResult<T>>(result);
        }
        static public async Task<HttpResult> Get(string url, string contentType = "application/json;charset=utf-8", Dictionary<string, string> header = null)
        {
            string result = null;
            result = await Task.Run(() =>
            {
                return httpRequest(url, contentType: contentType, header: header);
            });
            if (string.IsNullOrWhiteSpace(result)) return new HttpResult()
            {
                error = new ResultError()
                {
                    Message = "网络请求出错"
                }
            };
            return JsonConvert.DeserializeObject<HttpResult>(result);
        }
        static public async Task<HttpResult> Post(string url, Object json = null, string contentType = "application/json;charset=utf-8", Dictionary<string, string> header = null)
        {
            var jsonStr = JsonConvert.SerializeObject(json);
            string result = null;
            result = await Task.Run(() =>
            {
                return httpRequest(url, jsonStr, "POST", contentType: contentType,header:header);
            });

            if (string.IsNullOrWhiteSpace(result)) return new HttpResult()
            {
                error = new ResultError()
                {
                    Message = "网络请求出错"
                }
            };
            return JsonConvert.DeserializeObject<HttpResult>(result);
        }
        public static string httpRequest(string url, string data = "", string method = "GET", string contentType = "application/json;charset=utf-8", int TimeOut = 20000, Dictionary<string, string> header = null)
        {
            Logger.Debug(method + " " + url + " " + data + " " + contentType);
            HttpWebResponse httpWebResponse = null;
            try
            {
                HttpWebRequest httpWebRequest = (HttpWebRequest)HttpWebRequest.Create(url);
                //设置请求方法
                httpWebRequest.Method = method;
                //请求超时时间
                httpWebRequest.Timeout = TimeOut;
                if (method != "GET") httpWebRequest.ContentType = contentType;
                httpWebRequest.Headers.Add("Authorization", "Bearer " + accessToken);
                if (header != null)
                {
                    foreach (var item in header.Keys)
                    {
                        if (httpWebRequest.Headers[item] != null)
                        {
                            httpWebRequest.Headers[item] = header[item];
                        }
                        else
                        {
                            httpWebRequest.Headers.Add(item, header[item]);
                        }
                    }
                }
                // 开启https
                if (url.ToLower().StartsWith("https"))
                {
                    ServicePointManager.ServerCertificateValidationCallback = new RemoteCertificateValidationCallback(CheckValidationResult);
                    httpWebRequest.ProtocolVersion = HttpVersion.Version10;
                    ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
                }
                // 发送数据
                if (!string.IsNullOrWhiteSpace(data))
                {
                    Byte[] bt = Encoding.UTF8.GetBytes(data);
                    //参数数据长度
                    httpWebRequest.ContentLength = bt.Length;
                    //将参数写入请求地址中
                    if (method != "GET") httpWebRequest.GetRequestStream().Write(bt, 0, bt.Length);
                }
                //发送请求
                httpWebResponse = (HttpWebResponse)httpWebRequest.GetResponse();
                //利用Stream流读取返回数据
                StreamReader streamReader = new StreamReader(httpWebResponse.GetResponseStream(), Encoding.UTF8);
                //获得最终数据，一般是json
                string responseContent = streamReader.ReadToEnd();
                streamReader.Close();
                httpWebResponse.Close();
                return responseContent;
            }
            catch (WebException ex)
            {
                if (ex.Response != null)
                {
                    using (StreamReader streamReader = new StreamReader(ex.Response.GetResponseStream(), Encoding.UTF8))
                    {
                        string responseContent = streamReader.ReadToEnd();
                        return responseContent;
                    }
                }
                Logger.Error("网络请求出错 status：" + ex.Status + " errMsg：" + ex.Message, ex);
                return null;
            }
            catch (Exception e)
            {
                Logger.Error("网络请求出错 errMsg：" + e.Message, e);
                return null;
            }
        }
        public static bool GetFile(string url,string filePath)
        {
            HttpWebResponse httpWebResponse = null;
            try
            {
                if (File.Exists(filePath))
                {
                    File.Delete(filePath);
                }
                FileStream fs = new FileStream(filePath, FileMode.Append, FileAccess.Write);
                HttpWebRequest httpWebRequest = (HttpWebRequest)HttpWebRequest.Create(url);
                if (url.ToLower().StartsWith("https"))
                {
                    ServicePointManager.ServerCertificateValidationCallback = new RemoteCertificateValidationCallback(CheckValidationResult);
                    httpWebRequest.ProtocolVersion = HttpVersion.Version10;
                    ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
                }
                //发送请求
                httpWebResponse = (HttpWebResponse)httpWebRequest.GetResponse();
                httpWebResponse.GetResponseStream().CopyTo(fs);
                httpWebResponse.Close();
                fs.Close();
                return true;
            }
            catch (WebException ex)
            {
                Logger.Error("网络请求出错 status：" + ex.Status + " errMsg：" + ex.Message, ex);
                return false;
            }
            catch (Exception e)
            {
                Logger.Error("网络请求出错 errMsg：" + e.Message, e);
                return false;
            }
        }

        private static bool CheckValidationResult(object sender, X509Certificate certificate, X509Chain chain, SslPolicyErrors errors)
        {
            return true; //总是接受     
        }

    }
}
