# Glass orchestrator — STATUS

**Wave:** 1 (alpha.1 HUD + alpha.2 Maps look-and-feel)  
**Integration branch:** intended `v5/integration` — **BLOCKED: Mobile has not created it yet**  
**Sync:** issue #137 `v5-orch-sync`  
**Plan:** generation plan `9f1d440e`; orchestrator pack on `main` `d2ec2ca5`  
**Code base:** `release/cyclone-mobile-v4.8.0` @ `97f81cb692893896b500f2372068fb1cd67d85ed` (One 1.5.5)

## Board

| ID | Agent | Branch | State | PR | Return |
|---|---|---|---|---|---|
| 001 | shell-ask-secret | `v5/glass/shell-ask-secret` | blocked | | |
| 002 | maps-canvas | `v5/glass/maps-canvas` | blocked | | |
| 003 | atlas-client | `v5/glass/atlas-client` | blocked | | |

States: `drafted` → `issued` → `in-pr` → `returned` → `merged` | `blocked`

## Handoff issuance

All three wave-1 handoffs are issued under `agents/` against the intended base `v5/integration`.

- **001** is ready once the integration branch exists. Scope amended after baseline review: `AppRoute` lives in `src/core/fleet.ts` and route tests live in `tests/fleet.test.mjs`; 001 may touch only those routing/test seams in addition to its original files.
- **002** is ready to build the standalone mock-backed board without touching `app.ts`; it should rebase/mount after 001.
- **003** is deliberately execution-blocked until 001 navigation exists **and** Mobile 001 schemas are on integration. It must consume, never fork, the contract names.

No Glass agent branch is created from a stale base while `v5/integration` is absent.

## Contract with Mobile

- [ ] `needs-secret` on the Ask presentation snapshot
- [ ] `protocol/cyclone-atlas-v1.schema.json` + `cyclone-secrets-v1.schema.json` on integration
- [ ] `atlas.get(placeId, persona)` from Follow Me / AtlasStore for a real house
- [ ] shared `session_id` rules preserved

## Deep-dive findings

- Glass is an additive HUD over One 1.5.5: keep JPEG live, handoff, session tiles, MCP/ChatGPT Attach, camera, pairing, doctor.
- Phone remains the only mutation authority. No PC `PhoneToolExecutor`; Maps is a replica + command surface.
- Maps is a **primary nav** and alpha.2 exit criterion: dotted infinite board, screen cards, door edges, fit/pan/zoom, inspector, Live/Dummy split, coverage, honest empty/stale states.
- alpha.1 ships Ask + phone-only secret waiting first; encrypted Glass fill is later unless proven leak-free.
- 4.8 phones must fail closed with “update the phone” for Ask/Maps/Vault atlas features.
- Remote MCP remains readonly; no silent `mapping.start`.

## Notes

- Mobile STATUS still says its orchestrator has not started and owns integration creation.
- Sync issue #137 was opened instead of inventing a second integration branch.
- No product code was written by the orchestrator.
