# 验证接口层：一条来源配多个播放目标、播放策略，以及各接口的返回体。
#
# 全程用虚构的设备号，本机根本找不到它们，所以这一路会被立刻记为失败——
# 但也正因为如此，全程不会去开任何声卡、不会有声音出来。
# 「一块声卡到底出不出声」由 verify-multi-output.ps1 / verify-failover.ps1 覆盖，这边只管接口。
#
# 用法：pwsh -File tools\verify-multi-api.ps1

param(
    [string]$ExePath
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $ExePath) { $ExePath = Join-Path $root 'AudioStream\bin\Debug\AudioStream.exe' }

$configDir = Join-Path $env:LOCALAPPDATA 'yiyiooo\AudioStream'
$configPath = Join-Path $configDir 'players.json'
$backupPath = Join-Path $configDir 'players.json.verify-api-backup'
$httpBase = 'http://127.0.0.1:12570'

$sourceId = '{0.0.0.00000000}.{aaaaaaaa-0000-0000-0000-000000000001}'
$sourceName = '虚构来源设备'
$targetA = '{0.0.0.00000000}.{aaaaaaaa-0000-0000-0000-000000000002}'
$targetB = '{0.0.0.00000000}.{aaaaaaaa-0000-0000-0000-000000000003}'
$targetC = '{0.0.0.00000000}.{aaaaaaaa-0000-0000-0000-000000000004}'

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

function Get-Players {
    $reply = Invoke-RestMethod "$httpBase/api/players" -TimeoutSec 5
    return @($reply.Result)
}

function Get-Statuses {
    $reply = Invoke-RestMethod "$httpBase/api/play-status" -TimeoutSec 5
    return @($reply.Result)
}

function New-PlayerBody([string]$policy, $targets, [string]$source, [string]$name) {
    return [ordered]@{
        IP               = ''
        SourceDeviceID   = $source
        SourceDeviceName = $name
        Policy           = $policy
        Targets          = $targets
    }
}

function New-Player($body) {
    $json = $body | ConvertTo-Json -Depth 8
    return Invoke-RestMethod "$httpBase/api/players" -Method Post -ContentType 'application/json' -Body $json -TimeoutSec 10
}

function TargetSpec([string]$id, [string]$name) {
    return [ordered]@{ DeviceID = $id; DeviceName = $name }
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

# 断言里的「条数」都是绝对数，所以要先把配置清成空基线再启动。
# 本机原先那条拉取记录留着的话，条数对不上，add_player 还会撞上「请勿重复添加」。
if (-not (Test-Path $configDir)) { New-Item -ItemType Directory -Path $configDir -Force | Out-Null }
Set-Content -Path $configPath -Value '[]' -Encoding utf8 -NoNewline

try {
    $proc = Start-Agent

    # ---- 第一段：新建一条「全播 + 两个目标」 ----
    $body = New-PlayerBody 'All' @((TargetSpec $targetA '虚构设备A'), (TargetSpec $targetB '虚构设备B')) $sourceId $sourceName
    $reply = New-Player $body
    Write-Check '新建多目标返回成功' ([bool]$reply.Success) ("Success=" + $reply.Success + " Message=" + $reply.Message)
    Write-Check '新建的返回体里带着这条记录' ($null -ne $reply.Result) "Result 为空"
    if ($reply.Result) {
        $createdId = $reply.Result.ID
        Write-Check '返回的目标是两个' (@($reply.Result.Targets).Count -eq 2) ("目标数=" + @($reply.Result.Targets).Count)
        Write-Check '返回的策略是 All' ($reply.Result.Policy -eq 'All') ("Policy=" + $reply.Result.Policy)
        Write-Check '目标顺序与提交的一致' (@($reply.Result.Targets)[0].DeviceID -eq $targetA) ("第一个=" + @($reply.Result.Targets)[0].DeviceID)
    }

    Start-Sleep -Milliseconds 400
    $players = @(Get-Players)
    Write-Check '列表里能看到这条' ($players.Count -eq 1) ("条数=" + $players.Count)
    if ($players.Count -eq 1) {
        Write-Check '列表里目标是两个' (@($players[0].Targets).Count -eq 2) ("目标数=" + @($players[0].Targets).Count)
        Write-Check '列表里优先级按顺序排' ((@($players[0].Targets)[0].Priority -eq 0) -and (@($players[0].Targets)[1].Priority -eq 1)) ("优先级=" + @($players[0].Targets)[0].Priority + "," + @($players[0].Targets)[1].Priority)
    }

    # ---- 第二段：同样的设备写成主备策略 ----
    $body = New-PlayerBody 'Failover' @((TargetSpec $targetA '虚构设备A'), (TargetSpec $targetB '虚构设备B')) $sourceId $sourceName
    $null = New-Player $body
    Start-Sleep -Milliseconds 300
    $players = @(Get-Players)
    $failover = @($players | Where-Object { $_.Policy -eq 'Failover' })
    Write-Check '策略写成了 Failover' ($failover.Count -eq 1) ("找到 " + $failover.Count + " 条")
    if ($failover.Count -eq 1) {
        Write-Check '主备这条也带着两个目标' (@($failover[0].Targets).Count -eq 2) ("目标数=" + @($failover[0].Targets).Count)
    }

    # ---- 第三段：同一块设备写两遍只留一份，否则声音会翻倍 ----
    $body = New-PlayerBody 'All' @((TargetSpec $targetC '虚构设备C'), (TargetSpec $targetC '虚构设备C'), (TargetSpec $targetA '虚构设备A')) $sourceId $sourceName
    $reply = New-Player $body
    Write-Check '重复设备被去掉' (@($reply.Result.Targets).Count -eq 2) ("目标数=" + @($reply.Result.Targets).Count)

    # ---- 第四段：参数不合法要给出话说，而不是默默建一条空记录 ----
    $before = @(Get-Players).Count
    $body = New-PlayerBody 'All' @() $sourceId $sourceName
    $reply = New-Player $body
    Write-Check '没选播放设备会被拒绝' ((-not [bool]$reply.Success) -and -not [string]::IsNullOrWhiteSpace($reply.Message)) ("Success=" + $reply.Success + " Message=" + $reply.Message)
    $body = New-PlayerBody 'All' @((TargetSpec $targetA '虚构设备A')) '' ''
    $reply = New-Player $body
    Write-Check '没选来源设备会被拒绝' ((-not [bool]$reply.Success) -and -not [string]::IsNullOrWhiteSpace($reply.Message)) ("Success=" + $reply.Success + " Message=" + $reply.Message)
    Write-Check '被拒绝的两次没有留下记录' (@(Get-Players).Count -eq $before) ("前=" + $before + " 后=" + @(Get-Players).Count)

    # ---- 第五段：改一条已有记录的策略与目标 ----
    $players = @(Get-Players)
    $first = $players[0]
    $body = New-PlayerBody 'Failover' @((TargetSpec $targetB '虚构设备B'), (TargetSpec $targetC '虚构设备C')) $sourceId $sourceName
    $json = $body | ConvertTo-Json -Depth 8
    $reply = Invoke-RestMethod "$httpBase/api/players/update?id=$($first.ID)" -Method Post -ContentType 'application/json' -Body $json -TimeoutSec 10
    Write-Check '改策略与目标返回成功' ([bool]$reply.Success) ("Success=" + $reply.Success + " Message=" + $reply.Message)
    Start-Sleep -Milliseconds 300
    $updated = @(Get-Players | Where-Object { $_.ID -eq $first.ID })
    Write-Check '改完策略落到了列表里' ((@($updated).Count -eq 1) -and ($updated[0].Policy -eq 'Failover')) ("Policy=" + @($updated)[0].Policy)
    Write-Check '改完目标是新的那两块' ((@($updated[0].Targets).Count -eq 2) -and (@($updated[0].Targets)[0].DeviceID -eq $targetB) -and (@($updated[0].Targets)[1].DeviceID -eq $targetC)) ("目标=" + (@($updated[0].Targets) | ForEach-Object { $_.DeviceID }) -join ',')

    # ---- 第六段：状态接口要能看出「配了但没响」以及为什么 ----
    Start-Sleep -Milliseconds 600
    $statuses = @(Get-Statuses)
    Write-Check '状态条数与配置条数一致' ($statuses.Count -eq @(Get-Players).Count) ("状态=" + $statuses.Count + " 配置=" + @(Get-Players).Count)
    $allHaveState = $true
    $anyError = $false
    foreach ($s in $statuses) {
        if ([string]::IsNullOrWhiteSpace($s.State)) { $allHaveState = $false }
        if (-not [string]::IsNullOrWhiteSpace($s.Error)) { $anyError = $true }
    }
    Write-Check '每条状态都有 State' $allHaveState "有空 State"
    Write-Check '虚构设备被如实记成失败原因' $anyError "没看到任何 Error"

    # ---- 第七段：老接口的返回体不再丢 Result ----
    # 这一族接口过去把 Result 声明成了字段，JSON 里压根不出现，界面永远看不到结果。
    # 这里必须挑一对上面没用过的设备：老接口自己会挡「重复添加」，撞上了返回的是失败体，看不到 Result。
    $legacyTarget = '{0.0.0.00000000}.{aaaaaaaa-0000-0000-0000-000000000005}'
    $legacyUrl = "$httpBase/api/add_player?s_device=$sourceId&t_device=$legacyTarget&ip=&s_device_name=$sourceName&t_device_name=虚构设备E"
    $reply = Invoke-RestMethod $legacyUrl -Method Post -TimeoutSec 10
    Write-Check '老接口 add_player 返回体里有 Result' ($null -ne $reply.Result) ("Result 为空，Success=" + $reply.Success + " Message=" + $reply.Message)
    $legacyId = $null
    if ($reply.Result) { $legacyId = $reply.Result.ID }

    $duplicate = Invoke-RestMethod $legacyUrl -Method Post -TimeoutSec 10
    Write-Check '老接口重复添加被拒绝且给出原因' ((-not [bool]$duplicate.Success) -and -not [string]::IsNullOrWhiteSpace($duplicate.Message)) ("Success=" + $duplicate.Success + " Message=" + $duplicate.Message)

    $countBefore = @(Get-Players).Count
    $reply = Invoke-RestMethod "$httpBase/api/pause?id=$legacyId" -Method Post -TimeoutSec 10
    Write-Check '老接口 pause 返回体里有 Result' ($null -ne $reply.Result) "Result 为空"

    $reply = Invoke-RestMethod "$httpBase/api/volume?id=$legacyId&val=0.5" -Method Post -TimeoutSec 10
    Write-Check '老接口 volume 返回体里有 Result' ($null -ne $reply.Result) "Result 为空"

    $reply = Invoke-RestMethod "$httpBase/api/del?id=$legacyId" -Method Post -TimeoutSec 10
    Write-Check '老接口 del 返回体里有 Result' ($null -ne $reply.Result) "Result 为空"
    Write-Check '删除后记录少了一条' (@(Get-Players).Count -eq ($countBefore - 1)) ("前=" + $countBefore + " 后=" + @(Get-Players).Count)
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
