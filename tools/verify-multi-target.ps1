# 验证「一条来源多个播放目标」的数据模型与配置迁移。
#
# 只碰配置文件与 HTTP 接口，造出来的记录一律 Play=false，所以不会去开任何声卡。
# 用法：pwsh -File tools\verify-multi-target.ps1

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

$legacyTargetId = '{0.0.0.00000000}.{11111111-1111-1111-1111-111111111111}'
$legacyTargetName = '旧格式目标设备'
$primaryTargetId = '{0.0.0.00000000}.{22222222-2222-2222-2222-222222222222}'
$backupTargetId = '{0.0.0.00000000}.{33333333-3333-3333-3333-333333333333}'
$recordId = '44444444-4444-4444-4444-444444444444'

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

function Save-Config([string]$json) {
    if (-not (Test-Path $configDir)) { New-Item -ItemType Directory -Path $configDir -Force | Out-Null }
    Set-Content -Path $configPath -Value $json -Encoding utf8 -NoNewline
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
    # ---- 第一段：旧格式（只有单个目标字段）读进来要能迁移 ----
    $legacyJson = '[{"ID":"' + $recordId + '","SourceDeviceID":"default","SourceDeviceName":"系统默认",' +
        '"TargetDeiceID":"' + $legacyTargetId + '","TargetDeiceName":"' + $legacyTargetName + '",' +
        '"IP":"192.168.31.32","PcName":null,"RemakeName":null,"Volume":1.0,"Index":1,"Play":false,"Hidden":false}]'
    Save-Config $legacyJson

    $proc = Start-Agent
    $players = @(Get-Players)
    Write-Check '旧配置读进来还是一条记录' ($players.Count -eq 1) ("条数=" + $players.Count)

    $one = $players[0]
    Write-Check '旧记录补出了策略字段' ($one.Policy -eq 'All') ("Policy=" + $one.Policy)
    Write-Check '旧记录补出了一条目标' ($one.Targets.Count -eq 1) ("目标数=" + $one.Targets.Count)
    Write-Check '目标设备号来自旧字段' ($one.Targets[0].DeviceID -eq $legacyTargetId) ("DeviceID=" + $one.Targets[0].DeviceID)
    Write-Check '目标设备名来自旧字段' ($one.Targets[0].DeviceName -eq $legacyTargetName) ("DeviceName=" + $one.Targets[0].DeviceName)
    Write-Check '内部字段没有漏给界面' (($null -eq $one.PrimaryTarget) -and ($null -eq $one.LegacyTargetDeviceID)) ("PrimaryTarget=" + $one.PrimaryTarget + " Legacy=" + $one.LegacyTargetDeviceID)

    # 触发一次保存：音量接口即使这一路没在跑，也会把配置写回磁盘
    $null = Invoke-RestMethod "$httpBase/api/volume?id=$recordId&val=1" -Method Post -TimeoutSec 5
    Start-Sleep -Milliseconds 300

    $saved = Get-Content $configPath -Raw -Encoding UTF8
    Write-Check '保存后不再写出旧字段' (-not $saved.Contains('TargetDeiceID')) "落盘内容=$saved"
    Write-Check '保存后写出目标数组' ($saved.Contains('"Targets"')) "落盘内容=$saved"
    Write-Check '保存后写出字符串形式的策略' ($saved.Contains('"Policy":"All"')) "落盘内容=$saved"

    Stop-Agent
    $proc = $null

    # ---- 第二段：新格式的多目标要原样读回来 ----
    $multiJson = '[{"ID":"' + $recordId + '","SourceDeviceID":"default","SourceDeviceName":"系统默认",' +
        '"IP":"192.168.31.32","PcName":null,"RemakeName":null,"Volume":1.0,"Index":1,"Play":false,"Hidden":false,' +
        '"Policy":"Failover","Targets":[' +
        '{"DeviceID":"' + $primaryTargetId + '","DeviceName":"主设备","Priority":0},' +
        '{"DeviceID":"' + $backupTargetId + '","DeviceName":"备设备","Priority":1}]}]'
    Save-Config $multiJson

    $proc = Start-Agent
    $players = @(Get-Players)
    $one = $players[0]
    Write-Check '多目标配置读进来还是一条记录' ($players.Count -eq 1) ("条数=" + $players.Count)
    Write-Check '两个目标都在' ($one.Targets.Count -eq 2) ("目标数=" + $one.Targets.Count)
    Write-Check '第一个目标号正确' ($one.Targets[0].DeviceID -eq $primaryTargetId) ("DeviceID=" + $one.Targets[0].DeviceID)
    Write-Check '第一个目标优先级为 0' ($one.Targets[0].Priority -eq 0) ("Priority=" + $one.Targets[0].Priority)
    Write-Check '第二个目标号正确' ($one.Targets[1].DeviceID -eq $backupTargetId) ("DeviceID=" + $one.Targets[1].DeviceID)
    Write-Check '第二个目标优先级为 1' ($one.Targets[1].Priority -eq 1) ("Priority=" + $one.Targets[1].Priority)
    Write-Check '策略读回来是 Failover' ($one.Policy -eq 'Failover') ("Policy=" + $one.Policy)

    # 界面拿到的这份是副本，改它不该影响正在跑的那一份
    $snapshot = @(Get-Players)
    $snapshot[0].Targets[0].DeviceName = '改过的名字'
    $again = Get-Players
    Write-Check '接口返回的是副本而不是内部对象' ($again[0].Targets[0].DeviceName -eq '主设备') ("再次读取=" + $again[0].Targets[0].DeviceName)
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
