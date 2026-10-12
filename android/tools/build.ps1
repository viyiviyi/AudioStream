#requires -Version 5.1
<#
  构建 AudioStream 的 Android 端 APK。

  用法：
    powershell -File tools\build.ps1                 # release（自签名，可直接装手机），含单元测试
    powershell -File tools\build.ps1 -Variant debug
    powershell -File tools\build.ps1 -SkipTest       # 只打包，不跑测试
    powershell -File tools\build.ps1 -Clean
    powershell -File tools\build.ps1 -Install        # 顺手装到唯一在线的那台设备上

  产物：dist\audiostream-android-<版本>-<variant>.apk
#>
param(
  [ValidateSet('release', 'debug')]
  [string]$Variant = 'release',
  [switch]$Clean,
  [switch]$SkipTest,
  [switch]$Install
)
$ErrorActionPreference = 'Stop'

# Gradle/Javac 是按 UTF-8 输出诊断信息的，控制台默认按 GBK 解会变成乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root  = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $root 'tools-cache'

if (-not (Test-Path (Join-Path $cache 'jdk17\bin\java.exe'))) {
  throw "还没准备构建环境，先跑： powershell -File tools\setup-toolchain.ps1"
}

# 环境变量：Gradle 用这套 JDK / SDK；缓存也放在项目里，方便复现和清理
$env:JAVA_HOME        = Join-Path $cache 'jdk17'
$env:ANDROID_HOME     = Join-Path $cache 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $cache 'gradle-home'
$env:PATH             = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Write-Host '== 1/4 准备签名证书 =='
$ks = Join-Path $cache 'audiostream.keystore'
if (-not (Test-Path $ks)) {
  # 证书落在 tools-cache（目录联接指向工作区共享的那份），密码只用于自签名 sideload，
  # 不当成秘密：谁有源码谁都能重新生成一份，重要的是同一台机器上多次构建签名一致。
  & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -v `
    -keystore $ks -alias audiostream -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass audiostream2024 -keypass audiostream2024 `
    -dname 'CN=AudioStream, OU=Android, O=Local, L=Local, ST=Local, C=CN'
  if ($LASTEXITCODE -ne 0) { throw '生成签名证书失败' }
  Write-Host "  已生成 $ks"
} else {
  Write-Host "  复用已有证书 $ks"
}

Write-Host '== 2/4 Gradle 构建 =='
$gradle = Join-Path $cache 'gradle-8.7\bin\gradle.bat'
$tasks = @()
if ($Clean) { $tasks += 'clean' }
if (-not $SkipTest) { $tasks += ':app:testDebugUnitTest' }
$assemble = "assemble$($Variant.Substring(0,1).ToUpper() + $Variant.Substring(1))"
$tasks += ":app:$assemble"

& $gradle -p $root --no-daemon --console=plain @tasks
if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败（$($tasks -join ' ')）" }

Write-Host '== 3/4 收集产物 =='
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$apkDir = Join-Path $root "app\build\outputs\apk\$Variant"
$apk = Get-ChildItem $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apk) { throw "没找到 APK：$apkDir" }

$version = 'x'
$m = Select-String -Path (Join-Path $root 'app\build.gradle') -Pattern "versionName '([^']+)'"
if ($m) { $version = $m.Matches[0].Groups[1].Value }

$out = Join-Path $dist "audiostream-android-$version-$Variant.apk"
Copy-Item $apk.FullName $out -Force
$size = [Math]::Round((Get-Item $out).Length / 1KB, 1)
Write-Host ("APK: {0}  ({1} KB)" -f $out, $size)

if ($Install) {
  Write-Host '== 4/4 安装到设备 =='
  $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
  $devices = & $adb devices | Select-String -Pattern '\sdevice$' | ForEach-Object { ($_ -split '\s+')[0] }
  if (-not $devices) { throw '没有在线设备，先连上手机或起一个模拟器' }
  if ($devices.Count -gt 1) { throw "有多个设备在线，请自己指定：`n  $adb -s <序列号> install -r `"$out`"" }
  & $adb -s $devices[0] install -r $out
  if ($LASTEXITCODE -ne 0) { throw '安装失败' }
  Write-Host ("已装到 {0}" -f $devices[0])
  exit 0
}

Write-Host '== 4/4 完成 =='
Write-Host '安装到手机（先在手机上打开 USB 调试）：'
Write-Host "  $env:ANDROID_HOME\platform-tools\adb.exe install -r `"$out`""
exit 0
