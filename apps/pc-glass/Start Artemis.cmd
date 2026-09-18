@echo off
setlocal EnableExtensions
title Artemis - Starting
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

echo Checking Artemis...
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8000/api/status' -TimeoutSec 3; if ($r.StatusCode -eq 200) { exit 0 } } catch {}; exit 1"
if not errorlevel 1 (
  echo Artemis is already running. Opening http://localhost:8000
  start "" "http://localhost:8000"
  exit /b 0
)

echo Starting Artemis...
uv run artemis restart --daemon --open
if errorlevel 1 (
  echo.
  echo Artemis could not start. Keep this window open and share the error if you need help.
  pause
  exit /b 1
)

echo Artemis is running in your browser.
exit /b 0
