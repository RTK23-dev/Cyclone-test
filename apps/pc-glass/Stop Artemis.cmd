@echo off
setlocal EnableExtensions
title Artemis - Stopping
set "ARTEMIS_DIR=C:\Users\Agent\artemis"
set "PATH=%LOCALAPPDATA%\Programs\scrcpy;%LOCALAPPDATA%\hermes\bin;%PATH%"

cd /d "%ARTEMIS_DIR%"
if not exist "pyproject.toml" (
  echo Artemis was not found at %ARTEMIS_DIR%
  pause
  exit /b 1
)

where uv >nul 2>&1
if errorlevel 1 (
  echo The uv tool was not found. Install it from https://docs.astral.sh/uv/ then try again.
  pause
  exit /b 1
)

echo Stopping Artemis...
uv run artemis stop
if errorlevel 1 (
  echo.
  echo Artemis could not be stopped cleanly. Keep this window open and share the error if you need help.
  pause
  exit /b 1
)

echo Artemis has stopped.
exit /b 0
