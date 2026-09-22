# Glass orchestrator — STATUS

**Wave:** Run 2 **PLANNED** (alpha.2 pipe — live atlas/vault replica). Waiting for operator go.  
**Do not launch agents until go.**  
**Integration branch:** `v5/integration` @ `8c597e1b58cfa117520146c43328b1a1bbedeb88`  
**Last release:** `glass-1.0.0-alpha.1` (companion `1.6.0-alpha.1`)  
**Code base:** One 1.5.5 live path on Mobile 4.8.0; Glass HUD from Run 1

## Run 1 (complete)

| ID | Agent | PR | State |
|---|---|---|---|
| 001 | shell-ask-secret | [#153](https://github.com/premiumcentraal-boop/Cyclone/pull/153) | merged |
| 002 | maps-canvas | [#155](https://github.com/premiumcentraal-boop/Cyclone/pull/155) | merged |
| 003 | atlas-client | [#154](https://github.com/premiumcentraal-boop/Cyclone/pull/154) | merged |
| 004 | glue-maps | [#156](https://github.com/premiumcentraal-boop/Cyclone/pull/156) | merged |
| 005 | glue-copy-adapter | [#158](https://github.com/premiumcentraal-boop/Cyclone/pull/158) | merged |
| 006 | glass-identity | [#157](https://github.com/premiumcentraal-boop/Cyclone/pull/157) | merged |

## Run 2 board (planned)

| ID | Agent | Branch | Paths | State |
|---|---|---|---|---|
| 007 | maps-honest-source | `v5/glass/maps-honest-source` | `mapsPage.ts`, empty/loading, live `MapsDataSource` load | planned |
| 008 | vault-live-slots | `v5/glass/vault-live-slots` | `vaultPage.ts`, `secrets.slots` / `secrets.request` UI | planned |
| 009 | glass-runtime-wire | `v5/glass/glass-runtime-wire` | `app.ts` constructors, `glassRuntime.ts`, version/session, doctor card | planned |

Merge order after returns: **007 + 008 first** (defaults stay compile-safe), then **009** if 009 lands first it must still typecheck against the option names in the shared brief.

## Why this wave, not mapping

alpha.3 Start mapping / live cursor waits on Mobile mapper (PRs [#151](https://github.com/premiumcentraal-boop/Cyclone/pull/151), [#152](https://github.com/premiumcentraal-boop/Cyclone/pull/152) still draft). `ask.start` is **not** in gateway `V5_OPS`. Run 2 closes the **alpha.2 exit**: Maps/Vault consume phone-owned `atlas.*` / `secrets.*`, and a 4.8 phone never sees a fake Gmail house.

Mobile [#146](https://github.com/premiumcentraal-boop/Cyclone/pull/146) (Follow Me atlas) is still open. Glass codes the consume path now; empty-valid / `partial` / update-the-phone are success. Do not invent a second atlas.

## Not this cut

`mapping.start` / cursor / `atlas.diff`. Encrypted PC fill. `ask.start` / Send. Gateway/MCP 5.0 bump (Mobile orch). `apps/mobile/**`. ChatGPT Attach. Camera.
