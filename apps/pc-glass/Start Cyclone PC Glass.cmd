@echo off
REM Preferred: no-console Windows helper UI (Start/Stop/Open).
cd /d "%~dp0"
if exist "%~dp0windows-helper\Launch Cyclone PC Glass Helper.vbs" (
  wscript //nologo "%~dp0windows-helper\Launch Cyclone PC Glass Helper.vbs"
  exit /b 0
)
REM Fallback console bootstrap if helper missing:
setlocal EnableExtensions
set "CYCLONE_CONNECTED=1"
if not defined CYCLONE_DEVICE_GATEWAY_URL set "CYCLONE_DEVICE_GATEWAY_URL=http://127.0.0.1:8765"
if not defined CYCLONE_SESSION_ID set "CYCLONE_SESSION_ID=default-foreground"
call "%~dp0start.bat" %*
