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
powershell -File tools\build.ps1               # release（自签名）APK，含单元测试
powershell -File tools\build.ps1 -Variant debug
powershell -File tools\build.ps1 -SkipTest     # 只打包
powershell -File tools\build.ps1 -Install      # 顺手装到唯一在线的那台设备
```

产物落在 `dist\audiostream-android-<版本>-<variant>.apk`，可直接 sideload：

```powershell
tools-cache\android-sdk\platform-tools\adb.exe install -r dist\audiostream-android-1.0.0-release.apk
```

工具链靠 `android\tools-cache` 这个**目录联接**指到 `C:\apps\dsh_workspace\pinyin-ime\tools-cache`，
本工程自己不下载任何东西。依赖只有 `junit` + `robolectric`（测试用），运行期只用平台 API。

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

`bits==32 && 编码是浮点` 在安卓上对应 `ENCODING_PCM_FLOAT`（API 21+），16 位整数对应 `ENCODING_PCM_16BIT`。
采样率与声道数直接照搬对端——`AudioTrack` 会做重采样，但**不做**声道矩阵，所以声道数必须原样给。

## 端口与发现

| 用途 | 端口 | 说明 |
|---|---|---|
| 被拉方的 HTTP（配置页与设备清单） | 12570 | 路由前缀 `/api`，例如 `http://<ip>:12570/api/devices` |
| 被拉方的音频 TCP | 12670 起 | 被占用时往后顺延到 12689，拉取方按区间逐个试 |

PC 端的配置页在选「对方设备」时就是 `GET http://<ip>:12570/api/devices`，所以手机当来源端时
必须在这个端口上提供同一个路由（`/api/info`、`/api/devices`），返回体形状与 PC 端一致：
`{"Result": [...], "Success": true}`。

## 现状

| 目标 | 状态 |
|---|---|
| 目标一：播放端 | 进行中——工程骨架与帧协议层已就位 |
| 目标二：来源端 | 未开始 |

已就位：

- 工程骨架与一键构建（`tools\setup-toolchain.ps1` / `tools\build.ps1`）
- 帧协议与音频格式层（`protocol/`，**19 项单元测试**）
- 本机身份 `MachineIdentity`（32 位十六进制，首次运行生成后落盘，改名不换身份）

`protocol/` 里那份参照字节不是自己算给自己看的：它由 `tools\AudioFraming.ps1`（PC 端验证脚本用的
同一份实现）现场生成过，与 Java 侧的字节逐字节相同。
