# Glass orchestrator — STATUS

**Wave:** Run 1 (alpha.1 HUD + alpha.2 Maps look-and-feel)  
**Integration branch:** `v5/integration` @ `f47afadea137b5083726090fb36c985537f0e778`  
**Orch session:** 2026-09-22 — coding launched (3 subagents)  
**Code base:** One 1.5.5 on Mobile 4.8.0

## Board

| ID | Agent | Branch | State | PR | Return |
|---|---|---|---|---|---|
| 001 | shell-ask-secret | `v5/glass/shell-ask-secret` | issued / in-progress | | |
| 002 | maps-canvas | `v5/glass/maps-canvas` | issued / in-progress | | |
| 003 | atlas-client | `v5/glass/atlas-client` | issued / in-progress | | |

## Launch

Worktrees: `/tmp/glass-001`, `/tmp/glass-002`, `/tmp/glass-003` from `v5/integration`.

001 owns `app.ts` + `fleet.ts` + Ask/Vault. Maps route is a placeholder only.  
002 owns the Minitap canvas; **no `app.ts`**.  
003 owns `atlasClient`; schemas already on integration (#144). **No `app.ts` / canvas.**

Glue after merge: orch mounts `createMapsPage` and optionally `atlasClient` into Ask/Vault/Maps.

## Contract with Mobile

- [x] Schemas on integration (`protocol/cyclone-atlas-v1.schema.json`, `cyclone-secrets-v1.schema.json`) — #144
- [x] `needs-secret` consumer state — #144
- [ ] Follow Me house as real `atlas.get` — #146 still open; Glass 002 mock + 003 empty-valid
- [x] `session_id` rules preserved

## Next

Wait for three PRs + returns. Merge into `v5/integration`. Orch glue commit. Then Run 1 release (Glass 1.0.0-alpha.1 identity). Do not start mapping cursor.
