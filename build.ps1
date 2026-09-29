<#
    AudioStream 一键构建脚本

    为什么需要它：本机只有 .NET SDK 自带的 MSBuild（没有 Visual Studio），
    而解决方案里的 AudioStreamSetup.vdproj 是 VS Installer 项目，构建不了。
    所以这里只构建 AudioStream.csproj，并把可分发文件整理到 dist 目录。

    用法（本机没有 pwsh，只有 Windows PowerShell 5.1，要这样调）：
        & C:\project\AudioStream\build.ps1                     # Release 构建，产物到 dist\
        & C:\project\AudioStream\build.ps1 -Configuration Debug
        & C:\project\AudioStream\build.ps1 -Pack               # 额外复制到 X:\softs\AudioStream
        & C:\project\AudioStream\build.ps1 -Pack -PackDir D:\somewhere
#>
[CmdletBinding()]
param(
    # 构建配置
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Release',

    # 构建完是否把 dist 复制到分发目录
    [switch]$Pack,

    # 分发目录
    [string]$PackDir = 'X:\softs\AudioStream'
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$project = Join-Path $root 'AudioStream\AudioStream.csproj'
$outDir = Join-Path $root "AudioStream\bin\$Configuration"
$distDir = Join-Path $root 'dist'

if (-not (Test-Path $project)) {
    throw "找不到工程文件：$project"
}

Write-Host "[1/3] 构建 $Configuration ..." -ForegroundColor Cyan
& dotnet build $project -c $Configuration -v m
if ($LASTEXITCODE -ne 0) {
    throw "构建失败，dotnet build 退出码 $LASTEXITCODE"
}

Write-Host "[2/3] 整理产物到 dist ..." -ForegroundColor Cyan
if (Test-Path $distDir) {
    Remove-Item $distDir -Recurse -Force
}
New-Item -ItemType Directory -Path $distDir -Force | Out-Null

# 调试符号不进分发目录；其余文件（含 web 子目录）按原相对路径复制
$files = Get-ChildItem -Path $outDir -Recurse -File | Where-Object { $_.Extension -ne '.pdb' }
foreach ($file in $files) {
    $relative = $file.FullName.Substring($outDir.Length + 1)
    $target = Join-Path $distDir $relative
    $targetParent = Split-Path $target -Parent
    if (-not (Test-Path $targetParent)) {
        New-Item -ItemType Directory -Path $targetParent -Force | Out-Null
    }
    Copy-Item -Path $file.FullName -Destination $target -Force
}
Write-Host ("      共 {0} 个文件，{1:N1} MB" -f $files.Count, (($files | Measure-Object Length -Sum).Sum / 1MB))

if ($Pack) {
    Write-Host "[3/3] 复制到 $PackDir ..." -ForegroundColor Cyan
    if (Test-Path $PackDir) {
        Remove-Item $PackDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $PackDir -Force | Out-Null
    Copy-Item -Path (Join-Path $distDir '*') -Destination $PackDir -Recurse -Force
    Write-Host "      完成：$PackDir" -ForegroundColor Green
}
else {
    Write-Host "[3/3] 跳过分发复制（需要时加 -Pack）" -ForegroundColor DarkGray
}

Write-Host "构建成功：$distDir\AudioStream.exe" -ForegroundColor Green
