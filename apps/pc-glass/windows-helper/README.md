# Cyclone PC Glass — Windows helper

Small Start / Stop / Open UI app for the PC Glass web platform. **Does not flash command-prompt windows.**

## Use

1. Keep this folder next to the rest of `apps/pc-glass` (it expects `../` to be the Glass root with `uv` + `.venv`).
2. Double-click **`Launch Cyclone PC Glass Helper.vbs`** (no console).
3. Or run `Install-DesktopShortcut.ps1` once to put a Desktop shortcut.

Buttons:

- **Start Glass** — sets Mode A env (`CYCLONE_CONNECTED=1`, gateway `:8765`, `CYCLONE_SESSION_ID=default-foreground`) and runs `uv run python -m artemis ui --port 8000 --no-open` with `CreateNoWindow`.
- **Stop Glass** — `uv run python -m artemis stop --port 8000` (hidden).
- **Open UI** — opens http://127.0.0.1:8000

Logs: `%LOCALAPPDATA%\CyclonePcGlass\logs\`

## vs `.cmd` launchers

`Start Cyclone PC Glass.cmd` / `start.bat` still exist for debugging, but they open consoles and may prompt for MCP install. Prefer this helper for daily use.

## Optional `.exe`

On a Windows machine with .NET SDK:

```powershell
.\build-exe.ps1
```

Produces `dist/CyclonePcGlassHelper.exe` (self-contained WinForms wrapper).
