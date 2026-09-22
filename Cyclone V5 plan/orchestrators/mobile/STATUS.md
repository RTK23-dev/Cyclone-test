# Mobile orchestrator — STATUS

**Wave:** Run 1 closeout in parallel with **Run 2 issued**  
**Integration branch:** `v5/integration@7e5ece6ff17d78a1a42135bc7cbad5bf397e13f3`  
**Plan source:** `main@d2ec2ca5a397f82fdaf90c72c93e3168f89b47dd`  
**Original code base:** `release/cyclone-mobile-v4.8.0@97f81cb692893896b500f2372068fb1cd67d85ed`

## Run 1 board

| ID | Agent | Branch | State | PR | Return |
|---|---|---|---|---|---|
| 001 | protocol-need-secret | `v5/mobile/protocol-need-secret` | **merged** | #144 → `c980b43e3245016b5fb29fe524ab20d4b7dd6737` | `RETURN-RUN1-001-protocol-need-secret.md` |
| 002 | vault-secrets-card | `v5/mobile/vault-secrets-card` | **merged** | #145 → `367ce4ed9864f9358802bf329de768b1676c109d` | `RETURN-RUN1-002-vault-secrets-card.md` |
| 003 | atlas-follow-me | `v5/mobile/atlas-follow-me` | **in-pr — changes required** | #146 | **missing** |

### Run-1 orchestrator correction

PR #147 is merged at `7e5ece6ff17d78a1a42135bc7cbad5bf397e13f3`.

Deep integration review found that Agent 001's original Atlas wire schema omitted the `partial` map state even though the V5 plan and Run-1 shared brief require incomplete coverage to remain distinguishable. The correction freezes:

```text
unmapped | partial | mapped | stale | blocked
```

A non-empty graph is not automatically mapped.

## Agent 003 required corrections before merge

PR #146 is not approved for integration until all are true:

1. Rebase/merge current `v5/integration` and rerun final CI on the combined head.
2. Install the real `AtlasRuntime.provider` through Agent 001's `GatewayV5ContractSources.installAtlas(...)` seam so production `atlas.get` / `atlas.places` return the phone Atlas instead of the empty fallback after initialization.
3. Serialize internal `PARTIAL` as wire `partial`; remove the non-empty → `mapped` coercion.
4. Harden Atlas promotion so ordinary user-content labels (person/thread names, message subjects, order/content titles) cannot become durable Atlas structure merely because they are not secret-shaped.
5. Add regressions for the real gateway hookup and structural-vs-content privacy.
6. Write `returns/RETURN-RUN1-003-atlas-follow-me.md`.
7. Final CI must pass; physical Pixel remains **UNVERIFIED**.

## Agent 002 follow-up hardening

Run-1 Vault architecture is accepted and merged. The phone card currently uses Compose immutable `String` state for the value while the user is typing, then converts to/clears a `CharArray`. The value does not enter the wire, Atlas, Brain or diagnostics, but the UI memory lifetime is not truthfully zeroizable. This is recorded as focused hardening for a later run; it is not a Run-1 merge blocker.

## Contract with Glass

- [x] Atlas/Secrets schemas on integration.
- [x] `needs-secret` is a distinct nonterminal consumer state.
- [x] Phone Secrets Card + metadata-only Vault gateway are integrated.
- [x] Atlas map status includes truthful `partial`.
- [ ] Production `atlas.get` returns Agent 003's durable Follow Me graph — blocked on #146 correction.
- [ ] Glass read-only Maps board consumes the real phone graph — Glass-owned exit test.
- [ ] Run-1 orchestrator return written — waits for #146.

## Run 2 — issued now

Files: `orchestrators/mobile/run-2/`

**RUN2_START_BASE_SHA:** `2a0f97179c2a57eb0548e558a98d90d81eb30064`

| ID | Agent | Branch | State |
|---|---|---|---|
| 004 | mapping-session-protocol | `v5/mobile/mapping-session-protocol` | **issued — branch created @ RUN2_START_BASE_SHA; code now** |
| 005 | mapper-walker-safety | `v5/mobile/mapper-walker-safety` | **issued — branch created @ RUN2_START_BASE_SHA; code now; use narrow 004/006 seams as needed** |
| 006 | place-catalog-chrome-settings | `v5/mobile/place-catalog-chrome-settings` | **issued — branch created @ RUN2_START_BASE_SHA; code now** |

Run-2 coding is allowed before Run-1 closes. The hard gate is now: **no Run-2 merge before #146 merges, RUN1_CLOSEOUT_SHA is recorded, each Run-2 branch rebases/merges-forward onto it, and final CI passes again.**

Planned final integration order:

```text
004 → 006 → 005
```

## Physical acceptance

Pixel/device acceptance remains **UNVERIFIED**. GitHub CI, lint and release assembly are not physical-device evidence.
