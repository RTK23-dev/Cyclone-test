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

Keep parallel agents on non-overlapping paths (`apps/mobile/**` vs `apps/pc-companion/**` vs `apps/device-gateway/**`) as in `AGENTS.md`.
