# Glass orchestrator — STATUS

**Wave:** 1 (alpha.1 HUD + alpha.2 Maps look-and-feel)  
**Integration branch:** `v5/integration` @ `0e14ab9892f429d4006591af7b4804a1b8009f92`  
**Sync:** issue #137 resolved — integration now exists and carries the hardened V5 plan  
**Plan:** generation plan `9f1d440e`; orchestrator pack on `main` `d2ec2ca5`  
**Code base:** `release/cyclone-mobile-v4.8.0` @ `97f81cb692893896b500f2372068fb1cd67d85ed` (One 1.5.5)

## Board

| ID | Agent | Branch | State | PR | Return |
|---|---|---|---|---|---|
| 001 | shell-ask-secret | `v5/glass/shell-ask-secret` | issued | | |
| 002 | maps-canvas | `v5/glass/maps-canvas` | issued | | |
| 003 | atlas-client | `v5/glass/atlas-client` | issued / dependency-gated | | |

States: `drafted` → `issued` → `in-pr` → `returned` → `merged` | `blocked`

## Handoff issuance

All three wave-1 handoffs are issued under `agents/` against `v5/integration`.

- **001** owns shell/nav, Ask wait-state, Vault slot stub, and the minimum route seam in `src/core/fleet.ts` + route tests. It must not invent atlas/secrets transport.
- **002** is intentionally standalone/mock-backed and may proceed in parallel without touching `app.ts`. Its output is the full Minitap-class board and a single swappable `AtlasViewModel`.
- **003** is issued but execution remains dependency-gated: consume Mobile 001 schemas from integration and rebase onto 001/002 before transport wiring. It must never fork CONTRACT names.

No Glass product branch existed at this status refresh; implementation agents should create their own branches from the integration SHA above when they begin.

## Contract with Mobile

- [ ] `needs-secret` on the Ask presentation snapshot
- [ ] `protocol/cyclone-atlas-v1.schema.json` + `cyclone-secrets-v1.schema.json` on integration
- [ ] `atlas.get(placeId, persona)` from Follow Me / AtlasStore for a real house
- [ ] shared `session_id` rules preserved

## Deep-dive findings

- Glass is an additive HUD over One 1.5.5: keep JPEG live, handoff, session tiles, MCP/ChatGPT Attach, camera, pairing, and doctor.
- Phone remains the only mutation authority. No PC `PhoneToolExecutor`; Maps is a replica + command surface.
- Maps is a **primary nav** and alpha.2 exit criterion: dotted infinite board, screen cards, door edges, fit/pan/zoom, inspector, Live/Dummy split, coverage, honest empty/stale states.
- The renderer boundary matters: 002 must export one `AtlasViewModel`; 003 swaps mock → `atlas.get` without rewriting canvas internals.
- alpha.1 ships Ask + phone-only secret waiting first; encrypted Glass fill stays later unless proven leak-free.
- Mobile < 5.0 must fail closed with “update the phone” for Ask/Maps/Vault atlas features.
- `session_id` is not optional metadata; it is part of authority and routing, especially for named workspaces.
- Remote MCP remains readonly; no silent `mapping.start`.
- The current integration branch already contains the V5 hardening commit that tightens durable atlas persistence and secret boundaries. Glass must consume those rules rather than carrying older draft assumptions.

## Next orchestration checkpoints

1. 001 can start immediately from `v5/integration`.
2. 002 can start immediately in parallel against its mock graph and must not edit shell navigation.
3. 003 waits for the route surface plus Mobile schemas/ops, then rebases and wires the pipe.
4. After each return: update this board, review PR scope/contract, and write the next numbered handoff. Do not start alpha.3 mapping cursor/start work early.

## Notes

- Sync issue #137 is obsolete because Mobile created `v5/integration`; close it rather than creating any second integration branch.
- Mobile STATUS on `main` is stale, but live branches show all three Mobile wave-1 branches now exist. Glass should trust branch/PR evidence over that stale board and keep CONTRACT synchronization explicit.
- No product code was written by the Glass orchestrator.
