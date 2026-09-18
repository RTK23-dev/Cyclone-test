# Cyclone PC Glass (`apps/pc-glass`)

Artemis-based **web PC companion** for Cyclone. PC AI agents control the phone **through Cyclone** (Device Gateway + MCP `phone_*` + `session_id`), not through raw ADB as product authority.

Adapted from [google/artemis](https://github.com/google/artemis) (Apache-2.0). See `LICENSE` and `NOTICE` (Google LLC; includes Minitap, Inc. source).

## Versions (do not mix these up)

| Component | Version | Source |
|---|---|---|
| Cyclone Mobile | **4.6.9** | `release/version.toml` `components.mobile` / branch `release/cyclone-mobile-v4.6.9` |
| Device Gateway | **4.1.0** | `components.device_gateway` — PC loopback HTTP (**not** the mobile app version) |
| Agent MCP (`cyclone-phone`) | **4.1.0** | `components.mcp` |
| Cyclone One (PC companion) | **1.5.5** | `components.pc_companion` |

`python_version = 4.1.0` in `version.toml` is the gateway/MCP Python component line, **not** mobile.

## Mode A contract (non-negotiable)

```text
Attach gateway -> session_id -> phone_observe / phone_locate -> decide -> phone_act only -> verify -> GATE on phone
```

- **PhoneToolExecutor** on the phone is the sole mutator.
- **Never invent `session_id`** — attach/obtain from the live gateway / `phone_status`.
- When Cyclone-connected: observe/act **only** via gateway `phone_*`. Disable ADB as product authority while connected.

### `phone_act` allowlist

| Allowed | Forbidden |
|---|---|
| `click`, `long_press`, `scroll`, `type`, `back`, `home`, `open_app`, `wait_for` | `swipe`, `launch_intent` |

## Quick start (Device Gateway 4.1.0 on this PC)

Worktree: `C:\Users\Agent\Cyclone-pc-glass` (branch `feature/pc-glass-artemis` from `release/cyclone-mobile-v4.6.9`).

### 1) Install + serve gateway (loopback :8765)

```powershell
cd C:\Users\Agent\Cyclone-pc-glass\apps\device-gateway
uv venv .venv --python 3.12
uv pip install -e ".[uiautomator2]" --python .venv\Scripts\python.exe

# Create bearer + locator (DPAPI) pointing at :8765 — once per machine
.\.venv\Scripts\python.exe -c "import secrets; from cyclone_device_gateway.tooling_seam import save_connection; t=secrets.token_hex(32); save_connection(t,'http://127.0.0.1:8765',port=8765); open('.runtime/serve.env','w',encoding='utf-8').write(chr(10).join(['CYCLONE_DEVICE_GATEWAY_TOKEN='+t,'CYCLONE_DEVICE_GATEWAY_URL=http://127.0.0.1:8765','CYCLONE_DEVICE_GATEWAY_PORT=8765','CYCLONE_DESKTOP_PAIRING_BOOTSTRAP=1','CYCLONE_ANDROID_BRIDGE_TOKEN='+secrets.token_hex(16),'CYCLONE_DEVICE_SERIAL=3B171FDJH0061G','']))"

Get-Content .\.runtime\serve.env | ForEach-Object { if ($_ -match '=') { $k,$v=$_.Split('=',2); Set-Item Env:$k $v } }
.\.venv\Scripts\python.exe -m cyclone_device_gateway.cli serve
```

Verify: `GET http://127.0.0.1:8765/v1/device/status` returns HTTP 200 with Bearer from DPAPI / `serve.env`.

Alternate: start **Cyclone One** and always read `%LOCALAPPDATA%\Cyclone One\runtime\gateway-locator.json` (port may differ from 8765).

### 2) Pair phone (Cyclone Mobile **4.6.9**)

USB ADB `device` is not enough. Fleet must show paired / AI access allowed; bridge must not be `AUTH_REJECTED` / doctor `TOKEN MISMATCH`.

1. Unlock Pixel, open Cyclone Mobile **4.6.9**.
2. Cyclone One: **Settings → PC Gateway & QR pairing → Scan PC QR** (or four-letter code).
3. Confirm `GET /v1/devices` shows `paired: true`.
4. Read `session_id` from `phone_status` — **never invent**. Use `default-foreground` + `display_id=0` only when advertised for the live human display.

### 3) Cyclone-connected PC Glass

```powershell
$env:CYCLONE_CONNECTED = "1"
$env:CYCLONE_DEVICE_GATEWAY_URL = "http://127.0.0.1:8765"
$env:CYCLONE_SESSION_ID = "<from phone_status — never invent>"
cd C:\Users\Agent\Cyclone-pc-glass\apps\pc-glass
# Offline: python scripts/smoke_cyclone_mode_a.py
# Live after pair: set CYCLONE_SESSION_ID_LIVE + token, re-run smoke
```

Driver: `artemis/drivers/cyclone/gateway_driver.py` (selected when `CYCLONE_CONNECTED=1`). MCP map: `CYCLONE_MCP_MAP.md`.

## Layout

| Path | Role |
|---|---|
| `artemis/` | Core runtime + Cyclone gateway driver |
| `mcp_server/` | Upstream Artemis MCP (product path is Cyclone `phone_*`) |
| `LICENSE` / `NOTICE` | Apache-2.0 attribution |

## Related docs

- `apps/device-gateway/MCP_CAPABILITY_MAPPING.md`
- `apps/pc-companion/README.md`
- `release/version.toml` / `docs/00_VERSION_MATRIX.md`