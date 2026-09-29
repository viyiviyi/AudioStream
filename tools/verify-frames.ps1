# 验证音频传输的帧协议。
#
# 背景：音频原来走裸字节流，两端靠「这一包读到的字节数 ≤ 32 就是心跳」这种长度猜测
# 来区分控制包和音频包。TCP 没有消息边界，这个猜测一定会偶尔吃掉音频字节、破坏采样对齐，
# 听感上就是杂音；服务端心跳更狠，直接往音频流里写 4 个零字节。
# 现在改成 [4 字节小端长度][1 字节类型][负载] 的帧，这个脚本就是来验「真的按帧走」。
#
# 做法：脚本自己扮成一个拉取方，直接用 TCP 连本机的音频端口走帧协议。
# 全程只收数据不播放，所以不会有任何声音；采集会被启动一下（共享模式，不独占），
# 脚本结束时会关掉连接让采集自己释放。
#
# 用法：& "<绝对路径>\tools\verify-frames.ps1"

param(
    [string]$ExePath
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $ExePath) { $ExePath = Join-Path $root 'AudioStream\bin\Debug\AudioStream.exe' }

$configDir = Join-Path $env:LOCALAPPDATA 'yiyiooo\AudioStream'
$playersPath = Join-Path $configDir 'players.json'
$playersBackup = Join-Path $configDir 'players.json.verify-frames-backup'
# 跑之前用户原有的自启项值。收尾时只删「本次启动新写进去的那一条」，
# 用户自己设的（哪怕指向同一个 exe）不能动。
$runBefore = $null
$httpBase = 'http://127.0.0.1:12570'

$FrameAudio = 1
$FrameText = 2
$FrameWaveFormat = 3

# 假的机器身份，长得像真的，但绝不会等于本机身份
$fakeMachine = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb1'
$fakePc = '验证用帧协议拉取方'

$script:failed = 0
$script:checks = 0
$script:tcpPort = 12670
$script:runOk = $true

function Write-Check([string]$name, [bool]$ok, [string]$detail) {
    $script:checks++
    if ($ok) {
        Write-Host ("[通过] " + $name)
    } else {
        $script:failed++
        Write-Host ("[失败] " + $name + "  -> " + $detail) -ForegroundColor Red
    }
}

function Stop-Agent {
    Get-Process AudioStream -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 400
}

function Start-Agent {
    $proc = Start-Process -FilePath $ExePath -PassThru
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 250
        try {
            $info = Invoke-RestMethod "$httpBase/api/info" -TimeoutSec 2
            $script:tcpPort = [int]$info.Result.TcpPort
            return $proc
        } catch { }
    }
    throw '配置界面没有起来，拿不到 /api/info'
}

# ---- 帧读写：与 C# 侧 AudioFraming 一一对应 ----

function Read-Exact($stream, [byte[]]$buffer, [int]$count) {
    $done = 0
    while ($done -lt $count) {
        $read = $stream.Read($buffer, $done, $count - $done)
        if ($read -le 0) { return $false }
        $done += $read
    }
    return $true
}

function Write-Frame($stream, [byte]$kind, [byte[]]$payload) {
    $total = 1 + $payload.Length
    $header = New-Object 'byte[]' 5
    $lenBytes = [BitConverter]::GetBytes([int]$total)
    [Array]::Copy($lenBytes, 0, $header, 0, 4)
    $header[4] = $kind
    $stream.Write($header, 0, 5)
    if ($payload.Length -gt 0) { $stream.Write($payload, 0, $payload.Length) }
    $stream.Flush()
}

function Write-TextFrame($stream, [string]$text) {
    Write-Frame $stream $FrameText ([System.Text.Encoding]::UTF8.GetBytes($text))
}

# 读一帧。返回 @{ Kind; Payload } 或 $null（对端关了连接）
function Read-Frame($stream) {
    $header = New-Object 'byte[]' 5
    if (-not (Read-Exact $stream $header 5)) { return $null }
    $total = [BitConverter]::ToInt32($header, 0)
    $kind = $header[4]
    if ($total -lt 1 -or $total -gt 1048576) { return $null }
    $payload = New-Object 'byte[]' ($total - 1)
    if ($payload.Length -gt 0) {
        if (-not (Read-Exact $stream $payload $payload.Length)) { return $null }
    }
    return @{ Kind = $kind; Payload = $payload }
}

function Frame-Text($frame) {
    return [System.Text.Encoding]::UTF8.GetString($frame.Payload)
}

function Connect-Agent([int]$timeoutMs = 3000) {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.NoDelay = $true
    $iar = $client.BeginConnect('127.0.0.1', $script:tcpPort, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne($timeoutMs)) {
        $client.Close()
        throw "连不上 127.0.0.1:$($script:tcpPort)"
    }
    $client.EndConnect($iar)
    # 读超时压到 2 秒：一帧都收不到时要能很快失败并继续跑后面的用例，
    # 而不是卡满一个长超时把整轮验证拖住。
    $client.ReceiveTimeout = 2000
    $client.SendTimeout = 5000
    return $client
}

# 走到「点单完成、格式已拿到」为止
function Invoke-Order($client, [string]$deviceId) {
    $stream = $client.GetStream()
    Write-TextFrame $stream ("/Hello/" + $fakeMachine + "/" + [uri]::EscapeDataString($fakePc))
    $reply = Read-Frame $stream
    if ($reply -eq $null -or $reply.Kind -ne $FrameText) { return @{ Ok = $false; Why = '握手没有回文本帧' } }
    $hello = Frame-Text $reply
    if (-not $hello.StartsWith('/Hello/')) { return @{ Ok = $false; Why = ('握手被回绝：' + $hello) } }
    Write-TextFrame $stream ("/WaveFormat/" + $deviceId + "/0")
    $fmt = Read-Frame $stream
    if ($fmt -eq $null) { return @{ Ok = $false; Why = '点单后连接被关，什么都没收到' } }
    if ($fmt.Kind -eq $FrameText) { return @{ Ok = $false; Why = ('点单被回绝：' + (Frame-Text $fmt)) } }
    if ($fmt.Kind -ne $FrameWaveFormat) { return @{ Ok = $false; Why = ('点单回的不是格式帧，类型=' + $fmt.Kind) } }
    if ($fmt.Payload.Length -lt 16) { return @{ Ok = $false; Why = ('格式负载太短：' + $fmt.Payload.Length) } }
    $sampleRate = [BitConverter]::ToInt32($fmt.Payload, 0)
    $bits = [BitConverter]::ToInt32($fmt.Payload, 4)
    $channels = [BitConverter]::ToInt32($fmt.Payload, 8)
    return @{ Ok = $true; Stream = $stream; SampleRate = $sampleRate; Bits = $bits; Channels = $channels }
}

# ---- 主流程 ----

$proc = $null
try {
    Stop-Agent
    try { $runBefore = (Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'AudioStream.exe' -ErrorAction SilentlyContinue).'AudioStream.exe' } catch { }
    if (Test-Path $playersPath) { Copy-Item $playersPath $playersBackup -Force }
    $proc = Start-Agent

    $devices = (Invoke-RestMethod "$httpBase/api/devices" -TimeoutSec 5).Result
    # 优先点单输入设备（麦克风）：它一直在产生采样（哪怕只是底噪），一定有数据流。
    # 输出设备的环回采集只在设备上真的有人放声音时才有回调，
    # 本机此刻没在放音，点输出设备会一帧都收不到——那是环境问题，不是协议问题。
    $outDevice = @($devices | Where-Object { $_.Flow -eq 'input' })[0]
    if (-not $outDevice) { $outDevice = @($devices | Where-Object { $_.Flow -eq 'output' })[0] }
    if (-not $outDevice) { throw '本机没有可用音频设备，无法点单' }

    Write-Host ('被测 exe：' + $ExePath)
    Write-Host ('音频端口：' + $script:tcpPort + '；点单设备：' + $outDevice.Name + '（' + $outDevice.Flow + '）')
    Write-Host ''

    # 1. 握手
    $client = Connect-Agent
    $order = Invoke-Order $client $outDevice.ID
    Write-Check '点单能拿到音频格式帧' $order.Ok $order.Why
    if (-not $order.Ok) { throw $order.Why }

    $fmt = @{ SampleRate = $order.SampleRate; Bits = $order.Bits; Channels = $order.Channels }
    Write-Check ('格式为可用的 PCM（' + $fmt.SampleRate + 'Hz/' + $fmt.Bits + 'bit/' + $fmt.Channels + '声道）') `
        (($fmt.SampleRate -ge 8000) -and ($fmt.SampleRate -le 384000) -and
         ($fmt.Bits -in @(8, 16, 24, 32)) -and ($fmt.Channels -ge 1) -and ($fmt.Channels -le 8)) `
        ('采样率=' + $fmt.SampleRate + ' 位深=' + $fmt.Bits + ' 声道=' + $fmt.Channels)

    $blockAlign = $fmt.Channels * [int]($fmt.Bits / 8)
    $bytesPerSecond = $fmt.SampleRate * $blockAlign

    # 2. /Start 之后收音频帧
    $stream = $order.Stream
    Write-TextFrame $stream '/Start'

    $audioFrames = New-Object System.Collections.ArrayList
    $audioBytes = 0
    $badAlign = 0
    $badFrames = New-Object System.Collections.ArrayList
    $textDuringAudio = New-Object System.Collections.ArrayList

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt 1500) {
        # 读超时说明这一段没有数据了：记为「收不到」继续往下跑，
        # 让后面的结论也能一起看到，不要一超时就把整轮打断
        $frame = $null
        try { $frame = Read-Frame $stream } catch { break }
        if ($frame -eq $null) { break }
        if ($frame.Kind -eq $FrameText) {
            [void]$textDuringAudio.Add((Frame-Text $frame))
            continue
        }
        if ($frame.Kind -ne $FrameAudio) { continue }
        [void]$audioFrames.Add($frame.Payload.Length)
        $audioBytes += $frame.Payload.Length
        if (($frame.Payload.Length % $blockAlign) -ne 0) {
            $badAlign++
            if ($badFrames.Count -lt 3) { [void]$badFrames.Add($frame.Payload.Length) }
        }
    }
    $sw.Stop()

    Write-Check ('收到音频帧（' + $audioFrames.Count + ' 帧 / ' + $audioBytes + ' 字节）') `
        ($audioFrames.Count -ge 10 -and $audioBytes -gt 0) `
        ('帧数=' + $audioFrames.Count + ' 字节=' + $audioBytes)

    Write-Check ('每个音频帧的长度都是帧对齐的（blockAlign=' + $blockAlign + '）') `
        ($badAlign -eq 0) `
        ('有 ' + $badAlign + ' 帧不对齐，例如：' + ($badFrames -join ', '))

    # 音频时长大致等于墙上时钟：差太多说明数据被吞了或被重复
    $audioSeconds = $audioBytes / $bytesPerSecond
    $wallSeconds = $sw.ElapsedMilliseconds / 1000.0
    $ratio = if ($wallSeconds -gt 0) { $audioSeconds / $wallSeconds } else { 0 }
    Write-Check ('收到的音频时长与真实时间相符（比值 ' + [Math]::Round($ratio, 2) + '）') `
        ($ratio -gt 0.5 -and $ratio -lt 1.5) `
        ('音频 ' + [Math]::Round($audioSeconds, 2) + ' 秒 / 实际 ' + [Math]::Round($wallSeconds, 2) + ' 秒')

    # 3. 心跳。服务端只在「超过 10 秒没往这条连接发过数据」时才回 /Pong（见 TcpServer 的 /Ping 分支），
    #    音频正在流的时候不回。所以这里要验的不是「一定回 Pong」，
    #    而是「发心跳不会污染音频流」：音频必须继续、仍然按帧对齐。
    #    旧实现正是在这里往音频流里直接写 4 个零字节，对端只能靠长度猜，猜错就切出错位。
    Write-TextFrame $stream '/Ping'
    $afterPingFrames = 0
    $afterPingBadAlign = 0
    $afterPingTexts = New-Object System.Collections.ArrayList
    $sw2 = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw2.ElapsedMilliseconds -lt 1500) {
        $frame = $null
        try { $frame = Read-Frame $stream } catch { break }
        if ($frame -eq $null) { break }
        if ($frame.Kind -eq $FrameText) {
            [void]$afterPingTexts.Add((Frame-Text $frame))
            continue
        }
        if ($frame.Kind -ne $FrameAudio) { continue }
        $afterPingFrames++
        if (($frame.Payload.Length % $blockAlign) -ne 0) { $afterPingBadAlign++ }
    }
    $sw2.Stop()

    Write-Check ('发心跳之后音频继续、仍然按帧对齐（' + $afterPingFrames + ' 帧）') `
        ($afterPingFrames -ge 5 -and $afterPingBadAlign -eq 0) `
        ('帧数=' + $afterPingFrames + ' 不对齐=' + $afterPingBadAlign)

    $unexpected = @(@($textDuringAudio) + @($afterPingTexts) | Where-Object { $_ -ne '/Pong' })
    Write-Check '音频期间没有意外的文本帧混进流里（心跳只可能是 /Pong）' ($unexpected.Count -eq 0) ($unexpected -join ', ')

    # 4. 关连接后本机应该把采集放掉
    # 只断言「本次点单的那个设备」被放掉：这台机器上可能同时有别的机器在拉别的设备
    # （比如另一台真机正拉着本机的扬声器），那跟本次测试无关，不该算失败。
    $client.Close()
    Start-Sleep -Milliseconds 800
    $captures = (Invoke-RestMethod "$httpBase/api/captures" -TimeoutSec 5).Result
    $stillMine = @($captures | Where-Object { $_ -eq $deviceId })
    Write-Check '断开后本次点单的采集已释放' ($stillMine.Count -eq 0) ('残留采集：' + (@($captures) -join ', '))

    # 5. 点单一个不存在的设备：应当直接断开，而不是回一堆垃圾
    $client2 = Connect-Agent
    $stream2 = $client2.GetStream()
    Write-TextFrame $stream2 ("/Hello/" + $fakeMachine + "/" + [uri]::EscapeDataString($fakePc))
    [void](Read-Frame $stream2)
    Write-TextFrame $stream2 '/WaveFormat/这是一块不存在的设备/0'
    $bad = Read-Frame $stream2
    Write-Check '点单不存在的设备会被关连接（不是回垃圾数据）' ($bad -eq $null) ('却收到了类型=' + $(if ($bad) { $bad.Kind } else { 'null' }))
    $client2.Close()

    # 6. 自连：报上本机身份，应当被 /Reject/self 挡掉
    $me = (Invoke-RestMethod "$httpBase/api/info" -TimeoutSec 5).Result
    $client3 = Connect-Agent
    $stream3 = $client3.GetStream()
    Write-TextFrame $stream3 ("/Hello/" + $me.MachineId + "/" + [uri]::EscapeDataString('冒充本机'))
    $selfReply = Read-Frame $stream3
    $selfText = if ($selfReply -ne $null -and $selfReply.Kind -eq $FrameText) { Frame-Text $selfReply } else { '<非文本帧>' }
    Write-Check '报本机身份会被 /Reject/self 挡住' ($selfText -eq '/Reject/self') ('收到：' + $selfText)
    $client3.Close()

    # 7. 空闲连接的心跳：超过 10 秒没往这条连接发过数据时，服务端必须回一条 /Pong 文本帧。
    #    这正是原来那个「往音频流里写 4 个零字节」的位置，现在走的是正经帧。
    $client4 = Connect-Agent
    $stream4 = $client4.GetStream()
    Write-TextFrame $stream4 ("/Hello/" + $fakeMachine + "/" + [uri]::EscapeDataString($fakePc))
    [void](Read-Frame $stream4)
    Start-Sleep -Seconds 11
    Write-TextFrame $stream4 '/Ping'
    $idleReply = $null
    try { $idleReply = Read-Frame $stream4 } catch { }
    $idleText = if ($idleReply -ne $null -and $idleReply.Kind -eq $FrameText) { Frame-Text $idleReply } else { '<非文本帧或没有应答>' }
    Write-Check '空闲连接的心跳得到 /Pong 文本帧应答' ($idleText -eq '/Pong') ('收到：' + $idleText)
    $client4.Close()
}
catch {
    $script:runOk = $false
    Write-Host ('执行中断：' + $_.Exception.Message) -ForegroundColor Red
}
finally {
    Stop-Agent
    if (Test-Path $playersBackup) {
        Copy-Item $playersBackup $playersPath -Force
        Remove-Item $playersBackup -Force
    }
    # 只清理本次写进去的自启项：跑之前没有、现在等于被测 exe 才删，用户原有的自启项不动
    try {
        $run = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -ErrorAction Stop
        $runNow = $run.'AudioStream.exe'
        if ($runNow -and $runNow -ne $runBefore -and $runNow -eq $ExePath) {
            Remove-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'AudioStream.exe' -ErrorAction SilentlyContinue
        }
    } catch { }
}

Write-Host ''
Write-Host ("结果：" + ($script:checks - $script:failed) + '/' + $script:checks + ' 通过')
if ($script:failed -gt 0 -or -not $script:runOk) { exit 1 }
exit 0
