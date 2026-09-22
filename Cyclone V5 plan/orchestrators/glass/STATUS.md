# Glass orchestrator — STATUS

**Wave:** Run 1 **COMPLETE** (alpha.1 HUD + alpha.2 Maps look-and-feel)  
**Integration branch:** `v5/integration`  
**Release:** `glass-1.0.0-alpha.1` (companion `1.6.0-alpha.1`)  
**Orch session:** 2026-09-22  
**Tests:** `cd apps/pc-companion && npm test` → **156 pass / 0 fail** on the glued tree

## Board

| ID | Agent | Branch | State | PR | Return |
|---|---|---|---|---|---|
| 001 | shell-ask-secret | `v5/glass/shell-ask-secret` | merged | [#153](https://github.com/premiumcentraal-boop/Cyclone/pull/153) | RETURN-001 |
| 002 | maps-canvas | `v5/glass/maps-canvas` | merged | [#155](https://github.com/premiumcentraal-boop/Cyclone/pull/155) (local merge `c9f33385`; GH squash blocked on tsconfig) | RETURN-002 |
| 003 | atlas-client | `v5/glass/atlas-client` | merged | [#154](https://github.com/premiumcentraal-boop/Cyclone/pull/154) | RETURN-003 |
| 004 | glue-maps | `v5/glass/glue-maps` | merged | [#156](https://github.com/premiumcentraal-boop/Cyclone/pull/156) | RETURN-004 |
| 005 | glue-copy-adapter | `v5/glass/glue-copy-adapter` | merged | [#158](https://github.com/premiumcentraal-boop/Cyclone/pull/158) | RETURN-005 |
| 006 | glass-identity | `v5/glass/glass-identity` | merged | [#157](https://github.com/premiumcentraal-boop/Cyclone/pull/157) | RETURN-006 |

## Shipped on integration

- Nav: Phone / Ask / Maps / Vault / Tasks / Connections / ChatGPT. Brand **Cyclone Glass**.
- Ask: `needs-secret` wait card (not failed). Title **Facebook needs a password**. Send disabled until Mobile Ask transport. Phones < 5.0 get “update the phone.”
- Maps: Minitap-class board mounted (`createMapsPage()`). Gmail house + Chrome Facebook + unmapped YouTube. Live vs Dummy. Mock atlas for Run 1.
- Vault: slot booleans only. No PC password input.
- `atlasClient` + `atlasClientAdapter` (async `atlas.get` → sync `MapsDataSource`). Not auto-mounted; mock remains the visible board until phone 5.0 atlas is live.
- Window title Cyclone Glass. Installer `productName` still Cyclone One.
- Live JPEG / handoff / camera / ChatGPT Attach untouched.

## Contract with Mobile

- [x] Schemas on integration — #144
- [x] `needs-secret` consumer state — #144
- [ ] Follow Me house as real `atlas.get` — #146 still open
- [x] `session_id` rules preserved
- [x] Glass Ask shows `needs-secret` / waiting-for-card
- [x] Maps board renders a graph (mock until #146)
- [x] No secret values in fixtures

## Not this cut (do not start)

Mapping cursor / `mapping.start` (alpha.3). Encrypted PC fill. Mobile 5.0.0-alpha.N pipe bump (Mobile orch). Windows installer binary (CI unsigned).
