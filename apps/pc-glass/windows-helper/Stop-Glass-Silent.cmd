@echo off
REM Stop Glass with no lingering console (helper logic).
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command ^
  ". '%~dp0GlassControl.ps1'; $r = Stop-GlassHidden; if (-not $r.Ok) { exit 1 }"
