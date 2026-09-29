# 验证设备枚举：输出设备与输入设备都要报出来，各自标明方向与系统默认项；
# 并且真的拿输入设备点单试采一次，确认麦克风能当来源。
#
# 不建任何播放记录、不播放声音；最后会短暂开一次麦克风采集并立刻释放。
# 用法：pwsh -File tools\verify-devices.ps1

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

# 帧协议工具：握手应答与格式应答现在都是帧
. "$PSScriptRoot\AudioFraming.ps1"

# 读一条文本帧。参数保留只为不改调用处；帧自带长度，不需要靠超时轮询攒到一个换行。
function Read-CommandLine($stream, [int]$timeoutMs) {
    $stream.ReadTimeout = $timeoutMs
    return (Read-TextFrame -Stream $stream)
}

# 读二进制（音频格式头不是文本，不能按行读）
function Read-Bytes($stream, [int]$count, [int]$timeoutMs) {
    $buffer = New-Object byte[] 4096
    $memory = New-Object System.IO.MemoryStream
    $deadline = (Get-Date).AddMilliseconds($timeoutMs)
    while ($memory.Length -lt $count -and (Get-Date) -lt $deadline) {
        if ($stream.DataAvailable) {
            $read = $stream.Read($buffer, 0, $buffer.Length)
            if ($read -le 0) { break }
            $memory.Write($buffer, 0, $read)
        } else {
            Start-Sleep -Milliseconds 20
        }
    }
    $bytes = $memory.ToArray()
    $memory.Dispose()
    return $bytes
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
    $devices = $null
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 250
        try {
            $devices = Invoke-RestMethod 'http://127.0.0.1:12570/api/devices' -TimeoutSec 2
            if ($devices) { break }
        } catch { }
    }

    if (-not $devices) {
        Write-Host "[失败] 配置界面没起来，拿不到 /api/devices" -ForegroundColor Red
        exit 1
    }

    $list = @($devices.Result)
    Write-Host ("设备共 {0} 个" -f $list.Count)
    foreach ($item in $list) {
        Write-Host ("  {0,-7} 默认={1,-6} {2}" -f $item.Flow, $item.Default, $item.Name)
    }

    Write-Check '接口返回成功' ($devices.Success -eq $true) ("Success=" + $devices.Success)
    Write-Check '设备列表非空' ($list.Count -ge 1) ("Count=" + $list.Count)

    $badId = @($list | Where-Object { [string]::IsNullOrWhiteSpace($_.ID) })
    $badName = @($list | Where-Object { [string]::IsNullOrWhiteSpace($_.Name) })
    Write-Check '每个设备都有设备号' ($badId.Count -eq 0) ("缺设备号 " + $badId.Count + " 个")
    Write-Check '每个设备都有名称' ($badName.Count -eq 0) ("缺名称 " + $badName.Count + " 个")

    $badFlow = @($list | Where-Object { $_.Flow -ne 'output' -and $_.Flow -ne 'input' })
    Write-Check '每个设备都标了方向' ($badFlow.Count -eq 0) ("方向异常 " + $badFlow.Count + " 个")

    $outputs = @($list | Where-Object { $_.Flow -eq 'output' })
    $inputs = @($list | Where-Object { $_.Flow -eq 'input' })
    Write-Host ("输出 {0} 个，输入 {1} 个" -f $outputs.Count, $inputs.Count)
    Write-Check '列出了输出设备' ($outputs.Count -ge 1) ("output=" + $outputs.Count)

    $defaultOutputs = @($outputs | Where-Object { $_.Default -eq $true })
    Write-Check '系统默认输出唯一' ($defaultOutputs.Count -eq 1) ("默认输出 " + $defaultOutputs.Count + " 个")

    if ($inputs.Count -gt 0) {
        $defaultInputs = @($inputs | Where-Object { $_.Default -eq $true })
        Write-Check '输入设备被列了出来（麦克风可以当来源）' ($inputs.Count -ge 1) ("input=" + $inputs.Count)
        Write-Check '系统默认输入唯一' ($defaultInputs.Count -eq 1) ("默认输入 " + $defaultInputs.Count + " 个")
    } else {
        Write-Host "[提示] 本机没有输入设备，跳过两项与输入有关的断言" -ForegroundColor Yellow
    }

    # 输出必须整段排在输入前面：界面按这个顺序分组，掺在一起会把麦克风摆到「播放设备」那一栏
    $order = ($list | ForEach-Object { $_.Flow }) -join ','
    Write-Check '输出整段排在输入之前' (-not ($order -match 'input,output')) ("顺序=" + $order)

    # 设备名必须是干净的原名，界面上那个「（默认）」是页面自己加的
    $decorated = @($list | Where-Object { $_.Name -like '*（默认）*' })
    Write-Check '设备名没有被界面装饰污染' ($decorated.Count -eq 0) ("带装饰 " + $decorated.Count + " 个")

    # 页面侧静态检查：分组与「播放设备只列输出」这两件事一旦被回退，界面上就会把麦克风当播放目标
    $pagePath = Join-Path $root 'AudioStream\web\index.html'
    $page = Get-Content -Raw -Path $pagePath -Encoding UTF8
    # 这里只认「输出、输入两个分组标签都在」这件事，不把文案写死：
    # 界面重做会改标点与说明文字（原来是「输出设备（扬声器 / 耳机）」，现在是「输出设备（扬声器、耳机：…）」），
    # 绑死整句会让每次改文案都误报一次。分组本身少了才是真回退——尤其输入那组，没了麦克风就当不了来源。
    Write-Check '页面有输出设备分组' ($page -match '输出设备（') '缺少输出分组标签'
    Write-Check '页面有输入设备分组' ($page -match '输入设备（') '缺少输入分组标签'
    # 播放目标那一栏现在是复选框列表（一来源多设备），不再走 populateDeviceSelect 的 onlyFlow 参数，
    # 所以改为直接检查 renderTargetPicker 自己有没有把候选设备限定在输出上，
    # 以及它在初始加载与刷新时是否都会被调用（漏了刷新那处，列表会停在旧设备上）。
    $pickerIndex = $page.IndexOf('function renderTargetPicker(')
    $pickerBody = ''
    if ($pickerIndex -ge 0) {
        $pickerEnd = $page.IndexOf('function ', $pickerIndex + 10)
        if ($pickerEnd -lt 0) { $pickerEnd = $page.Length }
        $pickerBody = $page.Substring($pickerIndex, $pickerEnd - $pickerIndex)
    }
    $pickerCalls = ([regex]::Matches($page, 'renderTargetPicker\(')).Count - 1
    Write-Check '页面的播放目标只列输出设备' ($pickerBody -match "Flow \|\| 'output'\) === 'output'") 'renderTargetPicker 没有把候选设备限定为输出'
    Write-Check '播放目标列表在初始加载与刷新时都会重渲染' ($pickerCalls -ge 2) ("调用 " + $pickerCalls + " 次")
    Write-Check '页面取设备名走 dataset 而不是界面文字' ($page.Contains('deviceNameOf(')) '没有找到 deviceNameOf'

    # 输入设备不能只是「列得出来」：真拿它点单要能开出采集、真能收到音频，
    # 否则「麦克风当来源」这件事在界面上看着有、用起来是坏的。
    if ($inputs.Count -gt 0) {
        $info = Invoke-RestMethod 'http://127.0.0.1:12570/api/info' -TimeoutSec 2
        $tcpPort = $info.Result.TcpPort
        # 试采用系统默认的那个麦克风：列表里第一个输入设备可能是 UU 远程之类的虚拟设备，
        # 没有远程会话时它一帧数据都不产出，拿它试采会误报「没在推音频」。
        $mic = $inputs | Where-Object { $_.Default } | Select-Object -First 1
        if (-not $mic) { $mic = $inputs[0] }
        Write-Host ("拿输入设备点单试采：" + $mic.Name)

        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $client.NoDelay = $true
            $client.Connect('127.0.0.1', $tcpPort)
            $stream = $client.GetStream()
            $stream.ReadTimeout = 4000

            Write-TextFrame -Stream $stream -Text '/Hello/deadbeefdeadbeefdeadbeefdeadbeef/verify-devices'
            $hello = Read-CommandLine $stream 2000
            Write-Check '按输入设备点单前握手正常' ($hello.StartsWith('/Hello/')) ("握手=" + $hello)

            Write-TextFrame -Stream $stream -Text ("/WaveFormat/" + $mic.ID + "/" + $mic.Name)

            # 放行时回的是一条音频格式帧，负载就是格式头字节
            $formatFrame = Read-Frame -Stream $stream
            $header = New-Object byte[] 0
            if ($null -ne $formatFrame -and $formatFrame.Kind -eq 3) { $header = $formatFrame.Payload }
            Write-Check '输入设备能开出采集（拿到格式头）' ($header.Length -ge 13) ("收到 " + $header.Length + " 字节")
            if ($header.Length -ge 13) {
                $sampleRate = [BitConverter]::ToInt32($header, 0)
                $bits = [BitConverter]::ToInt32($header, 4)
                $channels = [BitConverter]::ToInt32($header, 8)
                Write-Check '格式头里的采样率合理' ($sampleRate -ge 8000 -and $sampleRate -le 192000) ("采样率=" + $sampleRate)
                Write-Check '格式头里的位深合理' ($bits -ge 8 -and $bits -le 64) ("位深=" + $bits)
                Write-Check '格式头里的声道数合理' ($channels -ge 1 -and $channels -le 8) ("声道=" + $channels)
            }

            Write-TextFrame -Stream $stream -Text '/Start'

            # 直接读一段，按帧头解析出「负载长度 + 类型」，确认对端真的在推音频帧。
            # 采集回调偶尔会给一次 0 字节（那种空帧也是合法帧），所以最多重读几次找一条非空的。
            # 整帧收全、每帧帧对齐、音频时长与墙上时间的比值这三项由 verify-frames.ps1 端到端覆盖。
            $stream.ReadTimeout = 3000
            $probe = New-Object 'byte[]' 8192
            $got = 0
            $probeKind = -1
            $probePayload = -1
            for ($try = 0; $try -lt 8; $try++) {
                try { $got = $stream.Read($probe, 0, $probe.Length) } catch { $got = 0; break }
                if ($got -lt 5) { break }
                $probeLen = [BitConverter]::ToInt32($probe, 0)
                $probeKind = [int]$probe[4]
                $probePayload = $probeLen - 1
                if ($probeKind -eq 1 -and $probePayload -gt 0) { break }
            }
            Write-Check '输入设备真的在推音频数据' `
                ($got -ge 5 -and $probeKind -eq 1 -and $probePayload -gt 0) `
                ("收到 " + $got + " 字节，帧类型=" + $probeKind + " 负载=" + $probePayload)

            Write-TextFrame -Stream $stream -Text '/Pause'
        } finally {
            $client.Close()
        }

        # 试采完必须把采集放掉，否则后面再跑别的脚本会撞上「设备被占」。
        # 只断言本次点单的那个设备：这台机器上可能同时有别的机器在拉别的设备，那与本次测试无关。
        Start-Sleep -Milliseconds 500
        $captures = Invoke-RestMethod 'http://127.0.0.1:12570/api/captures' -TimeoutSec 2
        $stillMine = @($captures.Result | Where-Object { $_ -eq $mic.ID })
        Write-Check '断开后本次点单的采集已释放' ($stillMine.Count -eq 0) ("仍在采集 " + @($captures.Result).Count + " 个设备")
    }
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
