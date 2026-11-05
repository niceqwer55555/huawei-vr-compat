@echo off
chcp 65001 >nul
setlocal
rem ==========================================================================
rem  华为 VR Glass 一键点亮（Windows 双击即可）
rem  用法：双击，或  一键点亮.bat [设备序列号] [on^|check^|off]
rem  自包含：每次都会把最新点亮脚本推到盒子，刷机后不用先跑完整安装工具。
rem  真实逻辑在设备端 magisk-module\runtime\light-on.sh
rem ==========================================================================

set "SERIAL=%~1"
if "%SERIAL%"=="" set "SERIAL=192.168.43.133:5555"
set "ACTION=%~2"
if "%ACTION%"=="" set "ACTION=on"
set "HERE=%~dp0"
set "SRC=%HERE%magisk-module\runtime\light-on.sh"

set "ADBEXE="
if defined ADB if exist "%ADB%" set "ADBEXE=%ADB%"
if not defined ADBEXE if exist "D:\Tools\AndroidSDK\platform-tools\adb.exe" set "ADBEXE=D:\Tools\AndroidSDK\platform-tools\adb.exe"
if not defined ADBEXE if exist "F:\Tools\AndroidSDK\platform-tools\adb.exe" set "ADBEXE=F:\Tools\AndroidSDK\platform-tools\adb.exe"
if not defined ADBEXE if exist "C:\platform-tools\adb.exe" set "ADBEXE=C:\platform-tools\adb.exe"
if not defined ADBEXE (
    for /f "delims=" %%i in ('where adb 2^>nul') do if not defined ADBEXE set "ADBEXE=%%i"
)
if not defined ADBEXE (
    echo [FATAL] 找不到 adb.exe，请把 AndroidSDK\platform-tools 加进 PATH
    echo         或设置环境变量 ADB 指向 adb.exe
    pause
    exit /b 1
)
if not exist "%SRC%" (
    echo [FATAL] 缺少 %SRC%
    pause
    exit /b 1
)

echo [i] adb    = %ADBEXE%
echo [i] 设备   = %SERIAL%
echo [i] 动作   = %ACTION%
echo.

"%ADBEXE%" connect %SERIAL% >nul 2>&1
"%ADBEXE%" -s %SERIAL% wait-for-device >nul 2>&1
if errorlevel 1 (
    echo [FATAL] 设备连不上：检查盒子是否开机、无线 adb 是否已开启
    pause
    exit /b 1
)

echo [i] 同步点亮脚本到设备 ...
"%ADBEXE%" -s %SERIAL% push "%SRC%" /data/local/tmp/light-on.sh >nul 2>&1
if errorlevel 1 (
    echo [FATAL] push 失败
    pause
    exit /b 1
)
"%ADBEXE%" -s %SERIAL% shell "su -c 'mkdir -p /data/adb/hvrglass; cp /data/local/tmp/light-on.sh /data/adb/hvrglass/light-on.sh; chmod 755 /data/adb/hvrglass/light-on.sh'" >nul 2>&1

echo.
"%ADBEXE%" -s %SERIAL% shell "su -c 'sh /data/adb/hvrglass/light-on.sh %ACTION%'"
echo.
echo [i] 设备端完整日志：/data/local/tmp/hvr-light-on.log
echo.
pause
