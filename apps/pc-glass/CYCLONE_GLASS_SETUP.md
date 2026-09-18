# Cyclone Glass — easy setup

**Cyclone Glass** is the PC web companion: control the phone through Cyclone (gateway `phone_*`), not raw ADB.

## First install

1. Clone or open the Cyclone worktree (this repo) and open `apps/pc-glass`.
2. Copy `.env.example` → `.env` and set `OPEN_ROUTER_API_KEY` (or use the dashboard).
3. Install [uv](https://docs.astral.sh/uv/) and ensure it is on `PATH`.
4. Double-click `windows-helper/Launch Cyclone Glass Helper.vbs`  
   or `Start Cyclone Glass.cmd`.
5. Click **Start**, then **Open UI** → http://127.0.0.1:8000

Optional: run `windows-helper/Install-DesktopShortcut.ps1` for a Desktop **Cyclone Glass** icon.

## Rapid updates (leading workflow)

In the helper, click **Update** (leave “Rebuild UI on update” checked).

That always:

- Stops Glass  
- Pulls the GitHub **update channel** tip (`windows-helper/update-channel.json`)  
- **Hard-replaces** tracked app files (old code gone, new code in)  
- **Keeps** `.env`, `traces/`, local DBs  
- Rebuilds the web UI  
- Starts Glass again  

No command-prompt windows. Logs: `%LOCALAPPDATA%\CycloneGlass\`.

Change channel branch in `windows-helper/update-channel.json` when the default branch moves (e.g. after merge to `main`).

## Mode A defaults

- `CYCLONE_CONNECTED=1`  
- `CYCLONE_DEVICE_GATEWAY_URL=http://127.0.0.1:8765`  
- `CYCLONE_SESSION_ID=default-foreground`  

Device Gateway + Cyclone Mobile (~4.6.9) must be paired for phone control. Glass branding / Auto / helpers do not require a new mobile release by themselves.
