<#
    验证「接收侧：断线自动重连与状态上报」。

    全程在本机跑，不需要第二台机器。用一个假的音频对端来当「另一台电脑」：
    它按固定节奏把已有的连接掐掉再重新接受连接，模拟对端程序重启；
    服务过若干轮之后改成回 /Reject/loop，用来验证拒绝原因能读懂。

    做四件事：
      1. 基线：加一条拉取记录后能真的播起来，State=playing、Attempts=0。
      2. 断开被察觉：对端掐断连接后，状态要变成没在播、Attempts 涨起来。
      3. 自动恢复：对端重新可用后，不用用户动手就能自己播回去，Attempts 归零。
      4. 拒绝原因可读：对端回 /Reject/loop 时，错误里要能看出是回路，而不是一串原始命令。
      5. 对端主机不可达时，一轮重连不能被拖成好几秒（Attempts 要在十秒内涨到 3 以上）。

    注意：假对端只推静音，不会有声音；播放设备优先挑虚拟声卡。
    脚本会临时加两条播放记录，结束时删掉；被测程序原有的记录不会被改动。
#>
param(
    [string]$ExePath = '',
    [int]$HttpPort = 12570,
    [int]$PeerPort = 12670,
    [int]$CycleSeconds = 6,
    # 连接次数到多少之后开始拒绝。第三节改用独立的「一律拒绝」对端来构造拒绝，
    # 所以这个阈值只作兜底：设小会让第二节自己撞上拒绝（假对端每 6 秒掐一次，
    # 第二节要跑二十多秒，早就过 3 轮了），这里取一个实际到不了的值。
    [int]$RejectAfterCycles = 9999
)

$ErrorActionPreference = 'Stop'
$script:Pass = 0
$script:Fail = 0

# 本机自己的 IPv4 列表。假对端只服务本机发起的连接——局域网里别的机器也会来连这个端口，
# 而这个假对端一次只服务一条连接，被它们占住就会让被测程序一直连不上（见 Start-FakePeer 里的注释）。
$LocalAddresses = @([System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
    Where-Object { $_.AddressFamily -eq 'InterNetwork' } | ForEach-Object { $_.ToString() })

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
    param([int]$TimeoutSeconds = 25)
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
    param([int]$Port, [int]$TimeoutSeconds = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($ep in [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()) {
            if ($ep.Port -eq $Port) { return $true }
        }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

# ---------- 假对端 ----------
# 扮演「另一台电脑」：对外报一个固定身份，按节奏服务连接，到点就掐断重来。
# 掐断而不是停掉监听，是为了模拟「对端程序重启」——端口一直在，连接断了又回来。
function Start-FakePeer {
    param([int]$Port, [int]$CycleSeconds, [int]$RejectAfterCycles, [bool]$AlwaysReject = $false, [string[]]$LocalAddresses = @())

    # 作业跑在独立进程里，靠 InitializationScript 把帧工具引进去
    $lib = Join-Path $PSScriptRoot 'AudioFraming.ps1'
    $init = [scriptblock]::Create(". '" + $lib + "'")
    return Start-Job -ArgumentList $Port, $CycleSeconds, $RejectAfterCycles, $AlwaysReject, $LocalAddresses -InitializationScript $init -ScriptBlock {
        param($port, $cycleSeconds, $rejectAfterCycles, $alwaysReject, $localAddresses)

        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $port)
        try {
            $listener.Server.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
        } catch { }
        $listener.Start()

        $silence = New-Object byte[] 7680
        $cycle = 0
        while ($true) {
            $client = $listener.AcceptTcpClient()
            # 只服务「本机发起」的连接。这个假对端是单线程的、一次只服务一条连接，
            # 而局域网里别的机器（真机）也会来连这个端口：被它们占住，被测程序就一直排在后面，
            # 表现出来是「重连一直不成功」——那测的是测试环境的干扰，不是重连机制本身。
            $remote = $client.Client.RemoteEndPoint.Address.ToString()
            if (-not ($remote -eq '127.0.0.1' -or $localAddresses -contains $remote)) {
                try { $client.Close() } catch { }
                continue
            }
            $cycle++
            $client.NoDelay = $true
            try {
                $stream = $client.GetStream()
                $stream.ReadTimeout = 3000
                $reject = $alwaysReject -or ($cycle -gt $rejectAfterCycles)
                $deadline = (Get-Date).AddSeconds($cycleSeconds)
                $streaming = $false
                $done = $false

                while (-not $done -and (Get-Date) -lt $deadline) {
                    if ($streaming) {
                        try { Write-Frame -Stream $stream -Kind 1 -Payload $silence } catch { break }
                        Start-Sleep -Milliseconds 20
                        continue
                    }
                    # 没有数据就轮询等一会儿，这样 deadline 才有机会生效；
                    # 一旦有数据就把整帧读完——不能在读帧中间超时退出，
                    # 那样已经读掉的半个帧头就丢了，后面整条流都会错位，
                    # 表现是「假对端忽然不理人」，测出来的是脚本自己的毛病而不是重连机制。
                    if (-not $stream.DataAvailable) {
                        Start-Sleep -Milliseconds 10
                        continue
                    }
                    $frame = $null
                    try { $frame = Read-Frame -Stream $stream } catch { break }
                    if ($null -eq $frame) { break }
                    if ($frame.Kind -ne 2) { continue }
                    $line = [System.Text.Encoding]::UTF8.GetString($frame.Payload)
                    if ($line.StartsWith('/Hello/')) {
                        Write-TextFrame -Stream $stream -Text '/Hello/22222222222222222222222222222222/PS-FakePeer'
                    } elseif ($line.StartsWith('/WaveFormat/')) {
                        if ($reject) {
                            Write-TextFrame -Stream $stream -Text '/Reject/loop'
                            $done = $true
                        } else {
                            $ms = New-Object System.IO.MemoryStream
                            $bw = New-Object System.IO.BinaryWriter($ms)
                            # 顺序必须和真实服务端一致：采样率、位深、声道数、编码。
                            # 位深小于 8 会让客户端算出的字节率变成 0，播放器一上来就报错。
                            $bw.Write([int]48000)
                            $bw.Write([int]32)
                            $bw.Write([int]2)
                            $bw.Write([int]3)   # IeeeFloat
                            $bw.Flush()
                            Write-FormatFrame -Stream $stream -Payload $ms.ToArray()
                        }
                    } elseif ($line.StartsWith('/Start')) {
                        $streaming = $true
                    } elseif ($line.StartsWith('/Pause')) {
                        $done = $true
                    }
                }
            } catch {
            } finally {
                try { $client.Close() } catch { }
            }
        }
    }
}

# ---------- 准备 ----------

if ([string]::IsNullOrWhiteSpace($ExePath)) {
    $ExePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'AudioStream\bin\Debug\AudioStream.exe'
}
if (-not (Test-Path $ExePath)) { throw "找不到被测程序：$ExePath" }
$ExePath = (Resolve-Path $ExePath).Path

Write-Host ("被测程序：" + $ExePath)

foreach ($ep in [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()) {
    if ($ep.Port -eq $HttpPort) {
        throw "端口 $HttpPort 已被占用，请先退出正在运行的 AudioStream 再跑本脚本。"
    }
}

$runKey = 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run'
$runBefore = $null
try { $runBefore = (Get-ItemProperty -Path $runKey -Name 'AudioStream.exe' -ErrorAction SilentlyContinue).'AudioStream.exe' } catch { }

$exe = $null
$fakeJob = $null
$addedPlayerIds = New-Object System.Collections.ArrayList
$beforePlayerIds = @()

try {
    Section '启动假对端与被测程序'
    Get-Process AudioStream -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 500
    if (-not (Wait-PortFree -Port $PeerPort -TimeoutSeconds 5)) {
        throw "端口 $PeerPort 已被占用，假对端起不来。"
    }

    # 先让假对端占住 12670，被测程序就会顺延到 12671 监听。
    # 这样它去连「本机地址」时命中的始终是假对端，不会连到自己。
    $fakeJob = Start-FakePeer -Port $PeerPort -CycleSeconds $CycleSeconds -RejectAfterCycles $RejectAfterCycles -LocalAddresses $LocalAddresses
    if (-not (Wait-PortUsed -Port $PeerPort -TimeoutSeconds 20)) { throw '假对端没有在监听' }
    Check '假对端已在监听' $true

    $exe = Start-Process -FilePath $ExePath -PassThru
    $info = Wait-Api
    if ($null -eq $info) { throw '被测程序起来后 /api/info 一直没有响应' }
    Write-Host ("  本机身份：" + $info.MachineId + "  计算机名：" + $info.PcName + "  TCP 端口：" + $info.TcpPort)
    Check '被测程序顺延到下一个端口监听' ([int]$info.TcpPort -ne $PeerPort) ("实际 TCP 端口：" + $info.TcpPort)
    Check '报告了本机 IPv4 地址' (@($info.Addresses).Count -ge 1) ("Addresses=" + ($info.Addresses -join ', '))

    $selfIp = @($info.Addresses)[0]

    $devices = @((Get-Api '/api/devices').Result)
    Check '能列出本机输出设备' ($devices.Count -ge 1) ("设备数 " + $devices.Count)
    if ($devices.Count -lt 1) { throw '没有可用的输出设备，无法继续' }

    # 挑虚拟声卡当播放设备，静音播放不会吵到人
    $playDevice = $devices | Where-Object { $_.Name -match 'Virtual|AudioRelay|UU|虚拟' } | Select-Object -First 1
    if ($null -eq $playDevice) { $playDevice = $devices[0] }
    $sourceDevice = $devices | Where-Object { $_.ID -ne $playDevice.ID } | Select-Object -First 1
    if ($null -eq $sourceDevice) { $sourceDevice = $playDevice }
    Write-Host ("  播放设备：" + $playDevice.Name)

    $beforePlayerIds = @((Get-Api '/api/players').Result | ForEach-Object { $_.ID })

    Section '一、加一条拉取记录并播起来'
    Post-Api ('/api/add_player?ip=' + $selfIp + '&s_device=' + [uri]::EscapeDataString($sourceDevice.ID) + '&t_device=' + [uri]::EscapeDataString($playDevice.ID) + '&s_device_name=' + [uri]::EscapeDataString($sourceDevice.Name) + '&t_device_name=' + [uri]::EscapeDataString($playDevice.Name)) | Out-Null
    $newPlayers = @((Get-Api '/api/players').Result | Where-Object { $beforePlayerIds -notcontains $_.ID })
    foreach ($p in $newPlayers) { [void]$addedPlayerIds.Add($p.ID) }
    Check '播放记录已加上' ($newPlayers.Count -eq 1) ("新增 " + $newPlayers.Count + " 条")
    if ($newPlayers.Count -ne 1) { throw '没有拿到新增的播放记录，无法继续' }
    $playerId = $newPlayers[0].ID

    $played = $false
    $deadline = (Get-Date).AddSeconds(15)
    $st = $null
    while ((Get-Date) -lt $deadline) {
        $st = @((Get-Api '/api/play-status').Result | Where-Object { $_.ID -eq $playerId })
        if ($st.Count -eq 1 -and $st[0].Playing) { $played = $true; break }
        Start-Sleep -Milliseconds 300
    }
    Check '这一路已经真的在播' $played ("State=" + ($st | ForEach-Object { $_.State }) + " Error=" + ($st | ForEach-Object { $_.Error }))
    Check '状态里说它正处在 playing' ($st.Count -eq 1 -and $st[0].State -eq 'playing') ("State=" + ($st | ForEach-Object { $_.State }))
    Check '对端身份已经显示出来' ($st.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($st[0].Peer)) ("Peer=" + ($st | ForEach-Object { $_.Peer }))

    Section '二、断开被察觉，然后自己接回来'
    # 假对端每 CycleSeconds 秒掐一次连接，采样窗口要能跨过至少一次掐断。
    # 本机重连很快：断开到重新播起来常常只有几十毫秒，Attempts 从涨起来到归零就在这中间发生。
    # 采样间隔必须远小于这个窗口（实测 100ms 仍会整段漏掉，10ms 才稳定采到）。
    $samples = New-Object System.Collections.ArrayList
    $sampleEnd = (Get-Date).AddSeconds(14)
    while ((Get-Date) -lt $sampleEnd) {
        $s = @((Get-Api '/api/play-status').Result | Where-Object { $_.ID -eq $playerId })
        if ($s.Count -eq 1) { [void]$samples.Add($s[0]) }
        Start-Sleep -Milliseconds 10
    }
    Write-Host ("  采样 " + $samples.Count + " 次")
    Check '采样期间看到过它在播' (@($samples | Where-Object { $_.Playing }).Count -gt 0)
    Check '对端断开后状态变成没在播' (@($samples | Where-Object { -not $_.Playing }).Count -gt 0)
    Check '断开之后开始自动重试（Attempts 涨起来）' (@($samples | Where-Object { $_.Attempts -ge 1 }).Count -gt 0) ("最大 Attempts=" + (@($samples | Measure-Object -Property Attempts -Maximum).Maximum))
    Check '重试期间状态不再自称 playing' (@($samples | Where-Object { $_.Attempts -ge 1 -and $_.State -eq 'playing' }).Count -eq 0)

    $recovered = $false
    # 等待窗口必须盖过最长退避：重试间隔是 1s 起、最多 5s（Player.MaxBackoffSeconds），
    # 再加上一次连接尝试与起播。原来只等 10 秒，赶上退避到第 4、5 轮时窗口不够，
    # 就会在「马上要接上」的前一刻判定失败。
    $deadline = (Get-Date).AddSeconds(25)
    $st2 = $null
    while ((Get-Date) -lt $deadline) {
        $st2 = @((Get-Api '/api/play-status').Result | Where-Object { $_.ID -eq $playerId })
        if ($st2.Count -eq 1 -and $st2[0].Playing) { $recovered = $true; break }
        Start-Sleep -Milliseconds 300
    }
    Check '对端回来之后自己接上了，不需要用户动手' $recovered ("State=" + ($st2 | ForEach-Object { $_.State }) + " Error=" + ($st2 | ForEach-Object { $_.Error }) + " 完整状态=" + ($st2 | ConvertTo-Json -Compress -Depth 6))
    Check '接上之后重试计数归零' ($st2.Count -eq 1 -and $st2[0].Attempts -eq 0) ("Attempts=" + ($st2 | ForEach-Object { $_.Attempts }))

    Section '三、对端拒绝时，原因要能读懂'
    # 这一节单独换一个「一律拒绝」的假对端。原先靠连接次数累积来触发拒绝，
    # 结果是第二节（要它还没到）和第三节（要它已经过）在抢同一个阈值，
    # 重连节奏稍有变化就两边都不满足，跑成时好时坏。两节各自用一个对端之后互不干扰。
    if ($fakeJob) {
        try { Stop-Job $fakeJob -ErrorAction SilentlyContinue } catch { }
        try { Remove-Job $fakeJob -Force -ErrorAction SilentlyContinue } catch { }
        Start-Sleep -Milliseconds 500
    }
    $fakeJob = Start-FakePeer -Port $PeerPort -CycleSeconds $CycleSeconds -RejectAfterCycles $RejectAfterCycles -AlwaysReject $true -LocalAddresses $LocalAddresses
    Start-Sleep -Seconds 1

    $readable = $false
    $st3 = $null
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        $st3 = @((Get-Api '/api/play-status').Result | Where-Object { $_.ID -eq $playerId })
        if ($st3.Count -eq 1 -and $st3[0].Error -match '回路') { $readable = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Check '拒绝原因里能看出是回路，而不是一串原始命令' $readable ("Error=" + ($st3 | ForEach-Object { $_.Error }))

    Section '四、对端主机不可达时，一轮重连不能被拖长'
    $parts = $selfIp.Split('.')
    $ghostIp = $parts[0] + '.' + $parts[1] + '.' + $parts[2] + '.254'
    if ($ghostIp -eq $selfIp) { $ghostIp = $parts[0] + '.' + $parts[1] + '.' + $parts[2] + '.253' }
    Write-Host ("  不可达地址：" + $ghostIp)

    Post-Api ('/api/add_player?ip=' + $ghostIp + '&s_device=' + [uri]::EscapeDataString($sourceDevice.ID) + '&t_device=' + [uri]::EscapeDataString($playDevice.ID) + '&s_device_name=' + [uri]::EscapeDataString($sourceDevice.Name) + '&t_device_name=' + [uri]::EscapeDataString($playDevice.Name)) | Out-Null
    $ghostPlayers = @((Get-Api '/api/players').Result | Where-Object { $beforePlayerIds -notcontains $_.ID -and $_.IP -eq $ghostIp })
    foreach ($p in $ghostPlayers) { [void]$addedPlayerIds.Add($p.ID) }
    Check '不可达地址的播放记录已加上' ($ghostPlayers.Count -eq 1) ("新增 " + $ghostPlayers.Count + " 条")
    if ($ghostPlayers.Count -eq 1) {
        $ghostId = $ghostPlayers[0].ID
        Start-Sleep -Seconds 10
        $ghostStatus = @((Get-Api '/api/play-status').Result | Where-Object { $_.ID -eq $ghostId })
        $ghostAttempts = 0
        if ($ghostStatus.Count -eq 1) { $ghostAttempts = $ghostStatus[0].Attempts }
        Check '十秒内至少重试三轮（说明没被一次连接拖住）' ($ghostAttempts -ge 3) ("Attempts=" + $ghostAttempts)
        Check '不可达时状态是没在播' ($ghostStatus.Count -eq 1 -and -not $ghostStatus[0].Playing)
        Check '不可达时给出了可读原因' ($ghostStatus.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($ghostStatus[0].Error)) ("Error=" + ($ghostStatus | ForEach-Object { $_.Error }))
    }
} finally {
    Section '清理'
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
    try {
        $runNow = (Get-ItemProperty -Path $runKey -Name 'AudioStream.exe' -ErrorAction SilentlyContinue).'AudioStream.exe'
        if ($runNow -and $runNow -ne $runBefore -and $runNow -eq $ExePath) {
            Remove-ItemProperty -Path $runKey -Name 'AudioStream.exe' -ErrorAction SilentlyContinue
            Write-Host '  已清理测试期间写入的开机自启项'
        }
    } catch { }
    Write-Host '  已停止被测程序与假对端，并删掉本次加的播放记录'
}

Write-Host ''
if ($script:Fail -eq 0) {
    Write-Host ("全部通过：" + $script:Pass + " 项") -ForegroundColor Green
} else {
    Write-Host ("通过 " + $script:Pass + " 项，失败 " + $script:Fail + " 项") -ForegroundColor Red
    $global:LASTEXITCODE = 1
}
