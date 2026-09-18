@echo off
REM Stop Glass without leaving a console open.
cd /d "%~dp0"
if exist "%~dp0windows-helper\Stop-Glass-Silent.cmd" (
  call "%~dp0windows-helper\Stop-Glass-Silent.cmd"
  exit /b %ERRORLEVEL%
)
where uv >nul 2>&1
if errorlevel 1 (
  echo The uv tool was not found.
  exit /b 1
)
uv run python -m artemis stop --port 8000
