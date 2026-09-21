# Mobile orchestrator — STATUS

**Wave:** 1 (alpha.1 + atlas foundation)  
**Integration branch:** `v5/integration` — current wave-1 docs base `46db46bf7b7dfd17b3d6d66e6ac6da20436382f1`  
**Plan source:** `main@d2ec2ca5a397f82fdaf90c72c93e3168f89b47dd` (V5 plan + orchestrators)  
**Code base:** `release/cyclone-mobile-v4.8.0@97f81cb692893896b500f2372068fb1cd67d85ed`

## Board

| ID | Agent | Branch | State | PR | Return |
|---|---|---|---|---|---|
| 001 | protocol-need-secret | `v5/mobile/protocol-need-secret` | issued | | |
| 002 | vault-secrets-card | `v5/mobile/vault-secrets-card` | issued — wait for 001 merge | | |
| 003 | atlas-follow-me | `v5/mobile/atlas-follow-me` | issued — wait for 001 schemas | | |

States: `drafted` → `issued` → `in-pr` → `returned` → `merged` | `blocked`

## Contract with Glass

- [ ] Schemas on integration — Mobile 001 owns.
- [ ] `needs-secret` in the Ask presentation snapshot Glass already mirrors — Mobile 001 owns; Glass 001 must not guess.
- [ ] `atlas.get` shape Glass 002/003 can render — Glass 002 may use a mock only until Mobile 003 fills the contract.

## Wave-1 dependency order

1. 001 runs first against the exact 4.8 tip above plus the merged V5 orchestration docs.
2. 002 may implement only after 001 lands, unless it limits itself to a thin compile stub and rebases before PR review.
3. 003 starts graph writes only after 001 schemas are stable on `v5/integration`.
4. No Mapper crawl, People memory, Ask compiler/Louella, Chrome-host mapper, Glass canvas, encrypted PC fill, fleet/camera, or Magisk in this wave.

## Live baseline notes

- 4.8 has `TaskPhase { STARTING, WORKING, PAUSED, REVIEW, HUMAN, DONE, FAILED, STOPPED }`; no secret-specific run state exists yet.
- `TaskPresentationProjector` currently maps human interruptions to generic `ACTION_NEEDED`.
- `GateClass` currently covers PAY / SEND / DELETE / GRANT only. `needs-secret` must be a task/interruption state, not a fake fifth approval-risk class.
- Login-regex presentation logic still exists in `OutcomeStageCopy` / `TaskHumanizer`; wave 1 must not turn that into a wider Ask-compiler rewrite.
- Physical Pixel 8 remains **UNVERIFIED**.

## Notes

- Integration plan seed landed via PR #138; wave-1 issue/status landed via PR #139.
- Before implementation, handoffs 001–003 were amended from the live 4.8 code review: Android gateway owns atlas/secrets ops; Python gateway only forwards/validates; V5 atlas must not reuse `AppGraphExecutor` as a rapid-fire macro executor.
- This orchestrator does not implement 001–003 itself.
