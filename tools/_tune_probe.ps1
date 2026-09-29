# 音质与延迟实测（开发期用）。
#
# 做法：起程序 → 建一条「本机流转」（来源=本机麦克风，目标=虚拟扬声器）→ 把音量设成 0
# → 采一段时间的水位与欠载/漂移计数 → 清干净。
#
# 为什么用麦克风当来源：输出设备在没人放音频时环回一帧都不出，测不出任何东西；
# 麦克风有底噪，一定有数据。音量设 0 是因为 AudioFlowing 看的是播放状态、与音量无关，
# 所以既能测到真实链路，又不会从音箱里出声。
#
# 用法：& tools\_tune_probe.ps1 -Seconds 60

param(
    [int]$Seconds = 60,
    [string]$ExePath,
    # 采集来源设备名（支持通配）。默认取本机默认输入设备。
    # 想验「两端时钟同源时修正次数应当接近 0」，可以换成与目标同一套时钟的输入设备。
    [string]$SourceName = '',
    # 播放目标设备名（支持通配）。默认用虚拟扬声器：它和采集端不是同一个时钟，漂移最大，是压力测试。
    # 换成和采集端同源的设备（比如 Realtek 的输出），就能验「两端时钟同步时修正次数应当接近 0」。
    [string]$TargetName = 'Virtual Speakers*'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $ExePath) { $ExePath = Join-Path $root 'AudioStream\bin\Debug\AudioStream.exe' }
$cfg = Join-Path $env:LOCALAPPDATA 'yiyiooo\AudioStream'
$bak = Join-Path $cfg 'players.json.tune-bak'
$httpBase = 'http://127.0.0.1:12570'

Get-Process AudioStream -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 600
if (Test-Path (Join-Path $cfg 'players.json')) {
    Copy-Item (Join-Path $cfg 'players.json') $bak -Force
}

$playerId = $null
try {
    Start-Process -FilePath $ExePath | Out-Null
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 250
        try { Invoke-RestMethod "$httpBase/api/info" -TimeoutSec 2 | Out-Null; break } catch { }
    }

    $devs = (Invoke-RestMethod "$httpBase/api/devices" -TimeoutSec 5).Result
    if ($SourceName) {
        $src = $devs | Where-Object { $_.Name -like $SourceName -and $_.Flow -eq 'input' } | Select-Object -First 1
    } else {
        $src = $devs | Where-Object { $_.Flow -eq 'input' -and $_.Default } | Select-Object -First 1
    }
    if (-not $src) { $src = $devs | Where-Object { $_.Flow -eq 'input' } | Select-Object -First 1 }
    $dst = $devs | Where-Object { $_.Name -like $TargetName -and $_.Flow -eq 'output' } | Select-Object -First 1
    if (-not $src) { throw '本机找不到输入设备，测不了' }
    if (-not $dst) { throw ("本机找不到名字匹配「" + $TargetName + "」的输出设备，测不了") }
    Write-Host ("来源=" + $src.Name + "  目标=" + $dst.Name)

    $body = @{
        IP               = ''
        SourceDeviceID   = $src.ID
        SourceDeviceName = $src.Name
        Policy           = 'All'
        Targets          = @(@{ DeviceID = $dst.ID; DeviceName = $dst.Name })
    } | ConvertTo-Json -Depth 6
    $created = Invoke-RestMethod "$httpBase/api/players" -Method Post `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 15
    $playerId = $created.Result.ID
    Invoke-RestMethod "$httpBase/api/volume?id=$playerId&val=0" -Method Post -TimeoutSec 5 | Out-Null

    Write-Host ("采样 $Seconds 秒（每 500ms 一次）…")
    $levels = New-Object System.Collections.ArrayList
    $rounds = [int]($Seconds * 2)
    for ($i = 0; $i -lt $rounds; $i++) {
        $st = (Invoke-RestMethod "$httpBase/api/play-status" -TimeoutSec 5).Result |
            Where-Object { $_.ID -eq $playerId }
        if ($st -and $st.Quality) {
            [void]$levels.Add([int]$st.Quality.LevelMs)
            $last = $st.Quality
        }
        Start-Sleep -Milliseconds 500
    }

    if ($levels.Count -eq 0) { throw '一条水位数据都没采到，播放没起来？' }
    $m = $levels | Measure-Object -Minimum -Maximum -Average
    Write-Host ''
    Write-Host ("水位：最小 {0}ms / 平均 {1:N1}ms / 最大 {2}ms（{3} 次采样）" -f $m.Minimum, $m.Average, $m.Maximum, $m.Count)
    Write-Host ("目标 {0}ms  容量 {1}ms" -f $last.TargetLevelMs, $last.CapacityMs)
    Write-Host ("欠载 {0} 次（补 {1}ms）  时钟跟随 {2}ppm  时钟校正 {3} 次 / 补 {4}ms / 丢 {5}ms  溢出丢 {6}ms" -f `
            $last.Underruns, $last.SilenceMs, $last.ResamplePpm, $last.DriftCorrections, $last.DriftRepeatMs, $last.DriftDropMs, $last.DroppedMs)
    Write-Host ("端到端估算 = 采集 10ms + 水位均值 {0:N1}ms + 声卡 10ms ≈ {1:N0}ms" -f $m.Average, (20 + $m.Average))
}
finally {
    if ($playerId) {
        try { Invoke-RestMethod "$httpBase/api/del?id=$playerId" -Method Post -TimeoutSec 5 | Out-Null } catch { }
    }
    Get-Process AudioStream -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 400
    if (Test-Path $bak) {
        Copy-Item $bak (Join-Path $cfg 'players.json') -Force
        Remove-Item $bak -Force
    }
}
