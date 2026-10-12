# AudioStream · Android 端

PC 端（`AudioStream.exe`，.NET Framework 4.7.2）的手机端。做两件事：

- **播放端**：手机连上 PC 的 AudioStream，选它的某个音频设备（扬声器环回或麦克风），把声音拉到本机播放。
- **来源端**：手机把麦克风与**手机内录**做成一台「AudioStream 机器」，让 PC 反过来拉——
  PC 端不必为手机做任何特殊处理，在它配置页的「电脑 IP」里填手机 IP 即可。

两端都走 PC 端那套现成协议，不做任何私有扩展。协议口径见下面「协议」一节，它是本工程的硬约束。

## 构建

本机不需要 Android Studio。工具链与同一个工作区的 `bili-tv` / `pinyin-ime` / `tv-browser` /
`lan-audio-share` 共用一份（JDK 17 + Gradle 8.7 + Android SDK platform-34 / build-tools 34.0.0）。

```powershell
cd android
powershell -File tools\setup-toolchain.ps1     # 建 tools-cache 目录联接 + 生成 local.properties
powershell -File tools\build.ps1               # release（自签名）APK + 88 项单元测试
powershell -File tools\build.ps1 -Variant debug
powershell -File tools\build.ps1 -SkipTest     # 只打包
powershell -File tools\build.ps1 -Install      # 顺手装到唯一在线的那台设备
```

真机用例（需要一台在线设备，共 6 项）：

```powershell
gradle :app:connectedDebugAndroidTest
```

产物落在 `dist\audiostream-android-<版本>-<variant>.apk`，可直接 sideload：

```powershell
tools-cache\android-sdk\platform-tools\adb.exe install -r dist\audiostream-android-1.0.0-release.apk
```

工具链靠 `android\tools-cache` 这个**目录联接**指到 `C:\apps\dsh_workspace\pinyin-ime\tools-cache`，
本工程自己不下载任何东西。运行期只用平台 API，不引 AndroidX；依赖只有测试用的
`junit` + `robolectric`（JVM 单元测试）与 `androidx.test:runner` + `androidx.test.ext:junit`
（真机用例），都不进 APK。

## 协议

两端之间的线上一共三样东西：**帧**、**文本命令**、**音频格式**。

### 帧

```
[4 字节小端长度][1 字节类型][负载]        长度含类型字节本身
```

| 类型值 | 名字 | 负载 |
|---|---|---|
| 1 | 音频 | 裸 PCM 字节，收到直接写进播放缓冲 |
| 2 | 文本 | UTF-8 命令，不带换行 |
| 3 | 音频格式 | 见下 |

之所以不是裸字节流：TCP 没有消息边界。C# 端早年靠「一次读到的包不超过 32 字节就当心跳」这种
长度猜测区分控制包与音频，内核一旦把两条消息并在一次读里、或者把一帧拆成两次读，猜测就会吃掉
真正的音频字节——音频是定长采样，少几个字节后面全部错位，听感是持续的杂音。
**读的时候必须读满**（`readExact`），不能假设一次 `read` 给全。

负载上限 1 MB（`MaxPayload`）：长度字段是不可信输入，不拦住就会照着它去分配一大块内存。
负载比本地缓冲大时**整帧读掉丢弃**并返回「读到了，但本帧内容不要」——流的位置必须保住，
否则后面每一帧都对不上。长度字段落在 `[0, 1MB]` 之外时直接判这条流结束，不再往下读
（站在流的中间，已经无法知道哪一段才是音频）。

### 命令（文本帧，都以 `/` 开头，参数用 `/` 分隔）

| 方向 | 命令 | 含义 |
|---|---|---|
| 拉取方 → 被拉方 | `/Hello/<本机 MachineId>/<名字转义>` | 握手，报身份。对端认出 MachineId 是它自己就回 `/Reject/self` |
| 被拉方 → 拉取方 | `/Hello/<对端 MachineId>/<名字转义>` | 握手应答 |
| 拉取方 → 被拉方 | `/WaveFormat/<设备号>/<设备名>` | 点单：要哪个设备。`default` = 对方此刻的系统默认输出（环回），`default-input` = 默认输入 |
| 被拉方 → 拉取方 | 一条类型 3 的帧 | 点单成功，回音频格式 |
| 被拉方 → 拉取方 | `/Reject/<原因>[/<补充说明>]` | 点单被拒，见下表 |
| 拉取方 → 被拉方 | `/Start` | 可以开始送音频了 |
| 拉取方 → 被拉方 | `/Pause` | 不听了，收工 |
| 拉取方 → 被拉方 | `/Ping` | 心跳（PC 端每 10 秒一次） |
| 被拉方 → 拉取方 | `/Pong` | 心跳应答（对端 10 秒内没发过东西才回，避免和音频抢路） |

拒绝原因（`/Reject/` 后半段，第 4 段是转义过的具体原因，要 `URLDecoder` 解回来）：

| 原因 | 意思 |
|---|---|
| `self` | 对端发现这是它自己——这条流必然绕回本机成环 |
| `loop` | 那个设备上此刻正播着从别处拉来的网络音频，再采一遍会绕成回路 |
| `nodevice` | 那个默认设备此刻不存在（比如机器上根本没插麦克风） |
| `unavailable` | 设备找到了、也放行了，但此刻打不开（被独占、驱动不支持共享采集） |
| `denied` | 对方没放行本机拉这个设备 |
| `timeout` | 对方把这次拉取挂起来等人确认，一直没人确认，超时了 |

手机端作为**被拉方**只会回 `self` / `nodevice` / `denied` / `unavailable` 四种：手机来源端不开授权
三档（`denied` 专指没给录音权限或没给内录授权），也不做采播冲突探测（手机端只采不放网络音频，
不存在绕回自己的回路），所以不会有 `loop` 与 `timeout`。

转义口径必须与 C# 端一致：**用 RFC 3986（空格 `%20`），不能用 `URLEncoder`**——C# 端用
`Uri.UnescapeDataString` 解，不把 `+` 当空格，用 `URLEncoder` 会让对端设备名凭空多一个加号。

### 手机端作为被拉方的设备号

| 设备号 | 指向 | 类别 |
|---|---|---|
| `default`、`capture:default`、`0`、空串 | 手机内录（跟随系统默认输出） | output |
| `default-input`、`mic:default` | 手机麦克风（跟随系统默认输入） | input |

`default` 与 `0` / 空串归**内录**是有意与 PC 端对齐的：PC 端的 `default` 指它此刻的系统默认输出
（环回），手机端最接近的对应物是内录。若归了麦克风，PC 端选「跟随默认」会拉到麦克风去。
同一设备号不会同时算两路来源，有断言钉住这条互斥性。

## 协议：音频格式（类型 3 的负载）

```
[int32 采样率][int32 位深][int32 声道数] + 尾部
```

尾部两种形态，**按负载总长度判断**，与 C# 端 `NetAudioRedirector` 的判据一致：

| 负载长度 | 尾部 | 解析 |
|---|---|---|
| ≥ 48 字节 | 36 字节 ASCII GUID（`WaveFormatExtensible.SubFormat.ToString()` 的结果） | 归一化后比对 `00000001-0000-0010-8000-00aa00389b71`（PCM）与 `00000003-...`（IEEE 浮点）；其它子格式记为「认不出」，**不假装成 PCM** |
| 13 ~ 47 字节 | 4 字节整数编码 | `1` = PCM、`3` = IEEE 浮点（取值由 CSCore.dll 反射读出核实） |

反方向编码照 C# 端 `SendWaveFormat` 的规则：子格式是 PCM/浮点（或根本不是扩展格式）就写 4 字节整数，
其它子格式写 36 字符 GUID。`WaveFormatCodec.encode/decode` 两端对称。

**手机端作为被拉方一律写 16 字节整数形式**（`[采样率][位深][声道数][1]`）。这是硬要求不是风格问题：
PC 端的判据是负载 >36 字节走 GUID、>12 字节走整数，而 **≤12 字节会静默退回它自认的默认格式**
（48k/32bit/浮点/双声道）——那会让 PC 端拿着一份和实际采集完全不一样的格式硬播，现场表现为
「连上了、有数据、就是难听」。

`bits==32 && 编码是浮点` 在安卓上对应 `ENCODING_PCM_FLOAT`（API 21+），16 位整数对应 `ENCODING_PCM_16BIT`。
采样率与声道数直接照搬对端——`AudioTrack` 会做重采样，但**不做**声道矩阵，所以声道数必须原样给。
手机端采集两路格式不同（麦克风 16 位单声道、内录 16 位立体声），PC 端「拿到什么格式就播什么格式」，
两边都能收；内录只能是 16 位整数，API 29 起的 `AudioPlaybackCapture` 不支持浮点。

## 端口与发现

| 用途 | 端口 | 说明 |
|---|---|---|
| 被拉方的 HTTP（配置页与设备清单） | 12570 | 路由前缀 `/api`，例如 `http://<ip>:12570/api/devices` |
| 被拉方的音频 TCP | 12670 起 | 被占用时往后顺延到 12689，拉取方按区间逐个试 |

PC 端的配置页在选「对方设备」时就是 `GET http://<ip>:12570/api/devices`，所以手机当来源端时
必须在这个端口上提供同一个路由（`/api/info`、`/api/devices`），返回体形状与 PC 端一致：

```json
{"Result":{"MachineId":"<32 位十六进制>","PcName":"…","TcpPort":12670,"HttpPort":12570,
           "Version":"…","Addresses":["192.168.1.5"]},"Success":true}
{"Result":[{"Name":"手机内录","ID":"capture:default","Default":true,"Flow":"output"}],"Success":true}
```

字段名是大写 `ID`；`TcpPort` 必须是**实际**监听端口（12670 被占会顺延，所以要先起音频口再起配置口）。
失败体是 `{"Success":false,"Code":…,"Message":"…"}`，PC 端只读 `Success` 与 `Message`。
中文必须有 `charset=utf-8`：设备名与拒绝原因都是中文。

## 联调与自测

PC 端不在手边时，来源端也能独立验证——这是回归的第一手段，三件套都留着别删：

| 东西 | 干什么 |
|---|---|
| `gradle :app:connectedDebugAndroidTest` | 真机整链路：起来源端、查 `/api/info` 与 `/api/devices`、拉一路麦克风看是否真收到声音（断言非零样本） |
| `app/src/androidTest/.../SourceHoldForPcTest.java` | 起来了源端并保持住（默认 3 秒，`-e holdSeconds 60` 拉长），供外部拉取方连 |
| `tools/pc-like-pull/PcLikePull.java` | 在宿主机 JVM 上扮演拉取方，直接用工程自己的 `PullSession` |

设备与开发机不在同一网段时，用 adb 端口转发把手机的两个口搬到本机（`adb forward tcp:22570 tcp:12570`
之类），再让 `PcLikePull` 连 `127.0.0.1`。

## 现状

| 目标 | 状态 |
|---|---|
| 目标一：播放端 | ✅ 实现完成，真机联调通过 |
| 目标二：来源端（麦克风） | ✅ 实现完成，已被真实拉取方端到端拉到声音 |
| 目标二：来源端（内录） | ✅ 实现完成；系统授权框之后的真实采集**未验**（见下） |

播放端验到什么程度：手机从真实 PC 端拉到 48000Hz/32 位浮点/双声道，12 秒写入声卡 4569600 字节
（理论值 99.2%）、丢弃 0、欠载 12；另有一条用例绕开声卡看负载，非零样本 41%，排除「链路通了但送的是静音」。
**没验到人耳听到声音**——联调机是虚拟机，扬声器是否出声无法自动化判定。

来源端验到什么程度：手机端起 12570/12670 两个口，`/api/info` 的 `TcpPort` 与实际监听一致；
一个真实的拉取方（宿主机上的 `PullSession`，与 C# 端同协议）拉 5 秒收到 485760 字节（理论 101.2%）、
253 帧、非零 242865 字节（49%）；手机侧同时记下「被 1 台拉取：PC拉取方」。两端帧数与字节数完全一致。

**还没验的两件事，都需要人工在真机上点一次（各约一分钟）**：

1. 电脑的 `AudioStream.exe` 亲自拉手机：在配置页填手机界面显示的地址，设备列表应出现
   「手机内录（跟随系统默认输出）」「手机内录」「手机麦克风（跟随系统默认输入）」「手机麦克风」四条。
2. 内录：手机上点「允许内录」→ 系统弹框同意 → 点「开启来源端」，然后在电脑上选「手机内录」，
   手机上放音乐应能在电脑上听到。

这两条之所以没做，是因为开发机上的联调设备是 VirtualBox 里的 Android-x86 虚拟机：它与宿主机之间
**除 adb(5555) 外的入站端口全部不通**（设备侧 netstat 可见 LISTEN、设备内部访问正常，宿主机直连超时；
对照实验：设备上起一个与 AudioStream 无关的 nc 监听 9999，宿主机直连同样超时），所以 PC 端程序本体
连不过来；而它的图形栈会成片卡死，连界面都画不出来，系统授权框也点不到。

## 已知限制

- **界面路径在本开发环境没验过**。联调机的图形栈（`gralloc.ranchu` + host GL socket）会间歇卡死几十秒，
  `screencap` 超时、System UI 自己弹 ANR。应用侧的阻塞问题已全部修掉（见 git 历史），但那台机器上
  仍然画不出界面，所以「用户点按钮」这条路只有代码、没有现场证据。功能正确性由不经过屏幕的测试保证。
- **内录授权活不过本次进程**：`MediaProjection` 的授权凭据不能存盘，重启应用要重新点一次，
  所以内录做不到「开机即用」。这是 Android 的规则，不是实现偷懒。
- **内录采不到的东西**：系统提示音、闹钟、通话，以及声明了禁止被捕获的应用（部分播放器、金融类应用）。
  只匹配媒体/游戏/未标注三类 usage——发现某个播放器采不到，先查它自己的 usage 或捕获声明。
- **协商成功之后才发生的失败报不到对端**：`/Start` 之后麦克风若被别的应用抢走，那时格式帧已经发出去了，
  协议没有「事后报错」通道，对端表现为「协商成功但没有声音」，手机侧界面才有原因。这是真实缺口。
- **PC 端独有的能力没有照搬**：不实现授权三档与 `/Reject/timeout`、不做采播冲突探测、
  不做多路共享采集池的跨格式重采样、不提供 `/players` 等管理接口——手机端用不到。
- 开发机上曾经在跑的是 `C:\apps\AudioStreamv2\AudioStream.exe`，与本工程 `dist\` 那份不是同一个文件
  （同版本、时间戳不同），联调时注意别搞混。
