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


## Run 2 mapping/session wire contract

Run-2 JSON uses the existing Gateway camel-case execution identity: `sessionId`, `displayId`,
optional `workspaceId` + `workspaceGeneration`, and (for named virtual-display acquisition)
`executionGeneration`. The conceptual `session_id` law above remains the same requirement: mapping
never invents a default session when identity is absent.

### `atlas.diff`

Request:

```json
{
  "placeId": "package:com.example.app",
  "persona": "mapping",
  "since": null
}
```

`since: null` is the bootstrap after a client has recovered full truth with `atlas.get`; Android
returns a phone-issued cursor and no fabricated history. Every non-null `since` must be a cursor
previously issued for the same Place/persona stream.

Response:

```json
{
  "placeId": "package:com.example.app",
  "persona": "mapping",
  "since": null,
  "cursor": "c1:<phone-epoch>:<monotonic-sequence>",
  "resyncRequired": false,
  "changes": []
}
```

Each change contains exactly `cursor`, `entity` (`place|screen|edge`), `change`
(`upsert|remove`) and structural `id`, plus only the relevant structural fields:
`mapStatus`, `layout {x,y}`, or `fromScreenId/toScreenId`. There are no labels, text, values,
raw observations, accessibility trees, screenshots, paths, selectors or dynamic content in a diff.
Unknown/expired/wrong-epoch cursors return `resyncRequired:true` and an empty `changes`; clients
recover with `atlas.get`.

### `mapping.start`

New job request:

```json
{
  "placeId": "package:com.example.app",
  "persona": "mapping",
  "sessionId": "default-foreground",
  "displayId": 0,
  "budget": {
    "maxNewScreens": 40,
    "maxElapsedMs": 600000,
    "maxConsecutiveNonProgress": 6,
    "maxAttemptsPerDoor": 3
  }
}
```

`budget` is optional; omission uses the bounded phone defaults above. A Layer-2 request additionally
carries `workspaceId` and `workspaceGeneration`. A named virtual-display request uses a nonzero
`displayId` and must carry the current `executionGeneration`.

Resume/unpause uses the same operation with no Place/persona/budget rewrite:

```json
{
  "resumeJobId": "map-...",
  "sessionId": "default-foreground",
  "displayId": 0
}
```

Resume re-acquires the existing phone authority and must remain on the original plane.

### `mapping.pause`, `mapping.stop`, `mapping.status`

Pause/stop request:

```json
{
  "mappingJobId": "map-...",
  "sessionId": "default-foreground",
  "displayId": 0
}
```

`mapping.status` has the same plane identity and optional `mappingJobId`. Without a job id it
returns the active job for that plane, or `state:"idle"`. Pause and stop still require the exact
bound plane but are allowed to stop mutation safely after control has already been lost.

Layer-2 status/pause/stop also carry the job's `workspaceId` + `workspaceGeneration`.
`executionGeneration` is acquisition/resume-only and is not accepted by pause/stop/status.

### Mapping status response

Every mapping command/status returns one exact status object. Non-idle jobs contain:

```text
mappingJobId
placeId
persona
state
sessionId
displayId
plane
controlRevision
executionGeneration
budget
currentAtlasNodeId
progress {
  newScreens
  verifiedMutations
  consecutiveNonProgress
  attemptedDoors
  remainingDarkRegions
}
atlasStatus
danger
boundary
startedAtEpochMs
updatedAtEpochMs
failureCode
```

`state` is exactly:

```text
idle | running | paused | needs-secret | human-control | completed | stopped | failed
```

`needs-secret` and `human-control` are nonterminal. A budget-limited successful crawl with dark
regions completes/stops with Atlas `partial`, never a fake `mapped` and never a fake failure.

### Authority/error behavior

- missing `sessionId` → `SESSION_REQUIRED`;
- session/display mismatch → `SESSION_DISPLAY_MISMATCH`;
- human/companion input ownership → `HUMAN_HAS_CONTROL`;
- changed foreground controller epoch, named-VD generation, or Layer-2 generation →
  `STALE_CONTROL_REVISION`;
- another nonterminal mapping job on the same plane → `MAPPING_PLANE_BUSY`;
- unknown job → `MAPPING_JOB_NOT_FOUND`;
- illegal state transition → `MAPPING_INVALID_STATE`.

Android owns these states and revisions. Device Gateway validates and forwards only. Remote readonly
MCP exposes no `mapping.start` tool.
