# Cyclone V5 + Glass 1.0

**Status:** generation plan (not current product)  
**Baseline to leave behind:** Mobile **4.8.0** (versionCode 140) · Cyclone One **1.5.5** · Gateway/MCP **4.1.0**  
**Target:** Mobile **5.0** · Cyclone Glass **1.0** · Gateway/MCP **5.0** as one contract  
**Written:** 2026-09-21

V5 is not a feature dump on 4.8. The phone grows an **atlas** and a **vault**. The PC becomes **Cyclone Glass** — a window onto that phone, not a second brain.

> One button to learn the house. A vault for the keys. A Minitap-class board on the PC so the operator can *see* the house. Eyes every time the agent walks through a door.

## Read in this order

| # | Doc | What it is |
|---|---|---|
| 0 | [Overview](00-overview.md) | Picture, Louella demo, what 4.8 already is |
| 1 | [Headset and laws](01-headset-and-laws.md) | Why this is a headset, not a harness |
| 2 | [Mobile 5.0](02-mobile-v5.md) | Workstreams M0–M6 |
| 3 | [Glass 1.0](03-glass-v1.md) | PC HUD: Ask, Maps, Vault, live |
| 4 | [App Maps canvas](04-app-maps-canvas.md) | **Operator board** — full mapping like MiniTest |
| 5 | [Secrets and vault](05-secrets-vault.md) | The card, Keystore, no values on the wire |
| 6 | [Atlas and mapper](06-atlas-and-mapper.md) | One-button crawl, dummy vs live, never-pay |
| 7 | [Ask compiler](07-ask-compiler.md) | Sentence stays law; atlas whispers |
| 8 | [Protocol and gateway](08-protocol-gateway.md) | `atlas.*` `mapping.*` `secrets.*` `ask.*` |
| 9 | [Cuts and milestones](09-cuts-and-milestones.md) | Alpha → RC → 5.0 / Glass 1.0 |
| 10 | [Reuse and refusals](10-reuse-and-refusals.md) | Steal 4.8. Do not rebuild. What we will not ship |

## Identity

| Surface | Today | V5 generation |
|---|---|---|
| Android | Cyclone Mobile 4.8.0 | Cyclone Mobile **5.0** |
| Windows | Cyclone One 1.5.5 | **Cyclone Glass 1.0** (same Tauri app, new product surface) |
| Pipe | Gateway / MCP 4.1.0 | Gateway / MCP **5.0** (lockstep with mobile, not later) |

Invariant that does not move: **the phone mutates. Glass shows, types, and asks. GATE still owns pay / send / delete.**

## Folder rule

This folder is the generation plan. It does not describe the shipping 4.8 product. Current-product docs stay in [`docs/`](../docs/). When a V5 cut ships, promote the matching slice into `docs/` and leave this folder as the source plan.
