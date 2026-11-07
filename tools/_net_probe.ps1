# 网络拉取实测（开发期用）。
#
# 做法：起程序 → 建一条「走 IP 的远程拉取」，来源=本机输入设备，目标=虚拟扬声器，
# 对端地址填 127.0.0.1（回环），于是网络条件被消掉，量到的是软件栈的下限。
# 音量设 0，不会从音箱里出声。
#
# 注意：自连（对端地址填本机）是支持的，不需要任何绕过手段。
#
# 用法：& tools\_net_probe.ps1 -Seconds 60
#       & tools\_net_probe.ps1 -Seconds 30 -Ip 192.168.31.33

param(
    [int]$Seconds = 60,
    [string]$ExePath,
    # 对端地址。127.0.0.1 是纯回环；填本机网卡 IP 会走真实网卡栈。
    [string]$Ip = '127.0.0.1',
    [string]$SourceName = '',
    [string]$TargetName = 'Virtual Speakers*'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $ExePath) { $ExePath = Join-Path $root 'AudioStream\bin\Release\AudioStream.exe' }
$cfg = Join-Path $env:LOCALAPPDATA 'yiyiooo\AudioStream'
$bak = Join-Path $cfg 'players.json.netprobe-bak'
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
    Write-Host ("来源=" + $src.Name + "  目标=" + $dst.Name + "  对端=" + $Ip)

    $body = @{
        IP               = $Ip
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

    # 等这一路真的开始出数据，别把连接建立的时间算进采样
    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 250
        $st = (Invoke-RestMethod "$httpBase/api/play-status" -TimeoutSec 5).Result |
            Where-Object { $_.ID -eq $playerId }
        if ($st -and $st.Quality -and $st.Quality.LevelMs -gt 0) { $ready = $true; break }
        if ($st -and $st.Note) { Write-Host ("状态：" + $st.Note) }
    }
    if (-not $ready) { throw '这一路没起来（10 秒内没看到水位），看上面的状态' }

    Write-Host ("采样 $Seconds 秒（每 500ms 一次）…")
    $levels = New-Object System.Collections.ArrayList
    $rounds = [int]($Seconds * 2)
    $last = $null
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
    Write-Host ("端到端估算 ≈ 采集 10ms + 水位均值 {0:N1}ms + 声卡 10ms ≈ {1:N0}ms" -f $m.Average, (20 + $m.Average))
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
