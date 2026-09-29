# 验证拉取授权：三档（无需确认 / 需确认 / 永不允许）的接口读写，
# 以及被拉取侧真的按档位放行、拒绝、挂起等确认。
#
# 做法：脚本自己扮成一个拉取方，直接用 TCP 连本机的音频端口走握手与点单，
# 所以「档位到底生效没有」是端到端验出来的，不是只看接口返回。
#
# 为了不发出声音：全程只走到 /WaveFormat 就关连接，从不发 /Start——
# 采集不会真正启动，也不会有任何音频播出来。
# 授权判定发生在采播冲突检测之前，所以这一段不依赖本机有没有在播网络音频。
#
# 「需确认」档的 30 秒超时路径不在这里等（等满太费时间），靠批准/拒绝两条路径覆盖等待逻辑。
#
# 用法：pwsh -File tools\verify-auth.ps1

param(
    [string]$ExePath
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $ExePath) { $ExePath = Join-Path $root 'AudioStream\bin\Debug\AudioStream.exe' }

$configDir = Join-Path $env:LOCALAPPDATA 'yiyiooo\AudioStream'
$playersPath = Join-Path $configDir 'players.json'
$grantsPath = Join-Path $configDir 'grants.json'
$playersBackup = Join-Path $configDir 'players.json.verify-auth-backup'
$grantsBackup = Join-Path $configDir 'grants.json.verify-auth-backup'
$httpBase = 'http://127.0.0.1:12570'

# 假的机器身份。32 位十六进制，长得像真的，但绝不会等于本机身份。
$fakeMachineA = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1'
$fakePcA = '验证用拉取方A'
$fakeMachineB = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2'
$fakePcB = '验证用拉取方B'

$script:failed = 0
$script:checks = 0
$script:tcpPort = 12670
$script:devId = $null
$script:devName = $null
$script:sessions = New-Object System.Collections.ArrayList

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

# ---- 接口侧 ----

function Get-GrantsState {
    return (Invoke-RestMethod "$httpBase/api/grants" -TimeoutSec 5).Result
}

function Set-DefaultAccess([string]$text) {
    $value = [uri]::EscapeDataString($text)
    return Invoke-RestMethod "$httpBase/api/grants/default?value=$value" -Method Post -TimeoutSec 10
}

function Save-Grant([string]$machineId, [string]$pcName, [string]$deviceId, [string]$deviceName, [string]$access) {
    $body = [ordered]@{
        MachineId  = $machineId
        PcName     = $pcName
        DeviceId   = $deviceId
        DeviceName = $deviceName
        Access     = $access
    }
    $json = $body | ConvertTo-Json -Depth 6
    # 必须自己编成 UTF-8 字节再发：直接发字符串时，PowerShell 会按非 UTF-8 编码写出去，
    # 中文档位到了服务端就成了「????」，判定必然认不出来。
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod "$httpBase/api/grants" -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 10
}

function Get-PendingFor([string]$machineId) {
    $state = Get-GrantsState
    return @($state.Pending | Where-Object { $_.MachineId -eq $machineId })
}

function Wait-PendingFor([string]$machineId, [int]$timeoutMs = 6000) {
    $deadline = (Get-Date).AddMilliseconds($timeoutMs)
    while ((Get-Date) -lt $deadline) {
        $pending = @(Get-PendingFor $machineId)
        if ($pending.Count -gt 0) { return $pending[0] }
        Start-Sleep -Milliseconds 200
    }
    return $null
}

function Invoke-Decide([string]$id, [bool]$approved, [bool]$remember) {
    $url = "$httpBase/api/grants/decide?id=$id&approved=$($approved.ToString().ToLower())&remember=$($remember.ToString().ToLower())"
    return Invoke-RestMethod $url -Method Post -TimeoutSec 10
}

# ---- TCP 侧：扮成一个拉取方 ----

# 帧协议工具（下面的 Send-Line / Read-Line 都走帧）
. "$PSScriptRoot\AudioFraming.ps1"

function Send-Line($stream, [string]$text) {
    Write-TextFrame -Stream $stream -Text $text
}

function Read-Line($stream) {
    return (Read-TextFrame -Stream $stream)
}

function Open-OrderSession([string]$machineId, [string]$pcName) {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect('127.0.0.1', $script:tcpPort)
    $client.ReceiveTimeout = 10000
    $stream = $client.GetStream()
    Send-Line $stream ("/Hello/" + $machineId + "/" + [uri]::EscapeDataString($pcName))
    $hello = Read-Line $stream
    $session = @{ Client = $client; Stream = $stream; Hello = $hello }
    $null = $script:sessions.Add($session)
    return $session
}

function Send-Order($session) {
    Send-Line $session.Stream ("/WaveFormat/" + $script:devId + "/" + $script:devName)
}

# 挑一块此刻真的打得开的设备去点单。
# 授权判定发生在打开采集之前，所以「被拒/被挂起」那几条不受设备状态影响；
# 但「规则命中无需确认要直接放行」要的是音频格式头，设备自己打不开就验不出授权了。
# 这一步顺带把「打不开的设备回一条说得出原因的拒绝」也看见了。
function Find-UsableSourceDevice {
    $list = @((Invoke-RestMethod "$httpBase/api/devices" -TimeoutSec 10).Result)
    foreach ($device in $list) {
        $session = Open-OrderSession $fakeMachineA $fakePcA
        Send-Line $session.Stream ("/WaveFormat/" + $device.ID + "/" + $device.Name)
        $reply = Read-OrderReply $session
        Close-Session $session
        if ($reply -eq 'FORMAT') { return $device }
        Write-Host ("  （跳过一块此刻开不出采集的设备：" + $device.Name + " -> " + $reply + "）")
        Start-Sleep -Milliseconds 200
    }
    return $null
}

# 读点单的应答。被拒绝时是一条 /Reject/... 文本帧；放行时回的是音频格式帧。
function Read-OrderReply($session) {
    try {
        $frame = Read-Frame -Stream $session.Stream
        if ($null -eq $frame) { return '<连接已断开>' }
        if ($frame.Kind -eq 3) { return 'FORMAT' }
        if ($frame.Kind -eq 2) { return $frame.Text }
        return ('<意外的帧 kind=' + $frame.Kind + '>')
    } catch {
        # 对端没给应答（多半是卡在等确认上）。返回可读文字让这一项记失败就行，
        # 别因为一个 IO 异常把整段检查都打断。
        return '<读取超时>'
    }
}

function Close-Session($session) {
    try { $session.Client.Close() } catch { }
}

function Close-AllSessions {
    foreach ($session in $script:sessions) { Close-Session $session }
    $script:sessions.Clear()
    Start-Sleep -Milliseconds 300
}

if (-not (Test-Path $ExePath)) {
    Write-Host "找不到可执行文件：$ExePath" -ForegroundColor Red
    exit 1
}

Write-Host "被测程序：$ExePath"

$runKey = 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run'
$runBefore = $null
try { $runBefore = (Get-ItemProperty -Path $runKey -Name 'AudioStream.exe' -ErrorAction SilentlyContinue).'AudioStream.exe' } catch { }

$proc = $null
$hadPlayersBackup = $false
$hadGrantsBackup = $false
Stop-Agent
if (-not (Test-Path $configDir)) { New-Item -ItemType Directory -Path $configDir -Force | Out-Null }
if (Test-Path $playersPath) { Copy-Item $playersPath $playersBackup -Force; $hadPlayersBackup = $true }
if (Test-Path $grantsPath) { Copy-Item $grantsPath $grantsBackup -Force; $hadGrantsBackup = $true }

# 清成空基线：拉取列表清空（本机就没人正在播网络音频），授权只留默认档「无需确认」。
Set-Content -Path $playersPath -Value '[]' -Encoding utf8 -NoNewline
Set-Content -Path $grantsPath -Value '{"DefaultAccess":"Allow","Grants":[]}' -Encoding utf8 -NoNewline

try {
    $proc = Start-Agent

    # 拿一块真实存在的设备来点单——设备不存在时对端在授权判定之前就断开，
    # 那样验到的是「设备找不到」，不是授权。
    $devices = @((Invoke-RestMethod "$httpBase/api/devices" -TimeoutSec 10).Result)
    if ($devices.Count -eq 0) { throw '本机没有任何音频设备，无法验证点单被拒' }
    # 设备列表里的第一块不一定是此刻打得开的那块：被别的程序独占的端点、或者驱动不支持
    # 共享模式采集的端点，点单会被回 /Reject/unavailable。那样验到的是「设备打不开」，
    # 不是「授权生效没有」，所以真去点一次单，挑一块能回格式头的。
    $picked = Find-UsableSourceDevice
    if ($null -eq $picked) { throw '本机没有任何一块此刻打得开的音频设备，无法验证点单' }
    $script:devId = $picked.ID
    $script:devName = $picked.Name
    Write-Host ("点单用设备：" + $script:devName + "  音频端口 " + $script:tcpPort)

    # ---- 第一段：接口读写 ----
    $state = Get-GrantsState
    Write-Check '默认档出厂是无需确认' ($state.DefaultAccess -eq '无需确认') ("DefaultAccess=" + $state.DefaultAccess)
    Write-Check '初始没有规则也没有待确认' ((@($state.Grants).Count -eq 0) -and (@($state.Pending).Count -eq 0)) ("规则=" + @($state.Grants).Count + " 待确认=" + @($state.Pending).Count)

    $reply = Set-DefaultAccess '需确认'
    Write-Check '改默认档返回新档位' ($reply.Result -eq '需确认') ("Result=" + $reply.Result + " Message=" + $reply.Message)
    $state = Get-GrantsState
    Write-Check '改完的默认档落到了接口上' ($state.DefaultAccess -eq '需确认') ("DefaultAccess=" + $state.DefaultAccess)

    $reply = Set-DefaultAccess '不认识'
    Write-Check '认不出的档位被拒绝且给出原因' ((-not [bool]$reply.Success) -and -not [string]::IsNullOrWhiteSpace($reply.Message)) ("Success=" + $reply.Success + " Message=" + $reply.Message)

    $reply = Save-Grant $fakeMachineA $fakePcA $script:devId $script:devName '永不允许'
    Write-Check '写规则返回这条规则' (($null -ne $reply.Result) -and ($reply.Result.Access -eq '永不允许')) ("Access=" + $reply.Result.Access + " Message=" + $reply.Message)
    $grantId = $null
    if ($reply.Result) { $grantId = $reply.Result.Id }
    Write-Check '规则里带着人看得懂的范围描述' ((-not [string]::IsNullOrWhiteSpace($reply.Result.Scope)) -and $reply.Result.Scope.Contains($script:devName)) ("Scope=" + $reply.Result.Scope)

    $null = Save-Grant $fakeMachineA $fakePcA $script:devId $script:devName '无需确认'
    $state = Get-GrantsState
    Write-Check '同机器同设备再写一次是就地更新' (@($state.Grants).Count -eq 1) ("规则数=" + @($state.Grants).Count)
    Write-Check '更新后的档位生效' (@($state.Grants)[0].Access -eq '无需确认') ("Access=" + @($state.Grants)[0].Access)

    # ---- 第二段：永不允许：点单当场被拒 ----
    $null = Save-Grant $fakeMachineA $fakePcA $script:devId $script:devName '永不允许'
    $session = Open-OrderSession $fakeMachineA $fakePcA
    Write-Check '握手能拿到对端身份回应' ($session.Hello.StartsWith('/Hello/')) ("应答=" + $session.Hello)
    Send-Order $session
    $reply = Read-OrderReply $session
    Write-Check '永不允许的设备点单被拒' ($reply -eq '/Reject/denied') ("应答=" + $reply)
    Close-Session $session

    # ---- 第三段：精确规则的无需确认要压过默认档的需确认 ----
    $null = Save-Grant $fakeMachineA $fakePcA $script:devId $script:devName '无需确认'
    $session = Open-OrderSession $fakeMachineA $fakePcA
    Send-Order $session
    $reply = Read-OrderReply $session
    Write-Check '规则命中无需确认时直接放行（格式头）' ($reply -eq 'FORMAT') ("应答=" + $reply)
    Close-Session $session
    Start-Sleep -Milliseconds 400
    Close-AllSessions

    # ---- 第四段：默认档需确认：先挂起，批准后放行 ----
    $session = Open-OrderSession $fakeMachineB $fakePcB
    Send-Order $session
    $pending = Wait-PendingFor $fakeMachineB
    Write-Check '需确认档下点单会挂在本机等批准' ($null -ne $pending) "6 秒内没看到待确认申请"
    if ($pending) {
        Write-Check '申请里带着是谁想拉哪个设备' (($pending.PcName -eq $fakePcB) -and ($pending.DeviceName -eq $script:devName) -and (-not [string]::IsNullOrWhiteSpace($pending.ClientIp))) ("PcName=" + $pending.PcName + " Device=" + $pending.DeviceName + " IP=" + $pending.ClientIp)
        Write-Check '申请带着剩余等待时间' ($pending.RemainingSeconds -gt 0) ("剩余=" + $pending.RemainingSeconds)
        $decide = Invoke-Decide $pending.Id $true $false
        Write-Check '批准申请返回成功' ([bool]$decide.Success) ("Success=" + $decide.Success + " Message=" + $decide.Message)
        $reply = Read-OrderReply $session
        Write-Check '批准后这一路被放行' ($reply -eq 'FORMAT') ("应答=" + $reply)
    }
    Close-Session $session
    Close-AllSessions
    Start-Sleep -Milliseconds 400
    Write-Check '处理过的申请不会留在待确认列表里' (@(Get-PendingFor $fakeMachineB).Count -eq 0) ("还剩 " + @(Get-PendingFor $fakeMachineB).Count + " 条")

    # ---- 第五段：默认档需确认：拒绝后被拒 ----
    $session = Open-OrderSession $fakeMachineB $fakePcB
    Send-Order $session
    $pending = Wait-PendingFor $fakeMachineB
    Write-Check '第二路申请同样会被挂起' ($null -ne $pending) "6 秒内没看到待确认申请"
    if ($pending) {
        $null = Invoke-Decide $pending.Id $false $false
        $reply = Read-OrderReply $session
        Write-Check '拒绝后对端收到被拒' ($reply -eq '/Reject/denied') ("应答=" + $reply)
    }
    Close-Session $session
    Close-AllSessions

    # ---- 第六段：批准时勾「记住」会落成永久规则，下次不再问 ----
    $before = @((Get-GrantsState).Grants).Count
    $session = Open-OrderSession $fakeMachineB $fakePcB
    Send-Order $session
    $pending = Wait-PendingFor $fakeMachineB
    if ($pending) {
        $null = Invoke-Decide $pending.Id $true $true
        $reply = Read-OrderReply $session
        Write-Check '批准并记住后这一路放行' ($reply -eq 'FORMAT') ("应答=" + $reply)
    } else {
        Write-Check '批准并记住后这一路放行' $false "没看到待确认申请"
    }
    Close-Session $session
    Close-AllSessions
    $state = Get-GrantsState
    Write-Check '记住的选择落成了一条新规则' (@($state.Grants).Count -eq ($before + 1)) ("前=" + $before + " 后=" + @($state.Grants).Count)
    $remembered = @($state.Grants | Where-Object { $_.MachineId -eq $fakeMachineB })
    Write-Check '这条规则是无需确认' (($remembered.Count -eq 1) -and ($remembered[0].Access -eq '无需确认')) ("条数=" + $remembered.Count + " Access=" + @($remembered)[0].Access)

    # ---- 第七段：记住之后再来不再问，直接放行 ----
    $session = Open-OrderSession $fakeMachineB $fakePcB
    Send-Order $session
    $reply = Read-OrderReply $session
    Write-Check '记住之后第二次直接放行' ($reply -eq 'FORMAT') ("应答=" + $reply)
    Start-Sleep -Milliseconds 800
    Write-Check '这次没有产生待确认申请' (@(Get-PendingFor $fakeMachineB).Count -eq 0) ("待确认=" + @(Get-PendingFor $fakeMachineB).Count)
    Close-Session $session
    Close-AllSessions

    # ---- 第八段：删规则 ----
    $grants = @((Get-GrantsState).Grants)
    $removeId = $grants[0].Id
    $reply = Invoke-RestMethod "$httpBase/api/grants/delete?id=$removeId" -Method Post -TimeoutSec 10
    Write-Check '删规则返回成功' ([bool]$reply.Success) ("Success=" + $reply.Success + " Message=" + $reply.Message)
    Write-Check '删掉一条后规则少了一条' (@((Get-GrantsState).Grants).Count -eq ($grants.Count - 1)) ("前=" + $grants.Count + " 后=" + @((Get-GrantsState).Grants).Count)
    $reply = Invoke-RestMethod "$httpBase/api/grants/delete?id=$removeId" -Method Post -TimeoutSec 10
    Write-Check '删不存在的规则会被拒绝' (-not [bool]$reply.Success) ("Success=" + $reply.Success)

    # ---- 第九段：默认档设回无需确认，规则清空后照旧放行（老行为不变） ----
    $state = Get-GrantsState
    foreach ($grant in @($state.Grants)) {
        $null = Invoke-RestMethod "$httpBase/api/grants/delete?id=$($grant.Id)" -Method Post -TimeoutSec 10
    }
    $reply = Set-DefaultAccess '无需确认'
    Write-Check '默认档能设回无需确认' ($reply.Result -eq '无需确认') ("Result=" + $reply.Result)
    $session = Open-OrderSession $fakeMachineA $fakePcA
    Send-Order $session
    $reply = Read-OrderReply $session
    Write-Check '默认无需确认时没有任何规则也照旧放行' ($reply -eq 'FORMAT') ("应答=" + $reply)
    Close-Session $session
    Close-AllSessions

    # ---- 第十段：授权状态随连接显示在本机客户端列表上 ----
    $session = Open-OrderSession $fakeMachineA $fakePcA
    Send-Order $session
    $null = Read-OrderReply $session
    Start-Sleep -Milliseconds 400
    $clients = @((Invoke-RestMethod "$httpBase/api/clients" -TimeoutSec 5).Result | Where-Object { $_.PcName -eq $fakePcA })
    Write-Check '正在被拉取的连接会出现在客户端列表里' ($clients.Count -ge 1) ("找到 " + $clients.Count + " 条")
    Close-Session $session
    Close-AllSessions
}
finally {
    Close-AllSessions
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Stop-Agent
    if ($hadPlayersBackup) {
        Copy-Item $playersBackup $playersPath -Force
        Remove-Item $playersBackup -Force -ErrorAction SilentlyContinue
        Write-Host '已恢复原有的播放列表配置'
    } else {
        Remove-Item $playersPath -Force -ErrorAction SilentlyContinue
        Write-Host '原先没有播放列表配置，已清掉测试写入的那份'
    }
    if ($hadGrantsBackup) {
        Copy-Item $grantsBackup $grantsPath -Force
        Remove-Item $grantsBackup -Force -ErrorAction SilentlyContinue
        Write-Host '已恢复原有的授权配置'
    } else {
        Remove-Item $grantsPath -Force -ErrorAction SilentlyContinue
        Write-Host '原先没有授权配置，已清掉测试写入的那份'
    }
    try {
        $runNow = (Get-ItemProperty -Path $runKey -Name 'AudioStream.exe' -ErrorAction SilentlyContinue).'AudioStream.exe'
        if ($runNow -and $runNow -ne $runBefore -and $runNow -eq $ExePath) {
            Remove-ItemProperty -Path $runKey -Name 'AudioStream.exe' -ErrorAction SilentlyContinue
            Write-Host '已清理测试期间写入的开机自启项'
        }
    } catch { }
}

Write-Host ""
if ($script:failed -eq 0) {
    Write-Host ("全部通过（{0} 项）" -f $script:checks) -ForegroundColor Green
} else {
    Write-Host ("{0}/{1} 项失败" -f $script:failed, $script:checks) -ForegroundColor Red
    exit 1
}
