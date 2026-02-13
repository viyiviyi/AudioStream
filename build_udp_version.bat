@echo off
echo ============================================
echo AudioStream UDP版本构建脚本
echo ============================================
echo.

REM 检查是否安装了MSBuild
where msbuild >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo 错误: 未找到MSBuild，请安装Visual Studio或.NET Framework SDK
    pause
    exit /b 1
)

echo 1. 清理旧构建文件...
if exist "AudioStream\bin" (
    rmdir /s /q "AudioStream\bin"
)
if exist "AudioStream\obj" (
    rmdir /s /q "AudioStream\obj"
)

echo.
echo 2. 恢复NuGet包...
cd AudioStream
if exist "packages" (
    echo 已存在packages目录，跳过恢复...
) else (
    nuget restore AudioStream.sln
    if %ERRORLEVEL% neq 0 (
        echo 警告: NuGet包恢复失败，尝试继续构建...
    )
)

echo.
echo 3. 构建项目...
msbuild AudioStream.sln /p:Configuration=Release /p:Platform="Any CPU" /t:Rebuild
if %ERRORLEVEL% neq 0 (
    echo 错误: 构建失败！
    pause
    exit /b 1
)

echo.
echo 4. 复制UDP版本Web界面...
if exist "AudioStream\bin\Release\web\index_udp.html" (
    del "AudioStream\bin\Release\web\index_udp.html"
)
copy "AudioStream\web\index_udp.html" "AudioStream\bin\Release\web\"

echo.
echo 5. 复制UDP版本说明文件...
copy "UDP_VERSION_README.md" "AudioStream\bin\Release\"

echo.
echo ============================================
echo 构建完成！
echo ============================================
echo.
echo 输出目录: AudioStream\bin\Release\
echo.
echo 包含文件:
echo   - AudioStream.exe (主程序)
echo   - UDP_VERSION_README.md (UDP版本说明)
echo   - web\index_udp.html (UDP版本Web界面)
echo.
echo 使用说明:
echo   1. 运行AudioStream.exe启动程序
echo   2. 在系统托盘右键点击程序图标
echo   3. 选择"配置"打开Web界面
echo   4. 在Web界面中启用UDP协议选项
echo.
pause