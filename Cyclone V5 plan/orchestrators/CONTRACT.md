# Connection contract (Mobile ↔ Glass)

Phone is source of truth. Glass is replica + command surface. MCP is a constrained pipe.

If this file and the code disagree, **stop and fix this file in the same PR as the code.** Do not let the two orchestrators invent parallel vocabularies.

Standing spec: [`../08-protocol-gateway.md`](../08-protocol-gateway.md)

## Names (frozen)

| Name | Meaning | Owner of truth |
|---|---|---|
| Place | `package` **or** `chrome\|origin` | Mobile atlas |
| Persona | `live` \| `mapping` | Mobile |
| Atlas map status | `unmapped` \| `partial` \| `mapped` \| `stale` \| `blocked` | Mobile atlas |
| Screen / Edge / Capability / FactSlot | Atlas objects | Mobile |
| Run state `needs-secret` | Not a failure | Mobile GATE |
| `session_id` | Required on observe/act/ask/mapping | Existing V4 kernel |
| Vault slot | Boolean presence on the wire; **never the value** | Mobile Keystore |

## Ops (alpha.1 / alpha.2)

```text
atlas.places
atlas.get(placeId, persona)
atlas.diff(placeId, since)          # alpha.2+

mapping.start | pause | stop | status   # start is alpha.3; status stub ok in alpha.2

ask.start | status                  # goal text only

secrets.slots                       # booleans only
secrets.request                     # slot name; UI shows the card
```

Schemas: `protocol/cyclone-atlas-v1.schema.json`, `protocol/cyclone-secrets-v1.schema.json`  
Mobile agent 001 **authors** them. Glass agent 003 **consumes** them. Changing a name requires a CONTRACT note in the same PR.

`partial` means the phone has useful structural Atlas knowledge but coverage is knowingly incomplete. It must never be serialized as `mapped` merely because at least one screen exists.

## Forbidden on the wire

Password, OTP, cookie, token, raw typed secret, unredacted vault field in JSON, logs, fleet websocket, Uvicorn access log, Glass `localStorage`, MCP traces.

## Fail closed (both sides)

| Condition | Result |
|---|---|
| Glass talks to mobile < 5.0 | Honest “update the phone.” No fake atlas |
| Missing `session_id` on act/ask/mapping | `SESSION_REQUIRED` |
| Named workspace `display_id` 0 | `SESSION_DISPLAY_MISMATCH` |
| Companion owns input, agent mutates | `HUMAN_HAS_CONTROL` |
| Secret value in a payload | Reject. Do not strip-and-forward |
| Remote MCP `GATEWAY_MODE=readonly` | No `mapping.start` |

## Version lockstep

`release/version.toml` mobile, pc_companion (Glass), device_gateway, mcp move together for V5 alphas. Pipe stays **5.0.0-alpha.N** with mobile. Do not leave gateway at 4.1.0.

## Who implements what

| Surface | Mobile orch | Glass orch |
|---|---|---|
| `protocol/*.schema.json` | Writes | Reviews, generates/types client |
| `apps/mobile/**/gateway` phone op registration / dispatch / data adapters | Writes; phone remains source of truth | — |
| `apps/device-gateway` forwarding / validation handlers | Writes | Reviews contract tests |
| `apps/mobile` vault, GATE, atlas, mapper, overlay | Writes | — |
| `apps/pc-companion` Ask, Maps, Vault UI, `atlasClient` | — | Writes |
| Overlay JPEG / live / handoff | Keep 4.8 behavior | Keep One 1.5.5 live path |

## Handshake for this wave

Coordination note: Mobile 001 publishes the `needs-secret` presentation state and schemas. Glass may mock atlas data for canvas work, but must replace the mock with Mobile 003's phone-owned `atlas.get` before the read-only alpha.2 exit test.

- [ ] Schemas exist on `v5/integration`
- [ ] GATE `NEED_SECRET` + overlay card on phone
- [ ] Glass Ask page shows `needs-secret` / waiting-for-card
- [ ] `atlas.get` returns a graph Glass can render (even from Follow Me only)
- [ ] Maps board renders that graph read-only
- [ ] No secret values in any fixture or log from the PRs
