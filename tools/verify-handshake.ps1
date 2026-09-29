# 验证握手协议：本机身份能报出来，并且「自己拉自己」会在握手阶段被拒绝。
#
# 这个脚本只在本机跑，不碰声卡——被拒绝的连接根本不会去开音频设备。
# 用法：pwsh -File tools\verify-handshake.ps1

param(
    [string]$ExePath
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $ExePath) { $ExePath = Join-Path $root 'AudioStream\bin\Debug\AudioStream.exe' }

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

# 帧协议工具：握手应答现在是一条文本帧
. "$PSScriptRoot\AudioFraming.ps1"

# 读一条文本帧。参数保留只为不改调用处；帧自带长度，不需要靠超时轮询攒到一个换行。
function Read-CommandLine($stream, [int]$timeoutMs) {
    $stream.ReadTimeout = $timeoutMs
    return (Read-TextFrame -Stream $stream)
}

function Invoke-Hello([int]$port, [string]$machineId, [string]$pcName) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $port)
        $stream = $client.GetStream()
        Write-TextFrame -Stream $stream -Text ("/Hello/$machineId/$pcName")
        return Read-CommandLine $stream 2000
    } finally {
        $client.Close()
    }
}

if (-not (Test-Path $ExePath)) {
    Write-Host "找不到可执行文件：$ExePath" -ForegroundColor Red
    exit 1
}

Write-Host "被测程序：$ExePath"
Get-Process AudioStream -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500

# 记录一下 Run 键，测试完只删掉本脚本自己写进去的那一条
$runKey = 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run'
$runBefore = $null
try { $runBefore = (Get-ItemProperty -Path $runKey -Name 'AudioStream.exe' -ErrorAction SilentlyContinue).'AudioStream.exe' } catch { }

$proc = Start-Process -FilePath $ExePath -PassThru
try {
    $info = $null
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 250
        try {
            $info = Invoke-RestMethod 'http://127.0.0.1:12570/api/info' -TimeoutSec 2
            if ($info) { break }
        } catch { }
    }

    if (-not $info) {
        Write-Host "[失败] 配置界面没有起来，拿不到 /api/info" -ForegroundColor Red
        exit 1
    }

    $body = $info.Result
    Write-Host ("本机身份：{0} / {1}，TCP {2}，HTTP {3}，地址 {4}" -f $body.MachineId, $body.PcName, $body.TcpPort, $body.HttpPort, ($body.Addresses -join ', '))

    Write-Check '本机身份已生成' ($body.MachineId -and $body.MachineId.Length -ge 16) ("MachineId=" + $body.MachineId)
    Write-Check '计算机名非空' (-not [string]::IsNullOrWhiteSpace($body.PcName)) ("PcName=" + $body.PcName)
    Write-Check 'TCP 端口已报告' ($body.TcpPort -gt 0) ("TcpPort=" + $body.TcpPort)
    Write-Check 'HTTP 端口已报告' ($body.HttpPort -eq 12570) ("HttpPort=" + $body.HttpPort)
    Write-Check '列出了本机 IPv4 地址' ($body.Addresses.Count -ge 1) ("Addresses=" + ($body.Addresses -join ', '))

    # 拿自己的身份去连自己，必须被拒
    $selfReply = Invoke-Hello $body.TcpPort $body.MachineId 'localhost-test'
    Write-Check '自连被拒绝' ($selfReply -eq '/Reject/self') ("回复=$selfReply")

    # 换一个身份，必须拿到对端身份应答
    $otherReply = Invoke-Hello $body.TcpPort 'deadbeefdeadbeefdeadbeefdeadbeef' 'peer-test'
    $expected = '/Hello/' + $body.MachineId + '/'
    Write-Check '正常握手拿到对端身份' ($otherReply.StartsWith($expected)) ("回复=$otherReply")
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
    try {
        $runNow = (Get-ItemProperty -Path $runKey -Name 'AudioStream.exe' -ErrorAction SilentlyContinue).'AudioStream.exe'
        if ($runNow -and $runNow -ne $runBefore -and $runNow -eq $ExePath) {
            Remove-ItemProperty -Path $runKey -Name 'AudioStream.exe' -ErrorAction SilentlyContinue
            Write-Host "已清理测试期间写入的开机自启项"
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
