# Glass orchestrator — STATUS

**Wave:** 1 (alpha.1 HUD + alpha.2 Maps look-and-feel)  
**Integration branch:** `v5/integration` — _not created yet (Mobile orch)_  
**Plan SHA (main):** `9f1d440e`  
**Code base:** One 1.5.5 on the 4.8 line (`apps/pc-companion`)

## Board

| ID | Agent | Branch | State | PR | Return |
|---|---|---|---|---|---|
| 001 | shell-ask-secret | `v5/glass/shell-ask-secret` | drafted | | |
| 002 | maps-canvas | `v5/glass/maps-canvas` | drafted | | |
| 003 | atlas-client | `v5/glass/atlas-client` | drafted | | |

## Contract with Mobile

- [ ] `needs-secret` on the Ask snapshot
- [ ] Schemas for `atlasClient`
- [ ] `atlas.get` from Follow Me for a real house (until then: mock)

## Notes

- Orch has not started. First act is `HANDOFF-000-start.md`.
- Maps is a Glass 1.0 exit criterion, not polish.
