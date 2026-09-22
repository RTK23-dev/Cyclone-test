# Glass orchestrator — STATUS

**Wave:** Run 3 **ISSUED** (alpha.2 operator table)  
**Integration branch:** `v5/integration` @ `9c72e393aa6f3a89dfe9c74928dc1c53a8e337ee`  
**Last release:** `glass-1.0.0-alpha.1` (companion `1.6.0-alpha.1`)  
**Orch session:** 2026-09-22 — Run 3 coding launched (3 subagents)  
**Code base:** One 1.5.5 live path on Mobile 4.8.0; Glass HUD from Run 1 + honest pipe from Run 2

## Run 1 (complete)

| ID | Agent | PR | State |
|---|---|---|---|
| 001 | shell-ask-secret | [#153](https://github.com/premiumcentraal-boop/Cyclone/pull/153) | merged |
| 002 | maps-canvas | [#155](https://github.com/premiumcentraal-boop/Cyclone/pull/155) | merged |
| 003 | atlas-client | [#154](https://github.com/premiumcentraal-boop/Cyclone/pull/154) | merged |
| 004 | glue-maps | [#156](https://github.com/premiumcentraal-boop/Cyclone/pull/156) | merged |
| 005 | glue-copy-adapter | [#158](https://github.com/premiumcentraal-boop/Cyclone/pull/158) | merged |
| 006 | glass-identity | [#157](https://github.com/premiumcentraal-boop/Cyclone/pull/157) | merged |

## Run 2 (complete) — honest live replica

Combined `apps/pc-companion` tests after merge: **190 pass / 0 fail**.

| ID | Agent | PR | State |
|---|---|---|---|
| 007 | maps-honest-source | [#161](https://github.com/premiumcentraal-boop/Cyclone/pull/161) | merged `8e2f26bc` |
| 008 | vault-live-slots | [#159](https://github.com/premiumcentraal-boop/Cyclone/pull/159) | merged `cf05351b` |
| 009 | glass-runtime-wire | [#160](https://github.com/premiumcentraal-boop/Cyclone/pull/160) | merged `9c72e393` |

Leftover for Run 3: `app.ts` still type-asserts Maps/Vault options; Maps `sessionId` unused in UI; Ask samples always shown; navigate-to-Maps clears `focusedDeviceId`; live `atlas.get` house still blocked on Mobile [#146](https://github.com/premiumcentraal-boop/Cyclone/pull/146).

## Run 3 board

| ID | Agent | Branch | State | PR | Return |
|---|---|---|---|---|---|
| 010 | maps-operator-board | `v5/glass/maps-operator-board` | issued / in-progress | | |
| 011 | ask-hud-honesty | `v5/glass/ask-hud-honesty` | issued / in-progress | | |
| 012 | glass-session-bind | `v5/glass/glass-session-bind` | issued / in-progress | | |

Worktrees: `/tmp/glass-010`, `/tmp/glass-011`, `/tmp/glass-012` from current `v5/integration`.

010 owns Maps inspector / Take control / plane label / dark-doors.  
011 owns Ask HUD honesty + plane + redacted log download.  
012 owns `app.ts` mount + `focusedSessionId` (keeps focused phone on Ask/Maps/Vault).

Frozen option names in `agents/run-003/RUN-003-SHARED.md`.

Merge order: **010 + 011 first**, then **012**.

## Not this cut

`mapping.start` / cursor / `atlas.diff`. Encrypted PC fill. `ask.start` / Send. Gateway/MCP 5.0 bump. `apps/mobile/**`. ChatGPT Attach. Camera. Glass identity bump / GitHub release.
