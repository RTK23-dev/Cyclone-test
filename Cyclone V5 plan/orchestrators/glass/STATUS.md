# Glass orchestrator — STATUS

**Wave:** Run 3 **COMPLETE** (alpha.2 operator table)  
**Integration branch:** `v5/integration` @ `5ec5957b` (plus orch glue on top)  
**Last release:** `glass-1.0.0-alpha.1` (companion `1.6.0-alpha.1`)  
**Orch session:** 2026-09-22 — Run 3 merged; combined companion tests **218 pass / 0 fail**  
**Code base:** One 1.5.5 live path on Mobile 4.8.0; Glass HUD Run 1 + honest pipe Run 2 + operator table Run 3

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

| ID | Agent | PR | State |
|---|---|---|---|
| 007 | maps-honest-source | [#161](https://github.com/premiumcentraal-boop/Cyclone/pull/161) | merged |
| 008 | vault-live-slots | [#159](https://github.com/premiumcentraal-boop/Cyclone/pull/159) | merged |
| 009 | glass-runtime-wire | [#160](https://github.com/premiumcentraal-boop/Cyclone/pull/160) | merged |

## Run 3 (complete) — operator table

Combined `apps/pc-companion` tests after merge + type-assert cleanup: **218 pass / 0 fail**. `tsc --noEmit` clean.

| ID | Agent | PR | State |
|---|---|---|---|
| 010 | maps-operator-board | [#164](https://github.com/premiumcentraal-boop/Cyclone/pull/164) | merged `1eb5a4d8` |
| 011 | ask-hud-honesty | [#163](https://github.com/premiumcentraal-boop/Cyclone/pull/163) | merged `ac63e757` |
| 012 | glass-session-bind | [#162](https://github.com/premiumcentraal-boop/Cyclone/pull/162) | merged `5ec5957b` |

010: session plane on Maps, Take control → Phone, edge inspector (English doors), Dark doors filter, capability glyphs. Start mapping still disabled.  
011: Ask samples only when preview/omitted version; 5.x without preview is empty honest HUD; redacted HUD log download; Send off.  
012: `focusedSessionId`; Ask/Maps/Vault keep the focused phone; named VD from Phone tiles; mock-only `previewSnapshots`.

## Still blocked / not this cut

Live Gmail house on the board still needs Mobile [#146](https://github.com/premiumcentraal-boop/Cyclone/pull/146) (`atlas.get` Follow Me).  
`mapping.start` / cursor / `atlas.diff` (alpha.3). Encrypted PC fill. `ask.start` / Send. Gateway/MCP 5.0 bump. Glass identity / GitHub prerelease (still `1.6.0-alpha.1` / `glass-1.0.0-alpha.1`). Pixel UNVERIFIED.
