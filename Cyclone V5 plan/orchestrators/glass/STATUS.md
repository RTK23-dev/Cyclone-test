# Glass orchestrator — STATUS

**Wave:** Run 1 (alpha.1 HUD + alpha.2 Maps look-and-feel)  
**Integration branch:** `v5/integration` @ `c9f33385` (maps-canvas merged locally; #155 close pending)  
**Orch session:** 2026-09-22 — glue agents 004/005/006 launched  
**Code base:** One 1.5.5 live path + Glass HUD; identity bump is Agent 006

## Board

| ID | Agent | Branch | State | PR | Return |
|---|---|---|---|---|---|
| 001 | shell-ask-secret | `v5/glass/shell-ask-secret` | merged | [#153](https://github.com/premiumcentraal-boop/Cyclone/pull/153) | RETURN-001 |
| 002 | maps-canvas | `v5/glass/maps-canvas` | merged on integration | [#155](https://github.com/premiumcentraal-boop/Cyclone/pull/155) | RETURN-002 |
| 003 | atlas-client | `v5/glass/atlas-client` | merged | [#154](https://github.com/premiumcentraal-boop/Cyclone/pull/154) | RETURN-003 |
| 004 | glue-maps | `v5/glass/glue-maps` | issued | | |
| 005 | glue-copy-adapter | `v5/glass/glue-copy-adapter` | issued | | |
| 006 | glass-identity | `v5/glass/glass-identity` | issued | | |

## Launch

Worktrees: `/tmp/glass-004`, `/tmp/glass-005`, `/tmp/glass-006` from `v5/integration`.

004 owns `app.ts` Maps mount + ask-vault Maps assertions.  
005 owns `fleet.ts` needs-secret copy + maps↔atlas adapter. **No `app.ts`.**  
006 owns version/identity files only. **No `app.ts` / pages / fleet.ts.**

After 004+005+006 merge: orch runs `npm test`, GitHub pre-release `glass-1.0.0-alpha.1`. Do not start mapping cursor.

## Contract with Mobile

- [x] Schemas on integration (`protocol/cyclone-atlas-v1.schema.json`, `cyclone-secrets-v1.schema.json`) — #144
- [x] `needs-secret` consumer state — #144
- [ ] Follow Me house as real `atlas.get` — #146 still open; Glass 002 mock + 003 empty-valid
- [x] `session_id` rules preserved

## Next

Wait for 004/005/006 PRs + returns. Merge into `v5/integration`. Combined test. Cut Run 1 pre-release.
