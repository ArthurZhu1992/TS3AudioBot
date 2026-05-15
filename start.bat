@echo off
chcp 65001 >nul
REM TS3AudioBot 启动脚本 (Windows)
REM 用法: start.bat [JVM参数...]
REM 自动使用同目录下的 ffmpeg/ 和 yt-dlp，无需额外配置。

setlocal enabledelayedexpansion

REM 检测 Java
where java >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\java.exe" (
            set "JAVA=%JAVA_HOME%\bin\java.exe"
        ) else (
            echo 错误: JAVA_HOME 下未找到 java.exe
            echo 请安装 Java 21+ (推荐: https://adoptium.net/)
            pause
            exit /b 1
        )
    ) else (
        echo 错误: 未找到 Java。请安装 Java 21+ (推荐: https://adoptium.net/)
        echo       或设置 JAVA_HOME 环境变量指向 JDK/JRE 安装目录。
        pause
        exit /b 1
    )
) else (
    set "JAVA=java"
)

REM 查找 JAR
set "JAR="
for %%f in (TS3AudioBot-*.jar) do (
    if not defined JAR set "JAR=%%f"
)

if not defined JAR (
    echo 错误: 未找到 TS3AudioBot-*.jar，请确认与启动脚本在同一目录。
    pause
    exit /b 1
)

echo TS3AudioBot 启动中...
for /f "tokens=*" %%i in ('"!JAVA!" -version 2^>^&1 ^| find "version"') do echo   Java: %%i
echo   JAR:  !JAR!
echo.

"!JAVA!" -jar "!JAR!" %*
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo 程序已退出 (代码: %ERRORLEVEL%)
    pause
)
