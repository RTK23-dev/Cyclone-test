# Cyclone Mobile 5.0.0-alpha.2.dev1

This is a **development checkpoint**, not the alpha.2 exit and not a public-production release.

It combines the V5 Mobile work that exists so far with the already-merged Glass operator work on `v5/integration`.

## What is now in one build line

### V5 alpha.1 foundation

- distinct nonterminal `needs-secret` presentation state;
- Atlas and Secrets schemas;
- Android-owned V5 gateway operations;
- StrongBox-preferred / Android-Keystore fallback Vault;
- phone Secrets Card;
- one-shot secure fill through PhoneToolExecutor;
- metadata-only slot presence on the wire;
- no secret values in Atlas / Brain / gateway / Glass fixtures.

### Atlas / alpha.2 foundation

- durable phone-local AtlasStore;
- Follow Me writes into the same Atlas;
- Graph-v2 reuse rather than a second graph;
- real production `atlas.get` / `atlas.places` source;
- live/mapping persona split;
- truthful `partial` map state;
- structural-only Atlas privacy boundary;
- data-only retrieval hints;
- App Maps mobile composable.

### Mapping / alpha.3 foundation pulled forward

- phone mapping-session state machine;
- `atlas.diff`;
- `mapping.start | pause | stop | status`;
- foreground / named VD / Layer-2 authority binding;
- mapper budgets;
- nonterminal secret/human boundaries;
- safe mapper walker with observe → one mutation → observe → verify;
- never-pay / never-send / never-delete / never-auto-GRANT behavior;
- mapping writes isolated to mapping persona.

### Glass already on the same integration lineage

- Cyclone Glass shell with Ask, Maps and Vault;
- full read-only Maps board and inspector;
- honest live-vs-demo source behavior;
- phone-owned Vault slot presence;
- focused device/session binding;
- foreground vs named-VD plane labels;
- Take control path;
- dark-door filter and English edge inspector;
- Ask sample honesty and redacted HUD-log export.

## Why this is alpha.2.dev1, not alpha.2

Still missing:

1. Agent 006 canonical Place/Chrome resolver and root Settings mount;
2. combined physical Pixel pass;
3. proof that Glass renders a real learned phone Atlas in a named acceptance run;
4. live mapping cursor/start wiring from Glass;
5. alpha.2 exit demo: Follow Me Gmail → explain the real house on Glass;
6. Chrome-host mapping;
7. Ask compiler / People memory / Louella path;
8. freshness/remap system;
9. encrypted Glass secret entry (phone-only card remains the proven path).

## Release safety

- publication disabled;
- Android versionCode 141;
- physical Pixel status UNVERIFIED;
- candidate is acceptable only if exact-source combined CI passes.
