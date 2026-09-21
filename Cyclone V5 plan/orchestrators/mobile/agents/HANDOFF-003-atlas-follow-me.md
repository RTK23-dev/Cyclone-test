# HANDOFF-003 — atlas-follow-me

**From:** Mobile 5.0 orchestrator  
**To:** one implementation agent  
**Wave:** alpha.2 start  
**Branch:** `v5/mobile/atlas-follow-me` off `v5/integration` (needs 001 schemas merged)  
**PR into:** `v5/integration`  
**Code paths (only these):** `apps/mobile/**/applearner/**`, `apps/mobile/**/brain/graphv2/**`, new `AtlasStore` / `PlaceCatalog`, Settings App Maps **mini** row, Follow Me write path into atlas, gateway `atlas.places` / `atlas.get` **filled** from the store  
**Do not touch:** `apps/pc-companion/**` (Glass draws the big board), mapper crawl (later), Ask compiler, vault (002)

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../../README.md)
2. [`06-atlas-and-mapper.md`](../../../06-atlas-and-mapper.md)
3. [`04-app-maps-canvas.md`](../../../04-app-maps-canvas.md) — you supply data; you do **not** build the Minitap board
4. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
5. Code: `AppGraphEngine.kt`, `GraphV2Contracts.kt`, Follow Me learner

Follow Me already learns screens. Promote that to **the** atlas. Dummy ≠ live (persona on the graph). Fact slots are **how to read**, not mapping-day emails.

## Your individual task

1. `AtlasStore` + `PlaceCatalog` (packages + chrome origins stub: origin field exists even if unused). Persona split.
2. Promote Graph v2: purpose, fact slots, danger, confidence, lastVerified, layout coords (so Glass can persist operator drags later).
3. Follow Me writes the same store (teach path).
4. Settings → App Maps: catalog list, status, **small** graph or “open on Glass”, Start button **disabled / labeled coming** if mapper is not this PR. Do not fake a crawl.
5. Fill gateway `atlas.places` and `atlas.get` from the store (empty valid document if unmapped).
6. Retriever: capability+slot query sketch is allowed; **do not** execute macros from it.
7. Tests: Follow Me → atlas node; dummy persona not mixed; `atlas.get` matches schema; no raw password on a node.

## Required

PR + `returns/RETURN-003-atlas-follow-me.md` with GitHub evidence.

## Out of scope

Autonomous mapper, Glass canvas, Chrome facebook.com crawl, People memory.

## Success

After a Follow Me of Gmail, `atlas.get` returns a house Glass 002 can draw. Phone Settings shows the place as Mapped (teach), not a blank.
