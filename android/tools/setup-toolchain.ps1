#requires -Version 5.1
<#
  一次性准备 Android 构建环境（不需要 Android Studio）。

  本工程的工具链不自己下载，而是复用本机上已经装好的那一份（默认就是
  C:\apps\dsh_workspace\pinyin-ime\tools-cache：JDK 17 + Gradle 8.7 +
  Android SDK platform-34 / build-tools 34.0.0，Gradle 缓存里已含 AGP 8.5.2）。
  本工程的 tools-cache 是指向它的目录联接（不需要管理员权限），因此不占额外空间。

  用法：
    powershell -File tools\setup-toolchain.ps1
    powershell -File tools\setup-toolchain.ps1 -Shared 'D:\somewhere\tools-cache'

  跑完得到：
    tools-cache\jdk17\                       JDK 17
    tools-cache\gradle-8.7\                  Gradle
    tools-cache\android-sdk\                 Android SDK
    local.properties                         把 SDK 路径告诉 Gradle
#>
param(
  [string]$Shared = 'C:\apps\dsh_workspace\pinyin-ime\tools-cache'
)
$ErrorActionPreference = 'Stop'

$root  = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $root 'tools-cache'

$needJava   = Join-Path $cache 'jdk17\bin\java.exe'
$needGradle = Join-Path $cache 'gradle-8.7\bin\gradle.bat'
$needSdk    = Join-Path $cache 'android-sdk\platforms\android-34\android.jar'

function Test-Ready {
    (Test-Path $needJava) -and (Test-Path $needGradle) -and (Test-Path $needSdk)
}

if (-not (Test-Ready)) {
    if (-not (Test-Path (Join-Path $Shared 'jdk17\bin\java.exe'))) {
        throw "共享工具链里没有 JDK：$Shared`n请用 -Shared 指定一份带 jdk17 / gradle-8.7 / android-sdk 的 tools-cache。"
    }
    Write-Host "复用 $Shared"
    $existing = Get-Item -LiteralPath $cache -Force -ErrorAction SilentlyContinue
    if ($null -ne $existing) {
        if ($existing.LinkType -eq 'Junction') {
            # 目录联接只能用 rmdir 删链接本身：Remove-Item -Recurse 会顺着链接
            # 把共享的那份工具链整个删掉
            & cmd /c "rmdir `"$cache`""
        } else {
            Remove-Item -Recurse -Force $cache
        }
    }
    & cmd /c "mklink /J `"$cache`" `"$Shared`"" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "建立目录联接失败：$cache → $Shared" }
}

if (-not (Test-Ready)) {
    throw "工具链不完整：$cache（缺 JDK、Gradle 或 SDK platform-34）"
}

$sdk = Join-Path $cache 'android-sdk'
$sdkEscaped = $sdk -replace '\\', '\\'
"sdk.dir=$sdkEscaped" | Set-Content -Path (Join-Path $root 'local.properties') -Encoding ascii

Write-Host '== 检查 =='
$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& cmd /c "`"$needJava`" -version 2>&1" | Select-Object -First 1
$ErrorActionPreference = $prev
Write-Host ("platform  : " + (Test-Path $needSdk))
Write-Host ("build-tool: " + (Test-Path (Join-Path $sdk 'build-tools\34.0.0\aapt2.exe')))
Write-Host ("gradle    : " + (Test-Path $needGradle))
Write-Host '环境就绪。下一步： powershell -File tools\build.ps1'
