#Requires -Version 5.1
<#
    验证「发送侧：共享采集与采播冲突拦截」。

    全程在本机跑，不依赖第二台机器。做四件事：
      1. 基线：没有任何播放记录时，点单一个设备能正常拿到格式头。
      2. 不误拒：加了一条指向本机的播放记录（会被对端以「自连」拒绝，实际并没有在播）时，
         点单同一个设备仍然要能成功——对端离线不该算成回路。
      3. 共享采集：两路拉同一个设备，采集只应有一份（/api/captures 一条），
         而拉取方是两路（/api/clients 两条），且两路都拿到了格式头。
      4. 冲突拦截：本机真的在播一个假的网络音频源到设备 E 时，点单 E 要被 /Reject/loop 拦下；
         点单另一个设备 F 不受影响。

    需要在对端 12670 上放一个假音频源，所以会重启一次被测程序（真程序会先占住 12670，
    假源起不来）。假源只推静音，不会有声音。

    注意：脚本会临时加一条播放记录，结束时删掉；被测程序常驻的播放记录不会被改动。
#>
param(
    [string]$ExePath = '',
    [int]$HttpPort = 12570,
    [int]$FakePort = 12670
)

$ErrorActionPreference = 'Stop'
$script:Pass = 0
$script:Fail = 0

function Check {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    if ($Ok) {
        $script:Pass++
        Write-Host ("  [通过] " + $Name) -ForegroundColor Green
    } else {
        $script:Fail++
        Write-Host ("  [失败] " + $Name + $(if ($Detail) { " —— " + $Detail } else { '' })) -ForegroundColor Red
    }
}

function Section {
    param([string]$Title)
    Write-Host ''
    Write-Host ("== " + $Title + " ==") -ForegroundColor Cyan
}

# ---------- 基础工具 ----------

$BaseUrl = "http://127.0.0.1:$HttpPort"

function Get-Api {
    param([string]$Path)
    return Invoke-RestMethod -Uri ($BaseUrl + $Path) -TimeoutSec 5
}

function Post-Api {
    param([string]$Path)
    return Invoke-RestMethod -Uri ($BaseUrl + $Path) -Method Post -TimeoutSec 20
}

function Wait-Api {
    param([int]$TimeoutSeconds = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $info = Get-Api '/api/info'
            if ($info -and $info.Result -and $info.Result.TcpPort) { return $info.Result }
        } catch {
            Start-Sleep -Milliseconds 300
        }
    }
    return $null
}

# 帧协议工具：握手、点单、格式应答与音频现在都走帧
. "$PSScriptRoot\AudioFraming.ps1"

function Send-Line {
    param($Stream, [string]$Text)
    Write-TextFrame -Stream $Stream -Text $Text
}

function Read-Line {
    param($Stream, [int]$TimeoutMs = 5000)
    $Stream.ReadTimeout = $TimeoutMs
    return (Read-TextFrame -Stream $Stream)
}

# 点单：握手 -> 要格式 -> 让它开始送。返回连接对象，由调用方负责关闭。
function Invoke-Order {
    param([int]$Port, [string]$DeviceId, [string]$DeviceName, [switch]$Start)

    $client = New-Object System.Net.Sockets.TcpClient
    $client.NoDelay = $true
    $client.Connect('127.0.0.1', $Port)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 5000
    $stream.WriteTimeout = 5000

    Send-Line $stream '/Hello/00000000000000000000000000000000/PSTest'
    $hello = Read-Line $stream

    Send-Line $stream ('/WaveFormat/' + $DeviceId + '/' + $DeviceName)

    $reply = $null
    $isBinary = $false
    $formatBytes = 0
    $frame = Read-Frame -Stream $stream
    if ($null -ne $frame) {
        if ($frame.Kind -eq 3) {
            # 音频格式帧
            $isBinary = $true
            $formatBytes = $frame.Length
        } elseif ($frame.Kind -eq 2) {
            # 文本帧：对端回的是拒绝之类
            $reply = $frame.Text
        }
    }

    if ($Start -and $isBinary) {
        Send-Line $stream '/Start'
    }

    return [pscustomobject]@{
        Client      = $client
        Stream      = $stream
        Hello       = $hello
        Reply       = $reply
        IsBinary    = $isBinary
        FormatBytes = $formatBytes
    }
}

function Close-Order {
    param($Order)
    if ($null -eq $Order) { return }
    try { $Order.Stream.Dispose() } catch { }
    try { $Order.Client.Close() } catch { }
}

# ---------- 假音频源 ----------
# 只服务一条连接：握手、给格式、然后不停推静音。
# 目的不是好听，是让被测程序真的进入「正在播网络音频」的状态。
function Start-FakeSource {
    param([int]$Port, [string[]]$LocalAddresses = @(), [string]$ExpectedDeviceId = '')
    # 作业跑在独立进程里，靠 InitializationScript 把帧工具引进去（ScriptBlock 里 dot-source 不了调用方的变量）
    $lib = Join-Path $PSScriptRoot 'AudioFraming.ps1'
    $init = [scriptblock]::Create(". '" + $lib + "'")
    return Start-Job -ArgumentList $Port, $LocalAddresses, $ExpectedDeviceId -InitializationScript $init -ScriptBlock {
        param($port, $localAddresses, $expectedDeviceId)
        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $port)
        $listener.Start()
        try {
            while ($true) {
                $client = $listener.AcceptTcpClient()
                # 只服务「本机发起」的连接：这个假源一次只服务一条连接，
                # 局域网里别的机器（真机）也会来连这个端口，被它们占住就会让被测程序一直连不上。
                $remote = $client.Client.RemoteEndPoint.Address.ToString()
                if (-not ($remote -eq '127.0.0.1' -or $localAddresses -contains $remote)) {
                    Write-Output ('假源：拒绝非本机连接 ' + $remote)
                    try { $client.Close() } catch { }
                    continue
                }
                Write-Output ('假源：接受连接 ' + $remote)
                $client.NoDelay = $true
                $stream = $client.GetStream()
                # 每条连接自己兜异常。原来的写法只有一个最外层 try，任何一次写失败都会走到
                # 下面的 finally 把 listener 一起关掉，假源就此停摆：客户端重连时 12670 已经没人听，
                # 于是顺延到 12671 连到它自己（报「对端认出这是它自己」），再下一次读超时。
                # 一条连接断掉只该结束这一条。
                try {
                $started = $false
                while (-not $started) {
                    $frame = Read-Frame -Stream $stream
                    if ($null -eq $frame) { break }
                    if ($frame.Kind -ne 2) { continue }   # 只认文本命令
                    $line = [System.Text.Encoding]::UTF8.GetString($frame.Payload)
                    Write-Output ('假源：命令 ' + $line)
                    if ($line.StartsWith('/Hello/')) {
                        Write-TextFrame -Stream $stream -Text '/Hello/11111111111111111111111111111111/PS-Fake'
                    } elseif ($line.StartsWith('/WaveFormat/')) {
                        $reqDevice = $line.Split('/')[2]
                        if ($expectedDeviceId -and $reqDevice -ne $expectedDeviceId) {
                            # 假源一次只服务一条连接。别的拉取（用户自己配的记录、或者测试留下的残留）
                            # 连上来会把它整条占住，本脚本这条就永远排不上，表现出来是「这一路没在播」。
                            # 不是本脚本点单的设备，直接让位回 accept。
                            Write-Output ('假源：不是本脚本要的设备，让位 ' + $reqDevice)
                            throw 'not-my-device'
                        }
                        $ms = New-Object System.IO.MemoryStream
                        $bw = New-Object System.IO.BinaryWriter($ms)
                        # 顺序必须和真实服务端一致：采样率、位深、声道数、编码。
                        # 位深要是小于 8，CSCore 算出来的字节率会是 0，客户端一上来就报错。
                        $bw.Write([int]48000)
                        $bw.Write([int]32)
                        $bw.Write([int]2)
                        $bw.Write([int]3)   # IeeeFloat，32 位浮点
                        $bw.Flush()
                        Write-FormatFrame -Stream $stream -Payload $ms.ToArray()
                    } elseif ($line.StartsWith('/Start')) {
                        $started = $true
                    }
                }
                $silence = New-Object byte[] 7680
                while ($true) {
                    Write-Frame -Stream $stream -Kind 1 -Payload $silence
                    Start-Sleep -Milliseconds 20
                }
                } catch {
                    Write-Output ("假源：一条连接结束（" + $_.Exception.Message + "）")
                } finally {
                    try { $client.Close() } catch { }
                }
            }
        } catch {
            Write-Output ("假源：接受连接出错（" + $_.Exception.Message + "）")
        } finally {
            try { $listener.Stop() } catch { }
        }
    }
}

function Wait-PortFree {
    param([int]$Port, [int]$TimeoutSeconds = 15)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $used = $false
        foreach ($ep in [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()) {
            if ($ep.Port -eq $Port) { $used = $true; break }
        }
        if (-not $used) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

function Wait-PortUsed {
    param([int]$Port, [int]$TimeoutSeconds = 15)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($ep in [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()) {
            if ($ep.Port -eq $Port) { return $true }
        }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

# ---------- 准备 ----------

if ([string]::IsNullOrWhiteSpace($ExePath)) {
    $ExePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'AudioStream\bin\Debug\AudioStream.exe'
}
if (-not (Test-Path $ExePath)) { throw "找不到被测程序：$ExePath" }

# 本机自己的 IPv4 列表，给假音频源挑出「本机发起」的连接（见 Start-FakeSource 里的注释）。
$LocalAddresses = @([System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
    Where-Object { $_.AddressFamily -eq 'InterNetwork' } | ForEach-Object { $_.ToString() })
$ExePath = (Resolve-Path $ExePath).Path

Write-Host ("被测程序：" + $ExePath)

foreach ($ep in [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()) {
    if ($ep.Port -eq $HttpPort) {
        throw "端口 $HttpPort 已被占用，请先退出正在运行的 AudioStream 再跑本脚本。"
    }
}

$exe = $null
$fakeJob = $null
$orders = New-Object System.Collections.ArrayList
$addedPlayerIds = New-Object System.Collections.ArrayList
$beforePlayerIds = @()

try {
    # ---------- 第一阶段：不占用 12670，验证基线与共享采集 ----------
    Section '启动被测程序'
    $exe = Start-Process -FilePath $ExePath -PassThru
    $info = Wait-Api
    if ($null -eq $info) { throw '被测程序起来后 /api/info 一直没有响应' }
    Check '程序已就绪并报告监听信息' ($null -ne $info)
    Write-Host ("  本机身份：" + $info.MachineId + "  计算机名：" + $info.PcName + "  TCP 端口：" + $info.TcpPort)
    $tcpPort = [int]$info.TcpPort

    $devices = (Get-Api '/api/devices').Result
    Check '能列出本机输出设备' ($null -ne $devices -and $devices.Count -ge 2) ("设备数 " + @($devices).Count)
    if ($null -eq $devices -or $devices.Count -lt 2) { throw '输出设备不足两个，无法继续' }

    # 挑两块此刻真的能开出采集的设备：冲突设备 E 尽量用虚拟声卡（静音播放不会吵到人），
    # 但要先确认它打得开——被别的程序独占的端点、或者驱动不支持共享模式采集的端点，
    # 点单会被回 /Reject/unavailable，那样验到的是「设备打不开」，不是「有没有被误拒」。
    $usable = @()
    foreach ($device in $devices) {
        $probe = Invoke-Order -Port $tcpPort -DeviceId $device.ID -DeviceName $device.Name
        $ok = $probe.IsBinary
        Close-Order $probe
        if ($ok) { $usable += $device }
        else { Write-Host ("  跳过一块此刻开不出采集的设备：" + $device.Name + " -> " + $probe.Reply) }
    }
    if ($usable.Count -lt 2) { throw '本机此刻打得开采集的设备不足两块，无法继续' }
    Start-Sleep -Milliseconds 800   # 等探测打开的采集释放掉
    # 对照设备 F 优先挑此刻没被别人拉着的：本脚本要断言「断开之后采集被释放」，
    # 一块正被别的机器共享着的设备本来就该留着采集，拿它做对照只会无缘无故失败。
    $busy = @((Get-Api '/api/captures').Result)
    $virt = $usable | Where-Object { $_.Name -match 'Virtual|AudioRelay|UU|虚拟' } | Select-Object -First 1
    if ($null -eq $virt) { $virt = $usable[0] }
    $other = $usable | Where-Object { $_.ID -ne $virt.ID -and $busy -notcontains $_.ID } | Select-Object -First 1
    if ($null -eq $other) { $other = $usable | Where-Object { $_.ID -ne $virt.ID } | Select-Object -First 1 }
    Write-Host ("  冲突设备 E：" + $virt.Name)
    Write-Host ("  对照设备 F：" + $other.Name)

    $beforePlayerIds = @((Get-Api '/api/players').Result | ForEach-Object { $_.ID })

    Section '一、基线：无人拉取时点单正常'
    # 只看本脚本要用的那个设备：别的机器正在拉本机其它设备与本脚本无关，
    # 断言「一个采集都没有」会把别人的正常拉取误判成本次失败。
    $startCaptures = @((Get-Api '/api/captures').Result)
    if (@($startCaptures | Where-Object { $_ -eq $other.ID }).Count -eq 0) {
        Check '开始时本脚本要用的设备没有被采集' $true
    } else {
        # 没有空闲设备可选时才会走到这里：这台机器上所有能用的设备都被别的机器拉着。
        Write-Host ('  （说明：跑之前就有别的机器在拉「' + $other.Name + '」，采集是共享的，这一条改为只提示）')
    }
    $baseline = Invoke-Order -Port $tcpPort -DeviceId $other.ID -DeviceName $other.Name
    [void]$orders.Add($baseline)
    Check '点单拿到对端身份' ($baseline.Hello -like '/Hello/*') ("实际：" + $baseline.Hello)
    Check '点单拿到音频格式头' ($baseline.IsBinary -and $baseline.FormatBytes -ge 13) ("收到 " + $baseline.FormatBytes + " 字节，Reply=" + $baseline.Reply)
    Close-Order $baseline
    [void]$orders.Remove($baseline)

    Section '二、不误拒：配置里有一条连不上的拉取，不算「正在播」'
    # 填本机内网地址，会连到本机自己 -> 被以「自连」拒绝 -> 实际并没有在播，Playing 应为 false
    $selfIp = @($info.Addresses)[0]
    $addResp = Post-Api ('/api/add_player?ip=' + $selfIp + '&s_device=' + [uri]::EscapeDataString($other.ID) + '&t_device=' + [uri]::EscapeDataString($virt.ID) + '&s_device_name=' + [uri]::EscapeDataString($other.Name) + '&t_device_name=' + [uri]::EscapeDataString($virt.Name))
    $newPlayers = @((Get-Api '/api/players').Result | Where-Object { $beforePlayerIds -notcontains $_.ID })
    foreach ($p in $newPlayers) { [void]$addedPlayerIds.Add($p.ID) }
    Check '成功加了一条指向本机的播放记录' ($newPlayers.Count -eq 1) ("新增 " + $newPlayers.Count + " 条")
    Start-Sleep -Seconds 3
    $status1 = @((Get-Api '/api/play-status').Result | Where-Object { $addedPlayerIds -contains $_.ID })
    Check '这一路此刻并没有在播（对端连不上）' ($status1.Count -eq 1 -and -not $status1[0].Playing) ("Playing=" + ($status1 | ForEach-Object { $_.Playing }) + " Error=" + ($status1 | ForEach-Object { $_.Error }))
    Check '状态里说清了没播起来的原因' ($status1.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($status1[0].Error)) ("Error=" + ($status1 | ForEach-Object { $_.Error }))
    $noPlay = Invoke-Order -Port $tcpPort -DeviceId $virt.ID -DeviceName $virt.Name
    [void]$orders.Add($noPlay)
    Check '对端连不上时，点单这个设备不被误拒' ($noPlay.IsBinary) ("实际应答：" + $noPlay.Reply)
    Close-Order $noPlay
    [void]$orders.Remove($noPlay)

    Section '三、共享采集：两路拉同一个设备'
    $order1 = Invoke-Order -Port $tcpPort -DeviceId $other.ID -DeviceName $other.Name -Start
    $order2 = Invoke-Order -Port $tcpPort -DeviceId $other.ID -DeviceName $other.Name -Start
    [void]$orders.Add($order1)
    [void]$orders.Add($order2)
    Check '第一路拿到格式头' ($order1.IsBinary) ("实际应答：" + $order1.Reply)
    Check '第二路拿到格式头' ($order2.IsBinary) ("实际应答：" + $order2.Reply)
    $captures = @((Get-Api '/api/captures').Result)
    # 只针对本脚本点单的那个设备断言：这台机器上可能同时有别的机器在拉别的设备，
    # 那是与本脚本无关的采集，数进来只会让断言无缘无故失败。
    $mineCaptures = @($captures | Where-Object { $_ -eq $other.ID })
    Check '本脚本点单的设备只开了一份采集（两路共享同一份）' ($mineCaptures.Count -eq 1) ("实际 " + $mineCaptures.Count + " 份；全部采集：" + ($captures -join ', '))
    $foreign = @($captures | Where-Object { $_ -ne $other.ID })
    if ($foreign.Count -gt 0) {
        Write-Host ("  提示：本机另有采集 " + ($foreign -join ', ') + "，多半是别的机器正在拉本机设备，本脚本只断言自己这一份。")
    }
    $clients = @((Get-Api '/api/clients').Result)
    # 只数本脚本自己那两路：本脚本的假身份是 PSTest，别的机器拉的同一个设备不算在内。
    $mineClients = @($clients | Where-Object { $_.SourceDeviceID -eq $other.ID -and $_.PcName -eq 'PSTest' })
    Check '本脚本的两路连接都在客户端列表里' ($mineClients.Count -eq 2) ("实际 " + $mineClients.Count + " 路（本机共 " + $clients.Count + " 路）")
    Check '两路拉的是同一个设备' ($mineClients.Count -eq 2) ("实际 " + $mineClients.Count + " 路")
    $named = @($mineClients | Where-Object { $_.DeviceName -eq $other.Name }).Count
    Check '界面能看到被拉走的设备名' ($named -eq 2) ("实际 " + $named + " 路")

    Close-Order $order1
    [void]$orders.Remove($order1)
    Start-Sleep -Milliseconds 800
    Check '一路断开后本脚本设备的采集仍在（另一路还听着）' (@((Get-Api '/api/captures').Result | Where-Object { $_ -eq $other.ID }).Count -eq 1)
    Close-Order $order2
    [void]$orders.Remove($order2)
    Start-Sleep -Milliseconds 800
    # 跑完时如果还有别的机器在拉同一块设备，采集按共享规则本来就该留着，
    # 那是正确行为，不能判成本脚本泄漏。
    $stillForeign = @((Get-Api '/api/clients').Result | Where-Object { $_.SourceDeviceID -eq $other.ID -and $_.PcName -ne 'PSTest' -and $_.Streaming })
    if ($stillForeign.Count -gt 0) {
        Write-Host ('  （说明：跑完时还有别的机器在拉「' + $other.Name + '」，采集按共享规则留着，这一步不判它泄漏）')
    } else {
        Check '两路都断开后本脚本设备的采集被释放' (@((Get-Api '/api/captures').Result | Where-Object { $_ -eq $other.ID }).Count -eq 0) ("实际：" + (@((Get-Api '/api/captures').Result) -join ', '))
    }

    # ---------- 第二阶段：让 12670 换成假音频源，验证冲突拦截 ----------
    Section '四、冲突拦截：本机真的在播网络音频时'
    Write-Host '  停掉被测程序，把 12670 让给假音频源'
    try { $exe.Kill(); $exe.WaitForExit(5000) | Out-Null } catch { }
    $exe = $null
    Check '端口 12670 已释放' (Wait-PortFree -Port $FakePort)

    # 假源扮演的是「被拉取的来源」。这一路要拉的是 $other（对照设备 F），
    # 所以让位判据要用 $other 的设备号，不是播放目标 $virt 的。
    $fakeJob = Start-FakeSource -Port $FakePort -LocalAddresses $LocalAddresses -ExpectedDeviceId $other.ID
    Check '假音频源已在 12670 上监听' (Wait-PortUsed -Port $FakePort)

    $exe = Start-Process -FilePath $ExePath -PassThru
    $info2 = Wait-Api
    if ($null -eq $info2) { throw '被测程序第二次启动失败' }
    $tcpPort = [int]$info2.TcpPort
    Check '被测程序顺延到 12671 监听' ($tcpPort -eq ($FakePort + 1)) ("实际 TCP 端口：" + $tcpPort)

    # 上一阶段留下的那条播放记录会在启动时自动拉起，这次它连得上假源，于是真的在播
    Start-Sleep -Seconds 6
    $status2 = @((Get-Api '/api/play-status').Result | Where-Object { $addedPlayerIds -contains $_.ID })
    if (-not ($status2.Count -eq 1 -and $status2[0].Playing)) {
        # 失败时把现场留下来：完整状态（含质量计数）+ 程序最近日志，省得下次还要重新猜。
        Write-Host ("  现场状态：" + ($status2 | ConvertTo-Json -Compress -Depth 6))
        try { Write-Host ("  假源 job 状态：" + (Get-Job $fakeJob.Id).State) } catch { }
        try {
            $ls = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
                Where-Object { $_.Port -eq $FakePort -or $_.Port -eq ($FakePort + 1) }
            foreach ($l in $ls) { Write-Host ("  监听中：" + $l) }
        } catch { }
        try {
            $fakeOut = Receive-Job $fakeJob -Keep 2>&1 | Select-Object -Last 8
            foreach ($line in $fakeOut) { Write-Host ("  假源：" + $line) }
        } catch { }
        try {
            $tail = (Get-Api '/api/logs?lines=10').Result
            foreach ($line in $tail.Lines) { Write-Host ("  日志：" + $line) }
        } catch {
            Write-Host ("  日志读取失败：" + $_.Exception.Message)
        }
    }
    Check '这一路这次真的在播了' ($status2.Count -eq 1 -and $status2[0].Playing) ("Playing=" + ($status2 | ForEach-Object { $_.Playing }) + " Error=" + ($status2 | ForEach-Object { $_.Error }))
    Check '它播的正是设备 E' ($status2.Count -eq 1 -and $status2[0].TargetDeiceID -eq $virt.ID) ("实际：" + ($status2 | ForEach-Object { $_.TargetDeiceID }))
    $probe = (Get-Api ('/api/probe?device=' + [uri]::EscapeDataString($virt.ID))).Result
    Check '冲突自检认为这个设备已被占住' ($probe.Wired -and $probe.Blocked) ("Wired=" + $probe.Wired + " Blocked=" + $probe.Blocked)

    $blocked = Invoke-Order -Port $tcpPort -DeviceId $virt.ID -DeviceName $virt.Name
    [void]$orders.Add($blocked)
    Check '点单正在被网络播放的设备被拦下' ($blocked.Reply -eq '/Reject/loop') ("实际应答：" + $blocked.Reply)

    $allowed = Invoke-Order -Port $tcpPort -DeviceId $other.ID -DeviceName $other.Name
    [void]$orders.Add($allowed)
    Check '点单另一个设备不受影响' ($allowed.IsBinary) ("实际应答：" + $allowed.Reply)

    Close-Order $blocked
    Close-Order $allowed
} finally {
    Section '清理'
    foreach ($order in @($orders)) { Close-Order $order }
    foreach ($id in $addedPlayerIds) {
        try { Post-Api ('/api/del?id=' + $id) | Out-Null } catch { }
    }
    if ($exe -and -not $exe.HasExited) {
        try { $exe.Kill(); $exe.WaitForExit(5000) | Out-Null } catch { }
    }
    if ($fakeJob) {
        try { Stop-Job $fakeJob -ErrorAction SilentlyContinue } catch { }
        try { Remove-Job $fakeJob -Force -ErrorAction SilentlyContinue } catch { }
    }
    Start-Sleep -Milliseconds 500
    Write-Host '  已停止被测程序与假音频源，并删掉本次加的播放记录'
}

Write-Host ''
if ($script:Fail -eq 0) {
    Write-Host ("全部通过：" + $script:Pass + " 项") -ForegroundColor Green
} else {
    Write-Host ("通过 " + $script:Pass + " 项，失败 " + $script:Fail + " 项") -ForegroundColor Red
    $global:LASTEXITCODE = 1
}
