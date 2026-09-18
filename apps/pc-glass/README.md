# Cyclone PC Glass (`apps/pc-glass`)

Artemis-based **web PC companion** for Cyclone. PC AI agents control the phone **through Cyclone** (Device Gateway + MCP `phone_*` + `session_id`), not through raw ADB as product authority.

| Pair with | Version / branch tip |
|---|---|
| Cyclone Mobile | tip of `release/cyclone-mobile-v4.6.7` (and newer mobile tip when cut) |
| Device Gateway | **4.1.0** (`apps/device-gateway`) |
| Agent MCP | **4.1.0** (`tools/cyclone-agent-mcp`, `cyclone-phone`) |
| Cyclone One (Tauri glass) | `apps/pc-companion` — live video / pairing UI |

This tree is adapted from [google/artemis](https://github.com/google/artemis) (Apache-2.0). See `LICENSE` and `NOTICE` (Google LLC; includes Minitap, Inc. source).

## Mode A contract (non-negotiable)

```text
Attach gateway -> session_id -> phone_observe / phone_locate -> decide -> phone_act only -> verify -> GATE on phone
```

- **PhoneToolExecutor** on the phone is the sole mutator.
- **Never invent `session_id`** — attach/obtain from the live gateway session.
- Three planes stay separate: observe / decide / act.
- When Cyclone-connected: observe/act **only** via gateway `phone_*`. Disable ADB as product authority while connected.

### `phone_act` allowlist

| Allowed | Forbidden |
|---|---|
| `click`, `long_press`, `scroll`, `type`, `back`, `home`, `open_app`, `wait_for` | `swipe`, `launch_intent` |

## Quick start (dev)

1. Run Cyclone Device Gateway **4.1.0** and pair Cyclone Mobile (QR / four-letter code via Cyclone One).
2. Confirm MCP `cyclone-phone` tools are healthy (`phone_status` / `phone_devices`).
3. From this package (after Python 3.12+ / uv setup inherited from Artemis):

```bash
cd apps/pc-glass
# Install deps per upstream Artemis flow (uv sync), then start the local UI/server
# Prefer Cyclone-connected mode once part 4 wiring is present.
```

Upstream Artemis quick-start and MCP IDE notes remain in the copied tree for reference; **product path for Cyclone is gateway/MCP**, not direct ADB.

## Layout

| Path | Role |
|---|---|
| `artemis/` | Core runtime (will gain Cyclone-connected backend in later parts) |
| `mcp_server/` | Upstream Artemis MCP — map/override toward Cyclone `phone_*` |
| `packages/` | `artemis-client`, helpers |
| `LICENSE` / `NOTICE` | Apache-2.0 attribution |

## Related docs in this monorepo

- `apps/device-gateway/MCP_CAPABILITY_MAPPING.md` — `phone_observe` / `phone_act` / GATE
- `apps/pc-companion/README.md` — Cyclone One glass + pairing
- `docs/00_VERSION_MATRIX.md` — gateway/MCP **4.1.0**

## Status

Scaffold on branch `feature/pc-glass-artemis` from `release/cyclone-mobile-v4.6.7`. Cyclone-connected driver: `artemis/drivers/cyclone/gateway_driver.py` (env `CYCLONE_CONNECTED=1` + `CYCLONE_SESSION_ID`).
MCP map: `CYCLONE_MCP_MAP.md`.

