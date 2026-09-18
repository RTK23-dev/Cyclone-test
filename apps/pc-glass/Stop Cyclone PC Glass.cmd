@echo off
setlocal EnableExtensions
title Cyclone PC Glass - Stopping
cd /d "%~dp0"
where uv >nul 2>&1
if errorlevel 1 (
  echo The uv tool was not found. Install it from https://docs.astral.sh/uv/ then try again.
  pause
  exit /b 1
)
echo Stopping Cyclone PC Glass...
uv run artemis stop
if errorlevel 1 (
  echo.
  echo Could not stop cleanly. Keep this window open and share the error if you need help.
  pause
  exit /b 1
)
echo Cyclone PC Glass has stopped.
exit /b 0
