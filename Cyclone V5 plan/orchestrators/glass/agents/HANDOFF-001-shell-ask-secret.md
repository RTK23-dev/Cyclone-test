# HANDOFF-001 — shell-ask-secret

**From:** Glass 1.0 orchestrator  
**To:** one implementation agent  
**Wave:** alpha.1  
**Branch:** `v5/glass/shell-ask-secret` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):** `apps/pc-companion/src/app.ts` (nav), `src/core/fleet.ts` (route type/state only), new `pages/askPage.ts`, `pages/vaultPage.ts` (slot inventory stub), `ui/secretsCard.ts` (waiting / phone-card banner; no PC vault), companion CSS as needed, `tests/fleet.test.mjs` plus focused new UI tests if the existing test harness can cover them, doctor/settings one-liner “Maps/Ask atlas need Mobile 5”  
**Do not touch:** `mapsPage` / canvas (002), `atlasClient` or new atlas/secrets transport (003), `livePhoneController`, ChatGPT Attach, camera, `apps/mobile/**`

## Baseline scope correction from orchestrator

On One 1.5.5 / `release/cyclone-mobile-v4.8.0`, `AppRoute` is defined in `src/core/fleet.ts` and route coverage lives in `tests/fleet.test.mjs`; adding Ask/Maps/Vault in `app.ts` alone will not compile cleanly. You may edit those two routing/test files only for the new routes. Do **not** grow 001 into the protocol client lane.

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../../README.md)
2. [`03-glass-v1.md`](../../../03-glass-v1.md) G0–G1, G3 path 1, G4
3. [`05-secrets-vault.md`](../../../05-secrets-vault.md)
4. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
5. `apps/pc-companion/src/app.ts` + `src/core/fleet.ts` on the 4.8 baseline

## Your individual task

1. Primary nav becomes: **Phone** (existing focused live/control entry), **Ask**, **Maps** (mount a placeholder page titled Maps — “board in 002”), **Vault**, keep Tasks / Connections / Settings.
2. Brand label **Cyclone Glass** in the top bar. Installer path can stay One.
3. **Ask page:** build the composer + HUD presentation surface. If a compatible existing gateway Ask seam is already present on `v5/integration`, use it. Otherwise do **not invent protocol names or transport**; keep the composer honest/disabled until 003 wires the contract, while still rendering the existing/fixture presentation snapshot. Show `needs-secret` as **Needs you — [slot]** with a waiting card (G3 path 1: password is entered on the phone). Take control stays the existing handoff.
4. **Vault page:** slot booleans only (“Facebook password: set”). Empty until 003. Never an input that stores a secret on the PC.
5. Mobile < 5.0: Ask/Maps/Vault show update-the-phone, not a fake atlas.
6. Tests: nav routes in the real `tests/fleet.test.mjs` harness; Ask renders a `needs-secret` fixture; Vault has no password field in the DOM. Do not weaken existing focused-live preservation tests.

## Required

PR + `returns/RETURN-001-shell-ask-secret.md` with GitHub evidence.

## Out of scope

Full Maps canvas, atlas/secrets transport, encrypted PC fill.

## Success

Operator can open Ask on the desk and see a password wall as a wait state, not a crash. Maps is in the nav, even if the board is a placeholder.
