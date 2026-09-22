# Mobile orchestrator — STATUS

**Wave:** consolidated V5 checkpoint  
**Combined candidate:** `5.0.0-alpha.2.dev1`  
**Combined branch:** `v5/mobile/alpha2-preview1-combined`  
**Integration base:** `v5/integration@f52eae1d9d3aad766fffd7aa436ead280cacdd75`  
**Original Mobile baseline:** `release/cyclone-mobile-v4.8.0@97f81cb692893896b500f2372068fb1cd67d85ed`

## Current Mobile integration picture

| ID | Work | Source | Combined checkpoint |
|---|---|---|---|
| 001 | protocol + needs-secret | PR #144 | merged earlier |
| 002 | Vault + Secrets Card | PR #145 | merged earlier |
| 003 | durable Atlas + Follow Me | PR #146 / corrected source | **included in 5.0.0-alpha.2.dev1 candidate** |
| 004 | mapping session + protocol | PR #152 | **included in candidate** |
| 005 | safe mapper walker | PR #151 / nullability correction | **included in candidate** |
| 006 | canonical Place/Chrome + Settings | branch exists, no commits | **NOT included / still missing** |

The combined checkpoint deliberately overlays 003/004/005 onto the latest integration line instead of merging their stale histories. This preserves all already-merged Glass work.

## V5 foundation now represented in the candidate

- [x] nonterminal `needs-secret`;
- [x] Vault + phone Secrets Card;
- [x] metadata-only secret slot wire;
- [x] durable phone Atlas;
- [x] Follow Me → same Atlas;
- [x] production phone-owned `atlas.get` / `atlas.places`;
- [x] truthful `partial`;
- [x] structural/content privacy boundary;
- [x] mapping session state machine;
- [x] `atlas.diff`;
- [x] `mapping.start | pause | stop | status`;
- [x] existing session/display/workspace authority reused;
- [x] safe mapper walker;
- [x] one mutation per decision;
- [x] never-pay/send/delete/auto-GRANT mapping policy;
- [x] mapping persona isolation;
- [ ] canonical Chrome origin Place resolver — Agent 006;
- [ ] root Settings mount for App Maps + Vault — Agent 006;
- [ ] physical Pixel acceptance.

## Glass on the same integration lineage

Glass Runs 1–3 are already merged on the candidate base:

- Ask / Maps / Vault shell;
- full read-only Maps board;
- honest demo/live source;
- phone Vault slot presence;
- focused device + session plane binding;
- English edge inspector;
- dark-door filter;
- Take control → Phone;
- honest Ask samples and redacted HUD log.

The combined Mobile candidate is intended to remove Glass's largest remaining read-only blocker: a real phone-owned Atlas source.

## Version

`5.0.0-alpha.2.dev1` / Android versionCode **141**.

Gateway and both MCP packages are bumped to the same V5 development version. Glass remains `1.6.0-alpha.1`.

Publication is **disabled**. This branch is a CI candidate, not a public production release.

## Required exit before calling this alpha.2

1. exact-source combined Mobile CI green;
2. Agent 006 canonical Place/Chrome + Settings integration;
3. named physical Pixel pass;
4. Follow Me one real app and render that real Atlas in Glass;
5. operator can explain the rooms from Glass without logs.

## Later V5 work

- Glass live mapping cursor/start;
- Chrome-host mapping;
- Ask compiler;
- People memory / Louella;
- stale/remap freshness;
- encrypted Glass secret fill or documented phone-card-only V1 path.

**Physical Pixel status:** UNVERIFIED.
