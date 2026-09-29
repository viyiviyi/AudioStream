# 帧协议的 PowerShell 侧实现，给各个验证脚本 dot-source 用。
#
# 帧 = [4 字节小端长度][1 字节类型][负载]，长度含类型字节本身。
# 类型：1 = 音频（裸 PCM），2 = 文本命令（UTF-8，不带换行），3 = 音频格式（二进制）。
#
# 为什么要改成帧：在这之前音频走裸字节流，接收侧靠「一次读到 ≤32 字节就当心跳丢掉」
# 这种长度猜测区分控制包与音频。TCP 没有消息边界，这个猜测迟早会吃掉一段音频字节，
# 之后所有采样都错开，听起来就是持续的杂音。带长度头之后，任何一段数据属于哪一类、
# 有多长都是确定的，不存在猜错的可能。
#
# 用法：. "$PSScriptRoot\AudioFraming.ps1"

function Write-Frame {
    param($Stream, [int]$Kind, [byte[]]$Payload)
    if ($null -eq $Payload) { $Payload = New-Object 'byte[]' 0 }
    $total = $Payload.Length + 1
    $header = New-Object 'byte[]' 5
    $header[0] = [byte]($total -band 0xFF)
    $header[1] = [byte](($total -shr 8) -band 0xFF)
    $header[2] = [byte](($total -shr 16) -band 0xFF)
    $header[3] = [byte](($total -shr 24) -band 0xFF)
    $header[4] = [byte]$Kind
    $Stream.Write($header, 0, 5)
    if ($Payload.Length -gt 0) { $Stream.Write($Payload, 0, $Payload.Length) }
    $Stream.Flush()
}

function Write-TextFrame {
    param($Stream, [string]$Text)
    Write-Frame -Stream $Stream -Kind 2 -Payload ([Text.Encoding]::UTF8.GetBytes($Text))
}

function Write-FormatFrame {
    param($Stream, [byte[]]$Payload)
    Write-Frame -Stream $Stream -Kind 3 -Payload $Payload
}

# 一次读满 Count 字节。TCP 的一次 Read 只保证「至少 1 字节」，不能假设读满。
function Read-Exact {
    param($Stream, [int]$Count)
    # 注意那个逗号：PowerShell 函数「返回空数组」时会被管道展开成什么都没有，
    # 调用方拿到的是 $null，看着就像「流结束了」。加逗号强制它作为数组整体返回。
    if ($Count -le 0) { return ,(New-Object 'byte[]' 0) }
    $buffer = New-Object 'byte[]' $Count
    $done = 0
    while ($done -lt $Count) {
        $read = $Stream.Read($buffer, $done, $Count - $done)
        if ($read -le 0) { return $null }
        $done += $read
    }
    return $buffer
}

# 读一帧。连接断开或读不全时返回 $null。
function Read-Frame {
    param($Stream)
    $header = Read-Exact -Stream $Stream -Count 5
    if ($null -eq $header) { return $null }
    # 长度字段一律用 BitConverter 解析。别写成 $header[0] -bor ($header[1] -shl 8) 这种位移式：
    # 那样算出来的值在长度超过 255 字节时是错的（只有首字节非零的小帧才碰巧对），
    # 于是读错字节数、整条流从那儿开始错位——现象就是「第一帧正常，第二帧起全是垃圾」。
    $len = [BitConverter]::ToInt32($header, 0)
    $kind = [int]$header[4]
    $payloadLen = $len - 1
    if ($payloadLen -lt 0) { return $null }
    # 负载为 0 的帧是合法的（采集回调偶尔会给一次 0 字节）。这里必须绕开 Read-Exact：
    # 它返回空数组时会被展开成 $null，和「流结束」混在一起，结果就是把一条正常帧当成对端断开。
    $payload = $null
    if ($payloadLen -gt 0) {
        $payload = Read-Exact -Stream $Stream -Count $payloadLen
        if ($null -eq $payload) { return $null }
    } else {
        $payload = New-Object 'byte[]' 0
    }
    $text = $null
    if ($kind -eq 2) { $text = [Text.Encoding]::UTF8.GetString($payload) }
    return [pscustomobject]@{
        Kind    = $kind
        Length  = $len
        Payload = $payload
        Text    = $text
    }
}

# 读一帧并要求它是文本帧，直接返回文本；不是文本帧时返回带方括号的标记，方便断言里看出实际情况。
function Read-TextFrame {
    param($Stream)
    $frame = Read-Frame -Stream $Stream
    if ($null -eq $frame) { return '<连接已断开>' }
    if ($frame.Kind -eq 2) { return $frame.Text }
    return ('<非文本帧 kind=' + $frame.Kind + '>')
}
