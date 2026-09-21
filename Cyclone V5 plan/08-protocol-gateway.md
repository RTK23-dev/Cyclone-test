# 08 — Protocol and gateway 5.0

Phone is source of truth. Glass is a replica + command surface. MCP is a constrained pipe.

Gateway/MCP **must ship 5.0 with mobile 5.0-alpha.1**, not “later.” 4.8 already drifted (mobile 4.8.0, pipe 4.1.0). Do not repeat that.

## New ops (names indicative)

All mutating/observe-of-run ops require `session_id`. Named workspace still requires `display_id > 0`. Companion owning input still returns `HUMAN_HAS_CONTROL`.

```text
atlas.places
atlas.get(placeId, persona)
atlas.diff(placeId, since)

mapping.start | pause | stop | status

ask.start | status                 # goal text only

secrets.slots                      # booleans
secrets.request                    # slot name; phone/Glass shows card

people.search                      # local people memory, not a Facebook scrape
```

**No secret values in args, results, traces, websocket payloads, or access logs.**

Schemas land in:

```text
protocol/cyclone-atlas-v1.schema.json
protocol/cyclone-secrets-v1.schema.json
```

Extend `protocol/cyclone-live-v1.schema.json` only if live JPEG needs a mapping-cursor overlay; prefer a sidecar event `mapping.cursor` on the fleet websocket.

## MCP

- Cursor / Grok local MCP: add read-only `atlas_query` so agents can *see* the map.
- `mapping.start` is Glass/UI or an explicit operator tool, not a silent side effect of chat.
- Remote MCP (`GATEWAY_MODE`) stays **readonly** by default. Mapping is a local operator act.
- `phone_act` still requires `session_id`. Glass Ask does not bypass GATE.

## Fleet events

Add (names indicative):

```text
atlas.updated
mapping.started | mapping.progress | mapping.stopped
ask.needs_secret
place.stale
```

Glass Maps subscribes to `atlas.updated` / `mapping.progress` to spawn cards without polling the full graph.

## Version matrix

When alpha.1 cuts, `release/version.toml` must agree:

| Key | Value |
|---|---|
| `components.mobile` | `5.0.0-alpha.1` (or the cut’s id) |
| `components.pc_companion` | `1.0.0-alpha.1` as **Glass** (or `2.0.0-alpha.1` if One 1.x numbering must continue — pick one in M0 and do not flip) |
| `components.device_gateway` | `5.0.0-alpha.1` |
| `components.mcp` | `5.0.0-alpha.1` |

**Numbering recommendation:** One becomes Glass **1.0**. Do not ship “One 1.6 with Maps.” Doctor, installer display name, and this folder all say Glass.

If installer-path compatibility forces keeping `Cyclone One` under `%LOCALAPPDATA%`, that is a path, not the product name.
