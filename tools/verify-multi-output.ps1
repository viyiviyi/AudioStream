# 验证「一路拉取把自己喂给本机多块声卡」。
#
# 用真实的声卡做：来源取本机默认输入设备（麦克风持续给帧），目标是本机另外两块输出设备，
# 断言是程序自报的「此刻真的在出声的设备名单」——那一刻两块声卡必须都在放。
# 顺带覆盖三种退化：目标就是来源、目标里有打不开的设备、目标全都不可用。
#
# 用法：powershell -File tools\verify-multi-output.ps1
# 跑完会恢复原有的播放列表配置与开机自启项。

param(
    [string]$ExePath
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $ExePath) { $ExePath = Join-Path $root 'AudioStream\bin\Debug\AudioStream.exe' }

$configDir = Join-Path $env:LOCALAPPDATA 'yiyiooo\AudioStream'
$configPath = Join-Path $configDir 'players.json'
$backupPath = Join-Path $configDir 'players.json.verify-backup'
$httpBase = 'http://127.0.0.1:12570'

$recordId = '55555555-5555-5555-5555-555555555555'
$ghostDeviceId = '{0.0.0.00000000}.{99999999-9999-9999-9999-999999999999}'

$script:failed = 0
$script:checks = 0

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
            $null = Invoke-RestMethod "$httpBase/api/info" -TimeoutSec 2
            return $proc
        } catch { }
    }
    throw '配置界面没有起来，拿不到 /api/info'
}

# 接口返回的 Result 可能是 null、单个对象或数组，统一收成数组。
# 注意两点：@($null) 会得到「含一个 null 的数组」，所以要先判 null；
# 而 PowerShell 返回数组时会把单元素数组解包成裸对象（此后 .Count 与 [0] 都不对），
# 所以用逗号运算符把数组原样送出去。
function As-Array($value) {
    if ($null -eq $value) { return ,@() }
    return ,@($value)
}

function Get-Devices {
    return As-Array (Invoke-RestMethod "$httpBase/api/devices" -TimeoutSec 5).Result
}

function Get-Status {
    return As-Array (Invoke-RestMethod "$httpBase/api/play-status" -TimeoutSec 5).Result
}

function Get-Players {
    return As-Array (Invoke-RestMethod "$httpBase/api/players" -TimeoutSec 5).Result
}

# 等到这一路自报「真的在出声」为止。声卡起播有一小段延迟，直接断言会误判。
function Wait-Playing([int]$seconds = 12) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $list = As-Array (Get-Status)
        if ($list.Count -ge 1 -and $list[0].Playing) { return $list[0] }
    }
    $list = As-Array (Get-Status)
    if ($list.Count -ge 1) { return $list[0] }
    return $null
}

function Save-Json([string]$json) {
    if (-not (Test-Path $configDir)) { New-Item -ItemType Directory -Path $configDir -Force | Out-Null }
    Set-Content -Path $configPath -Value $json -Encoding utf8 -NoNewline
}

# 造一条记录。返回 JSON 文本而不是对象：PowerShell 会把单元素数组解包成对象，
# 那样写出来的配置不是 JSON 数组，程序会读不进来。
function New-RecordJson([string]$sourceId, [string]$sourceName, [array]$targetList, [string]$policy = 'All') {
    $targets = @()
    for ($i = 0; $i -lt $targetList.Count; $i++) {
        $targets += @{
            DeviceID = $targetList[$i].ID
            DeviceName = $targetList[$i].Name
            Priority = $i
        }
    }
    $record = @{
        ID = $recordId
        SourceDeviceID = $sourceId
        SourceDeviceName = $sourceName
        IP = ''
        PcName = $null
        RemakeName = $null
        Volume = 1.0
        Index = 1
        Play = $true
        Hidden = $false
        Policy = $policy
        Targets = $targets
    }
    # -InputObject 收数组本体，不走管道，避免单元素被解包
    return (ConvertTo-Json -InputObject @($record) -Depth 8)
}

function Active-Names($status) {
    if ($null -eq $status -or $null -eq $status.ActiveDeviceNames) { return ,@() }
    return ,@($status.ActiveDeviceNames)
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
$hadBackup = $false
Stop-Agent
if (Test-Path $configPath) {
    Copy-Item $configPath $backupPath -Force
    $hadBackup = $true
}

try {
    # 先摸清本机有哪些设备可用来做这个验证
    Save-Json '[]'
    $proc = Start-Agent
    $devices = As-Array (Get-Devices)
    Stop-Agent
    $proc = $null

    $inputs = @($devices | Where-Object { $_.Flow -eq 'input' })
    $outputs = @($devices | Where-Object { $_.Flow -eq 'output' })

    $source = $inputs | Where-Object { $_.Default } | Select-Object -First 1
    if (-not $source) { $source = $inputs | Select-Object -First 1 }

    $targets = @($outputs | Where-Object { $_.Default -eq $false } | Select-Object -First 2)
    if ($targets.Count -lt 2) { $targets = @($outputs | Select-Object -First 2) }

    Write-Check '本机至少有一路可当来源的输入设备' ($null -ne $source) '没有找到任何输入设备，后面的断言无从谈起'
    Write-Check '本机至少有两块输出设备可用' ($targets.Count -ge 2) ("输出设备数=" + $targets.Count)

    if ($null -eq $source -or $targets.Count -lt 2) {
        throw '本机设备不足以做多输出验证'
    }

    Write-Host ("来源设备：" + $source.Name)
    foreach ($t in $targets) { Write-Host ("目标设备：" + $t.Name) }

    # ---- 第一段：一份来源同时喂两块声卡 ----
    Save-Json (New-RecordJson $source.ID $source.Name $targets 'All')
    $proc = Start-Agent

    $status = Wait-Playing
    $targetCount = if ($status) { @($status.Targets).Count } else { 0 }
    Write-Check '这一路起来了' ($null -ne $status -and $status.Playing) ("state=" + $(if ($status) { $status.State } else { '<无>' }) + " error=" + $(if ($status) { $status.Error } else { '' }))
    Write-Check '配置里的两个目标都在' ($targetCount -eq 2) ("目标数=" + $targetCount)
    Write-Check '策略是全播' ($null -ne $status -and $status.Policy -eq 'All') ("Policy=" + $(if ($status) { $status.Policy } else { '' }))

    $active = Active-Names $status
    Write-Check '两块声卡此刻都在出声' ($active.Count -eq 2) ("在出声的设备=" + ($active -join '、'))
    Write-Check '出声的正是配置里的那两个目标' (
        $active.Count -eq 2 -and
        (@($active | Where-Object { $_ -eq $targets[0].Name }).Count -eq 1) -and
        (@($active | Where-Object { $_ -eq $targets[1].Name }).Count -eq 1)
    ) ("在出声的设备=" + ($active -join '、'))

    # 同一份数据只拉一次：本机流转不经过网络，被拉取列表里不该出现本次涉及的这些设备。
    # 只断言本脚本自己的设备：别处（比如另一台机器正在拉本机的某个设备）与本脚本无关，
    # 断言「列表为空」会把别人的正常拉取误判成本次失败。
    $captures = As-Array (Invoke-RestMethod "$httpBase/api/captures" -TimeoutSec 5).Result
    $mine = @($source.ID) + @($targets | ForEach-Object { $_.ID })
    $leaked = @($captures | Where-Object { $mine -contains $_ })
    Write-Check '本机流转没有把自己登记成被拉取设备' ($leaked.Count -eq 0) ("本次涉及的设备出现在被拉取列表里=" + ($leaked -join '、') + "；当时全部被拉取设备=" + ($captures -join '、'))

    Stop-Agent
    $proc = $null

    # ---- 第二段：目标里混进来源设备本身，那一块要跳过，剩下的照常出声 ----
    Save-Json (New-RecordJson $source.ID $source.Name @($source, $targets[0]) 'All')
    $proc = Start-Agent
    $status = Wait-Playing 8
    $active = Active-Names $status
    Write-Check '目标是来源设备时只出剩下的那一路' ($active.Count -eq 1) ("在出声的设备=" + ($active -join '、'))
    Write-Check '出声的就是那个有效目标' ($active.Count -eq 1 -and $active[0] -eq $targets[0].Name) ("在出声的设备=" + ($active -join '、'))
    Stop-Agent
    $proc = $null

    # ---- 第三段：目标里有一个设备号是不存在的，剩下那块照常出声 ----
    $ghost = @{ ID = $ghostDeviceId; Name = '不存在的播放设备' }
    Save-Json (New-RecordJson $source.ID $source.Name @($ghost, $targets[0]) 'All')
    $proc = Start-Agent
    $status = Wait-Playing 8
    $active = Active-Names $status
    Write-Check '一块设备打不开不影响另一块出声' ($active.Count -eq 1) ("在出声的设备=" + ($active -join '、'))
    Write-Check '出声的是那块存在的设备' ($active.Count -eq 1 -and $active[0] -eq $targets[0].Name) ("在出声的设备=" + ($active -join '、'))
    Stop-Agent
    $proc = $null

    # ---- 第四段：目标全都不可用，这一路要明确报失败而不是假装在播 ----
    Save-Json (New-RecordJson $source.ID $source.Name @($ghost) 'All')
    $proc = Start-Agent
    Start-Sleep -Seconds 4
    $list = As-Array (Get-Status)
    $first = if ($list.Count -ge 1) { $list[0] } else { $null }
    Write-Check '这一路还在配置里' ($list.Count -eq 1) ("记录数=" + $list.Count)
    Write-Check '目标全都不可用时这一路不算在播' ($null -ne $first -and -not $first.Playing) ("Playing=" + $(if ($first) { $first.Playing } else { '<无记录>' }))
    Write-Check '并且给出了说得清的原因' ($null -ne $first -and -not [string]::IsNullOrEmpty($first.Error)) ("Error=" + $(if ($first) { $first.Error } else { '' }))
    Write-Check '原因里点名了是哪块设备' ($null -ne $first -and $first.Error -like '*不存在的播放设备*') ("Error=" + $(if ($first) { $first.Error } else { '' }))
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Stop-Agent
    if ($hadBackup) {
        Copy-Item $backupPath $configPath -Force
        Remove-Item $backupPath -Force -ErrorAction SilentlyContinue
        Write-Host '已恢复原有的播放列表配置'
    } else {
        Remove-Item $configPath -Force -ErrorAction SilentlyContinue
        Write-Host '原先没有播放列表配置，已清掉测试写入的那份'
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
