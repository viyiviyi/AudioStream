# 验证来源端两个「跟随系统默认」的点单路径。
#
# 背景：来源下拉里多了两条跟随项——「系统默认输出设备」（环回录默认扬声器正在放的声音）
# 与「系统默认输入设备」（录默认麦克风）。它们的设备号是特殊值 default / default-input，
# 由被拉取侧（也就是本机）解析成此刻真实的设备号，所以本机换了默认扬声器或默认麦克风，
# 对方那条拉取会自动跟着走，不必回来重配。
#
# 这个脚本要验的就是这条链路：点单 default 真的落到「本机此刻的默认输出设备」上，
# default-input 真的落到默认输入设备上；解析不出来时要回一条说得出原因的拒绝，
# 而不是把空设备号交给声卡去抛一个更难懂的异常。
#
# 做法：脚本自己扮成一个拉取方，直接用 TCP 连本机的音频端口走握手与点单，
# 全程只读到「拿到格式帧」为止，从不发 /Start——采集不会真正启动，也不会有任何声音。
# 「落到哪块设备上」是靠 /api/captures 验的：点单成功时它会列出这次真正被打开的采集设备号。
#
# 用法：& "<绝对路径>\tools\verify-default-source.ps1"

param(
    [string]$ExePath
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $ExePath) { $ExePath = Join-Path $root 'AudioStream\bin\Debug\AudioStream.exe' }

$configDir = Join-Path $env:LOCALAPPDATA 'yiyiooo\AudioStream'
$playersPath = Join-Path $configDir 'players.json'
$playersBackup = Join-Path $configDir 'players.json.verify-default-source-backup'
$grantsPath = Join-Path $configDir 'grants.json'
$grantsBackup = Join-Path $configDir 'grants.json.verify-default-source-backup'
# 跑之前用户原有的自启项值。收尾时只删「本次启动新写进去的那一条」。
$runBefore = $null
$httpBase = 'http://127.0.0.1:12570'

# 假的机器身份，长得像真的，但绝不会等于本机身份
$fakeMachine = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2'
$fakePc = '验证用默认设备拉取方'

$script:failed = 0
$script:checks = 0
$script:tcpPort = 12670
$script:runOk = $true

# 帧协议工具（Write-TextFrame / Read-Frame）
. "$PSScriptRoot\AudioFraming.ps1"

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

# 把授权默认档设为「无需确认」：这个脚本验的是设备解析，
# 不该被「需确认」挂在那里等 30 秒，也不该因为上一次验证留下的规则而被挡。
function Set-DefaultAccess([string]$text) {
    $value = [uri]::EscapeDataString($text)
    return Invoke-RestMethod "$httpBase/api/grants/default?value=$value" -Method Post -TimeoutSec 10
}

function Get-Captures {
    return @((Invoke-RestMethod "$httpBase/api/captures" -TimeoutSec 5).Result)
}

# 被拉取侧记下的连接清单。靠 PcName 认出本脚本扮的那条连接，再看它记的被拉设备名——
# 「default 到底解析到了哪块设备」最直接的证据就是这个，而且不会被别的机器在拉什么干扰。
function Get-MyClients {
    return @((Invoke-RestMethod "$httpBase/api/clients" -TimeoutSec 5).Result | Where-Object { $_.PcName -eq $fakePc })
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
    # 读超时压到 2 秒：一帧都收不到时要能很快失败并继续跑后面的用例
    $client.ReceiveTimeout = 2000
    $client.SendTimeout = 5000
    return $client
}

# 走到「点单有结果」为止。返回：
#   Kind = format（拿到格式帧）/ reject（收到 /Reject/... 文本帧）/ closed（连接被关，什么都没收到）/ other
# 拒绝文本放在 Why 里，格式参数另外几个字段。
function Invoke-Order($client, [string]$deviceId) {
    $stream = $client.GetStream()
    Write-TextFrame -Stream $stream -Text ("/Hello/" + $fakeMachine + "/" + [uri]::EscapeDataString($fakePc))
    $reply = Read-Frame -Stream $stream
    if ($null -eq $reply) { return [pscustomobject]@{ Kind = 'closed'; Why = '握手没有应答'; Stream = $stream } }
    if ($reply.Kind -ne 2) { return [pscustomobject]@{ Kind = 'other'; Why = ('握手回的不是文本帧，类型=' + $reply.Kind); Stream = $stream } }
    if (-not $reply.Text.StartsWith('/Hello/')) { return [pscustomobject]@{ Kind = 'reject'; Why = $reply.Text; Stream = $stream } }

    Write-TextFrame -Stream $stream -Text ("/WaveFormat/" + $deviceId + "/0")
    $fmt = Read-Frame -Stream $stream
    if ($null -eq $fmt) { return [pscustomobject]@{ Kind = 'closed'; Why = '点单后连接被关，什么都没收到'; Stream = $stream } }
    if ($fmt.Kind -eq 2) { return [pscustomobject]@{ Kind = 'reject'; Why = $fmt.Text; Stream = $stream } }
    if ($fmt.Kind -ne 3) { return [pscustomobject]@{ Kind = 'other'; Why = ('点单回的不是格式帧，类型=' + $fmt.Kind); Stream = $stream } }
    if ($fmt.Payload.Length -lt 16) { return [pscustomobject]@{ Kind = 'other'; Why = ('格式负载太短：' + $fmt.Payload.Length); Stream = $stream } }

    return [pscustomobject]@{
        Kind       = 'format'
        Why        = ''
        Stream     = $stream
        SampleRate = [BitConverter]::ToInt32($fmt.Payload, 0)
        Bits       = [BitConverter]::ToInt32($fmt.Payload, 4)
        Channels   = [BitConverter]::ToInt32($fmt.Payload, 8)
    }
}

# /Reject/nodevice/<转义过的原因> 里把原因解出来；不是这类拒绝就返回空串。
function Get-RejectReason([string]$reply) {
    $prefix = '/Reject/nodevice/'
    if (-not $reply.StartsWith($prefix)) { return '' }
    try { return [uri]::UnescapeDataString($reply.Substring($prefix.Length)) } catch { return '' }
}

# ---- 主流程 ----

$proc = $null
try {
    Stop-Agent
    try { $runBefore = (Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'AudioStream.exe' -ErrorAction SilentlyContinue).'AudioStream.exe' } catch { }
    if (Test-Path $playersPath) { Copy-Item $playersPath $playersBackup -Force }
    if (Test-Path $grantsPath) { Copy-Item $grantsPath $grantsBackup -Force }
    $proc = Start-Agent
    [void](Set-DefaultAccess '无需确认')

    # 点单之前先记下此刻已经在采的设备：这台机器上可能同时有别的真机在拉本机的设备，
    # 那种采集与本次测试无关，既不能当成「我打开了它」，也不能当成「我没放掉它」。
    $baseCaptures = Get-Captures

    $devices = @((Invoke-RestMethod "$httpBase/api/devices" -TimeoutSec 5).Result)
    $defaultOut = @($devices | Where-Object { $_.Default -and $_.Flow -eq 'output' })[0]
    $defaultIn = @($devices | Where-Object { $_.Default -and $_.Flow -eq 'input' })[0]

    Write-Host ('被测 exe：' + $ExePath)
    Write-Host ('音频端口：' + $script:tcpPort)
    Write-Host ('本机默认输出设备：' + $(if ($defaultOut) { $defaultOut.Name } else { '<没有>' }))
    Write-Host ('本机默认输入设备：' + $(if ($defaultIn) { $defaultIn.Name } else { '<没有>' }))
    Write-Host ''

    Write-Check '设备接口能标出本机此刻的默认设备（下面的对照基准）' `
        (($null -ne $defaultOut) -or ($null -ne $defaultIn)) `
        '输出与输入两侧都没有默认设备，这台机器上没法验跟随项'

    # 这两个特殊值只由被拉取侧解析，不该出现在设备列表里——
    # 一旦混进去，「拉取授权」的设备下拉就会多出两个永远匹配不上真实设备号的选项。
    $special = @($devices | Where-Object { $_.ID -eq 'default' -or $_.ID -eq 'default-input' })
    Write-Check '设备接口里没有混进这两个特殊设备号' ($special.Count -eq 0) ('混进了：' + (@($special | ForEach-Object { $_.ID }) -join ', '))

    # 1. default = 系统默认输出设备（环回采集）
    $client = Connect-Agent
    $order = Invoke-Order $client 'default'
    if ($order.Kind -eq 'format') {
        Start-Sleep -Milliseconds 300
        $captures = Get-Captures
        Write-Check ('点单 default 拿到可用的音频格式（' + $order.SampleRate + 'Hz/' + $order.Bits + 'bit/' + $order.Channels + '声道）') `
            (($order.SampleRate -ge 8000) -and ($order.SampleRate -le 384000) -and
             ($order.Bits -in @(8, 16, 24, 32)) -and ($order.Channels -ge 1) -and ($order.Channels -le 8)) `
            ('采样率=' + $order.SampleRate + ' 位深=' + $order.Bits + ' 声道=' + $order.Channels)
        if ($defaultOut) {
            $mine = Get-MyClients
            Write-Check '点单 default 落到本机此刻的默认输出设备上（被拉侧记下了这块设备）' `
                (@($mine | Where-Object { $_.DeviceName -eq $defaultOut.Name }).Count -gt 0) `
                ('被拉侧记录=' + (@($mine | ForEach-Object { $_.DeviceName }) -join ', ') + '；期望 ' + $defaultOut.Name)
            if (@($baseCaptures | Where-Object { $_ -eq $defaultOut.ID }).Count -eq 0) {
                Write-Check '点单 default 打开了那块默认输出设备的采集' `
                    (@($captures | Where-Object { $_ -eq $defaultOut.ID }).Count -gt 0) `
                    ('采集列表=' + ($captures -join ', ') + '；期望含 ' + $defaultOut.ID)
            } else {
                Write-Host '  （说明：跑之前就有别的机器在拉这块默认输出设备，采集是共享的，这一步只由被拉侧记录来验）'
            }
        } else {
            Write-Host '  （说明：设备接口没标出默认输出设备，但点单 default 成功了，两侧口径不一致，值得看一眼）'
        }
    } elseif ($order.Kind -eq 'reject' -and $order.Why.StartsWith('/Reject/loop')) {
        Write-Host '  （略过 default：本机此刻正把网络音频播到那块默认输出设备上，采它会绕成回路）'
    } else {
        $reason = Get-RejectReason $order.Why
        Write-Check '本机没有可用默认输出设备时，点单 default 被说得出原因地拒绝' `
            (($order.Kind -eq 'reject') -and ($reason.Length -gt 0) -and ($reason.IndexOf('默认输出设备') -ge 0)) `
            ('结果=' + $order.Kind + ' 说明=' + $order.Why + ' 原因=' + $reason)
    }
    $client.Close()
    Start-Sleep -Milliseconds 700

    # 2. default-input = 系统默认输入设备（麦克风）
    $client = Connect-Agent
    $order = Invoke-Order $client 'default-input'
    if ($order.Kind -eq 'format') {
        Start-Sleep -Milliseconds 300
        $captures = Get-Captures
        if ($defaultIn) {
            $mine = Get-MyClients
            Write-Check '点单 default-input 落到本机此刻的默认输入设备上（被拉侧记下了这块设备）' `
                (@($mine | Where-Object { $_.DeviceName -eq $defaultIn.Name }).Count -gt 0) `
                ('被拉侧记录=' + (@($mine | ForEach-Object { $_.DeviceName }) -join ', ') + '；期望 ' + $defaultIn.Name)
            if (@($baseCaptures | Where-Object { $_ -eq $defaultIn.ID }).Count -eq 0) {
                Write-Check '点单 default-input 打开了那块默认输入设备的采集' `
                    (@($captures | Where-Object { $_ -eq $defaultIn.ID }).Count -gt 0) `
                    ('采集列表=' + ($captures -join ', ') + '；期望含 ' + $defaultIn.ID)
            } else {
                Write-Host '  （说明：跑之前就有别的机器在拉这块默认输入设备，采集是共享的，这一步只由被拉侧记录来验）'
            }
        } else {
            Write-Host '  （说明：设备接口没标出默认输入设备，但点单 default-input 成功了，两侧口径不一致，值得看一眼）'
        }
        Write-Check ('default-input 拿到可用的音频格式（' + $order.SampleRate + 'Hz/' + $order.Bits + 'bit/' + $order.Channels + '声道）') `
            (($order.SampleRate -ge 8000) -and ($order.SampleRate -le 384000) -and
             ($order.Bits -in @(8, 16, 24, 32)) -and ($order.Channels -ge 1) -and ($order.Channels -le 8)) `
            ('采样率=' + $order.SampleRate + ' 位深=' + $order.Bits + ' 声道=' + $order.Channels)
    } else {
        $reason = Get-RejectReason $order.Why
        Write-Check '本机没有可用默认输入设备时，点单 default-input 被说得出原因地拒绝' `
            (($order.Kind -eq 'reject') -and ($reason.Length -gt 0) -and ($reason.IndexOf('默认输入设备') -ge 0)) `
            ('结果=' + $order.Kind + ' 说明=' + $order.Why + ' 原因=' + $reason)
    }
    $client.Close()
    Start-Sleep -Milliseconds 700

    # 3. 大小写不敏感：界面上手打或者老配置里写成大写也要认
    $client = Connect-Agent
    $upper = Invoke-Order $client 'DEFAULT-INPUT'
    Write-Check '大写写的 DEFAULT-INPUT 与小写等价' `
        ($upper.Kind -eq $order.Kind) `
        ('大写得到 ' + $upper.Kind + '，小写得到 ' + $order.Kind)
    $client.Close()
    Start-Sleep -Milliseconds 300

    # 4. 空设备号：要回一条说得出原因的拒绝，不能静默断开
    $client = Connect-Agent
    $empty = Invoke-Order $client ''
    $emptyReason = Get-RejectReason $empty.Why
    Write-Check '点单空的设备号会被说得出原因地拒绝（不把空设备号交给声卡）' `
        (($empty.Kind -eq 'reject') -and ($emptyReason.Length -gt 0)) `
        ('结果=' + $empty.Kind + ' 说明=' + $empty.Why + ' 原因=' + $emptyReason)
    $client.Close()

    # 5. 断开之后本机应该把跟随项打开的采集放掉
    #    只追究「跑之前本来没人采、是这次点单才打开的那些」：
    #    别的机器一直在拉的设备，采集本来就该留着。
    #    还有一种情况也得排除：跑完时别的机器刚好也连上来拉同一块设备——采集是共享的，
    #    它人还在听，本机把采集放掉才是错的。这一步靠 /api/clients 判断，不能只看采集列表。
    Start-Sleep -Milliseconds 800
    $left = Get-Captures
    $allClients = @((Invoke-RestMethod "$httpBase/api/clients" -TimeoutSec 5).Result)
    $leaked = @()
    foreach ($device in @($defaultOut, $defaultIn)) {
        if (-not $device) { continue }
        if (@($baseCaptures | Where-Object { $_ -eq $device.ID }).Count -gt 0) { continue }
        if (@($allClients | Where-Object { $_.DeviceName -eq $device.Name -and $_.Streaming }).Count -gt 0) {
            Write-Host ('  （说明：跑完时还有别的机器在拉「' + $device.Name + '」，采集按共享规则留着，这一步不判它泄漏）')
            continue
        }
        $leaked += @($left | Where-Object { $_ -eq $device.ID })
    }
    Write-Check '断开后跟随项打开的采集已释放' ($leaked.Count -eq 0) ('残留采集：' + (@($left) -join ', '))
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
    if (Test-Path $grantsBackup) {
        Copy-Item $grantsBackup $grantsPath -Force
        Remove-Item $grantsBackup -Force
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
