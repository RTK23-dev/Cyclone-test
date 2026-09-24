# 09 — Cuts and milestones

Do not try to ship maps + Glass board + Louella in one tag.

Gateway/MCP **5.0** ships with **alpha.1**, not later.

Physical Pixel 8 stays **UNVERIFIED** until a named pass. CI is not that pass.

## Cuts

| Tag | Mobile | Glass | Operator can demo |
|---|---|---|---|
| **5.0.0-alpha.1 / Glass 1.0-alpha.1** | Vault + secrets card on Ask. GATE `NEED_SECRET`. Protocol stubs. | Ask page + “waiting for secret.” Pipe 5.0. | Facebook login wall does not *Couldn’t finish* |
| **alpha.2** | Atlas from Follow Me + Settings mini-canvas | **Full read-only Maps board** (G2.1–G2.6). App switcher, zoom, inspector | Walk Gmail once, **see the house on PC**. Exit: explain rooms from the canvas alone |
| **alpha.3** | One-button mapper, never-pay, dummy/live split | Start mapping from the board; live cursor; diffs spawn cards | Settings or Glass → Gmail → Start, watch from the desk |
| **5.0.0-rc** | Chrome places, Ask compiler, People memory | Ask sketches, Louella path, persona split, pin/remap | The sentence: live email → Chrome Facebook → DM |
| **5.0.0 / Glass 1.0** | Freshness + Pixel pass | Encrypted fill (or explicitly ship phone-only card). Stale/refresh in catalog | Secrets fill, one Gmail map, one Chrome-host map, one Ask that uses a live slot, overlay yield, GATE pay-block |

## Glass web cuts (owner charter, 2026-09-23)

Glass restarts as `apps/glass`, a local web app. The V5 pages prototyped inside Cyclone One are ported, not extended.

| Cut | Glass (web) | Phone / gateway work it needs | Developer can |
|---|---|---|---|
| **glass 1.0.0-alpha.1** ✅ built 2026-09-23 | Web shell served by the local gateway, one launcher, one design system. **Apps** page. **Map** board ported. **Phone** live + take control + Ask | `apps.list`; serve static bundle; existing `atlas.*`, `mapping.*`, `ask.*` | Open Glass in a browser, see every app, pan a map, drive the phone |
| **alpha.2** ✅ built 2026-09-23 | **Runs** list + **Run inspector** v1 from today's trace (steps, tools, verification, failure) with cause of death | `runs.list/get` over `AgentTraceStore`; phone-side cause-of-death classifier | Open a failed run and see which step killed it |
| **alpha.3** ✅ built 2026-09-24 (owner request) | **Devices**: connect a phone with a six-digit code + Allow on the phone ("Connect this PC?" notification), list, reconnect, disconnect; Glass opens on Devices when nothing is connected; honest not-ready reasons | trust match code (PC + phone), per-phone trust facts on `/v1/fleet`, `TRUST_REJECTED`, version kept across trust restore | Connect or drop a phone from the browser in seconds, like WhatsApp Web |
| **alpha.4** ✅ built 2026-09-24 | Inspector v2: rooms per step, map vs model steps, route on the map, `stale-door` (redacted frames and expected room still to come) | Run record v2 (room, room after, app + version, decision source) | See where a route broke on the map and jump to fix it |
| **alpha.5** ✅ built 2026-09-24 | **Scenarios** tab + **Versions** tab (cards and table; the Minitap-style left-to-right scenario board is still to come) | `scenarios.list` (routes from the entry room, health from runs) + `atlas.versions`; doors stamped with the app version | See an app's scenarios and their health; see needs-remap after an app update |
| **Glass 1.0** | Knowledge page, Vault slots, polish, parity; Cyclone One loses the prototype pages | People memory, freshness | Glass exit criteria in [03](03-glass-v1.md) |

## Glass Maps is not optional

alpha.2 is the **look-and-feel** milestone. It ships **before** autonomous crawl. The operator must feel Mini’s demo on *our* atlas first. If the board is late, Glass is late.

## Pixel 8 5.0 gate (honest)

Do not publish 5.0 until Pixel 8 has:

1. Secrets fill (phone card at minimum)
2. One mapping of Gmail
3. One Chrome-host map
4. One Ask that uses a slot **live** (not dummy identity)
5. Overlay yield during mapping taps
6. GATE pay-block during mapping
7. Glass Maps: pan the Gmail house, inspector opens, live cursor on a remap

## Suggested merge order

1. Protocol schemas + GATE `NEED_SECRET` (mobile + gateway together)
2. Vault + overlay card
3. AtlasStore promoted from Graph v2 + Follow Me write path
4. Glass Maps read-only board
5. Mapper session + Glass start/watch
6. Ask compiler + People memory
7. Freshness
8. Encrypted Glass fill (or document phone-only)

Keep parallel agents on non-overlapping paths (`apps/mobile/**` vs `apps/glass/**` vs `apps/device-gateway/**`) as in `AGENTS.md`. `apps/pc-companion/**` is Cyclone One; V5 Glass work no longer lands there.
