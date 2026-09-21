# 10 — Reuse and refusals

## Reuse (do not rebuild)

| Piece | Path |
|---|---|
| Hands | `PhoneToolExecutor`, Human Gesture, overlay host-yield |
| Physics | GATE, Fast Path settle/fingerprint, Session Kernel, named VD cubic gesture |
| Graph seed | `AppGraphEngine`, `brain/graphv2/*`, Follow Me learner |
| Retriever | `AppGraphRetriever.findBestPath` — sketch only |
| Secret strip | `automation/skill/SkillSecrets.kt` |
| Ask HUD | Overlay / Ask panel presentation snapshot |
| Live view | One JPEG path, `HUMAN_HAS_CONTROL`, `PHONE_LOCKED` |
| Pairing / doctor | Existing QR, four-letter, loopback gateway |
| Fleet / camera / ChatGPT Attach | Stay. Glass adds pages; it does not rewrite `livePhoneController` |
| Settings | `CycloneSettings426.kt` — add App Maps row, don’t replace the page |

## What we refuse

- Rapid-fire saved tap lists as the executor
- Dummy email as “current logged-in Gmail”
- Passwords in Graph nodes, MCP, Glass disk, Download logs
- Mapping the feed (400 emails, infinite Reels)
- A PC-side PhoneToolExecutor
- `LOGIN` / `SESSION_STATUS` as the default title for every account sentence
- Glass that works on 4.8 phones “a bit.” Break the contract loudly: **update the phone**
- Unattended whole-phone crawl. One button, one place, a budget
- Maps buried in Settings on Glass. Maps is a primary nav
- Mini acceptance-criteria as the graph nodes
- “Passed 8/8” as mapping success
- Merged fleet atlas in V1
- Remote-MCP `mapping.start` while `GATEWAY_MODE=readonly` is the default — keep it local
- Shipping 5.0 on CI green without Pixel evidence

## Code owners (indicative)

| Workstream | Owner tree |
|---|---|
| M1 vault / card | `apps/mobile/**/overlay/**`, new `secrets/` |
| M2 atlas | `apps/mobile/**/applearner/**`, `brain/graphv2/**` |
| M3 mapper | new `apps/mobile/**/mapping/` |
| M4 ask | destination authority / presentation / agent loop |
| G2 Maps board | `apps/pc-companion/src/pages/mapsPage.ts`, `ui/appMapCanvas.ts` |
| Pipe | `apps/device-gateway/**`, `protocol/**`, `tools/*mcp/**` |

## Definition of done (generation)

A V5 cut is done when: the slice is implemented, tests/guards pass, secrets never persist in traces, version.toml + gradle + Glass/One identity agree, and physical verification is stated honestly.
