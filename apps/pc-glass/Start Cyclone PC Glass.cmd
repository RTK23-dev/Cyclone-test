@echo off
REM Cyclone PC Glass — starts this tree with CYCLONE_CONNECTED=1 (gateway driver).
setlocal EnableExtensions
cd /d "%~dp0"
set "CYCLONE_CONNECTED=1"
if not defined CYCLONE_DEVICE_GATEWAY_URL set "CYCLONE_DEVICE_GATEWAY_URL=http://127.0.0.1:8765"
if not defined CYCLONE_SESSION_ID set "CYCLONE_SESSION_ID=default-foreground"
call "%~dp0start.bat" %*
