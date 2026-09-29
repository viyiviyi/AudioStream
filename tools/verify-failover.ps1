# 验证「主备（Failover）策略：按优先级挑第一块能打开的声卡，不行就换下一块」。
#
# 用真实声卡做：来源取本机默认输入设备，目标是一块真声卡加若干「不存在的设备号」。
# 覆盖四段：
#   一、两块都能开时，出声的是优先级最高的那一块（证明优先级真的被遵守）
#   二、优先级最高的那块开不出来时，自动用下一块顶上
#   三、候选全都开不出来时，这一路明确报失败并点名每一块设备
#   四、顺序反过来（可用设备优先级最高）时仍用可用那块（证明不是「总挑第二个」）
#
# 边界：本机没有可控手段让一块正在用的声卡中途消失或变得打不开（WasapiOut 走共享模式，
# 不会因为独占而失败），所以「运行中当前设备失效后自动换下一个」这条路径无法在真机上跑，
# 只能靠第二、四段证明候选轮转本身的正确性。
#
# 用法：powershell -File tools\verify-failover.ps1
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

$recordId = '66666666-6666-6666-6666-666666666666'
$ghostA = @{ ID = '{0.0.0.00000000}.{88888888-8888-8888-8888-888888888888}'; Name = '验证用不存在的设备甲' }
$ghostB = @{ ID = '{0.0.0.00000000}.{77777777-7777-7777-7777-777777777777}'; Name = '验证用不存在的设备乙' }

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
# 而 PowerShell 返回数组时会把单元素数组解包成裸对象，所以用逗号运算符把数组原样送出去。
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

# 等到这一路稳定下来（成功或彻底失败都算稳定），用于只关心结果的场景。
function Wait-Settled([int]$seconds = 8) {
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

# 造一条记录。每个目标自带 Priority（小的先用）。
# 返回 JSON 文本而不是对象：PowerShell 会把单元素数组解包成对象，那样写出来的配置不是 JSON 数组。
function New-RecordJson([string]$sourceId, [string]$sourceName, [array]$targetList, [string]$policy = 'Failover') {
    $targets = @()
    for ($i = 0; $i -lt $targetList.Count; $i++) {
        $targets += @{
            DeviceID = $targetList[$i].ID
            DeviceName = $targetList[$i].Name
            Priority = $targetList[$i].Priority
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

    # 拿一块真声卡当「能打开的候选」。优先挑非默认那块，免得和系统声音抢设备。
    $real = $outputs | Where-Object { $_.Default -eq $false } | Select-Object -First 1
    if (-not $real) { $real = $outputs | Select-Object -First 1 }

    Write-Check '本机至少有一路可当来源的输入设备' ($null -ne $source) '没有找到任何输入设备，后面的断言无从谈起'
    Write-Check '本机至少有一块可用的输出设备' ($null -ne $real) ("输出设备数=" + $outputs.Count)

    if ($null -eq $source -or $null -eq $real) {
        throw '本机设备不足以做主备策略验证'
    }

    Write-Host ("来源设备：" + $source.Name)
    Write-Host ("可用的输出设备：" + $real.Name)

    # ---- 第一段：两块都能开时，用的是优先级最高的那一块 ----
    # 优先级 0 给真设备，优先级 1 给另一个真设备（本机通常有多块输出，够就再拿一块）
    $second = $outputs | Where-Object { $_.ID -ne $real.ID } | Select-Object -First 1
    $both = @(
        @{ ID = $real.ID; Name = $real.Name; Priority = 0 }
    )
    if ($second) { $both += @{ ID = $second.ID; Name = $second.Name; Priority = 1 } }

    Save-Json (New-RecordJson $source.ID $source.Name $both 'Failover')
    $proc = Start-Agent
    $status = Wait-Playing
    $active = Active-Names $status
    Write-Check '主备策略下这一路起来了' ($null -ne $status -and $status.Playing) ("state=" + $(if ($status) { $status.State } else { '<无>' }) + " error=" + $(if ($status) { $status.Error } else { '' }))
    Write-Check '策略是主备' ($null -ne $status -and $status.Policy -eq 'Failover') ("Policy=" + $(if ($status) { $status.Policy } else { '' }))
    Write-Check '只开了一块声卡（不是全播）' ($active.Count -eq 1) ("在出声的设备=" + ($active -join '、'))
    Write-Check '出声的是优先级最高的那一块' ($active.Count -eq 1 -and $active[0] -eq $real.Name) ("在出声的设备=" + ($active -join '、'))
    Stop-Agent
    $proc = $null

    # ---- 第二段：优先级最高的那块开不出来，自动换下一块顶上 ----
    Save-Json (New-RecordJson $source.ID $source.Name @(
        @{ ID = $ghostA.ID; Name = $ghostA.Name; Priority = 0 },
        @{ ID = $real.ID; Name = $real.Name; Priority = 1 }
    ) 'Failover')
    $proc = Start-Agent
    $status = Wait-Playing
    $active = Active-Names $status
    Write-Check '首选出不来时这一路仍然起来了' ($null -ne $status -and $status.Playing) ("state=" + $(if ($status) { $status.State } else { '<无>' }) + " error=" + $(if ($status) { $status.Error } else { '' }))
    Write-Check '换到了下一块能用的设备上' ($active.Count -eq 1 -and $active[0] -eq $real.Name) ("在出声的设备=" + ($active -join '、'))
    Write-Check '状态里报的是真正在用的那块设备' ($null -ne $status -and $status.TargetDeiceID -eq $real.ID) ("TargetDeiceID=" + $(if ($status) { $status.TargetDeiceID } else { '' }))
    Stop-Agent
    $proc = $null

    # ---- 第三段：候选全都开不出来，明确报失败并点名每一块 ----
    Save-Json (New-RecordJson $source.ID $source.Name @(
        @{ ID = $ghostA.ID; Name = $ghostA.Name; Priority = 0 },
        @{ ID = $ghostB.ID; Name = $ghostB.Name; Priority = 1 }
    ) 'Failover')
    $proc = Start-Agent
    $status = Wait-Settled 6
    Write-Check '候选全都不可用时这一路不算在播' ($null -ne $status -and -not $status.Playing) ("Playing=" + $(if ($status) { $status.Playing } else { '<无记录>' }))
    Write-Check '并且给出了说得清的原因' ($null -ne $status -and -not [string]::IsNullOrEmpty($status.Error)) ("Error=" + $(if ($status) { $status.Error } else { '' }))
    Write-Check '原因里两块设备都被点名' (
        $null -ne $status -and
        $status.Error -like ('*' + $ghostA.Name + '*') -and
        $status.Error -like ('*' + $ghostB.Name + '*')
    ) ("Error=" + $(if ($status) { $status.Error } else { '' }))
    Stop-Agent
    $proc = $null

    # ---- 第四段：顺序反过来，仍然用能用的那一块（证明不是「总挑第二个」） ----
    Save-Json (New-RecordJson $source.ID $source.Name @(
        @{ ID = $real.ID; Name = $real.Name; Priority = 0 },
        @{ ID = $ghostA.ID; Name = $ghostA.Name; Priority = 1 }
    ) 'Failover')
    $proc = Start-Agent
    $status = Wait-Playing
    $active = Active-Names $status
    Write-Check '首选可用时就用首选，不去碰后面那块' ($active.Count -eq 1 -and $active[0] -eq $real.Name) ("在出声的设备=" + ($active -join '、'))
    Stop-Agent
    $proc = $null
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
