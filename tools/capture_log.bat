@echo off
rem Capture DoubaoA11y logs from the phone over adb.
rem Double-click to start; press Ctrl+C (or close the window) to stop.
rem The log file goes to the logs folder next to tools.

if not defined ADB set "ADB=%USERPROFILE%\AndroidSdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%i"
if not exist "%~dp0..\logs" mkdir "%~dp0..\logs"
set "OUT=%~dp0..\logs\doubao_log_%TS%.txt"

"%ADB%" logcat -G 16M
echo Saving DoubaoA11y log to %OUT%
echo Press Ctrl+C to stop.
"%ADB%" logcat -v time -s DoubaoA11y > "%OUT%"
