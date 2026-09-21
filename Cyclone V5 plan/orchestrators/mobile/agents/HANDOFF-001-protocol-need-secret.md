# HANDOFF-001 — protocol-need-secret

**From:** Mobile 5.0 orchestrator  
**To:** one implementation agent  
**Wave:** alpha.1  
**Branch:** `v5/mobile/protocol-need-secret` off `v5/integration`  
**PR into:** `v5/integration`  
**Code paths (only these):** `protocol/`, `apps/device-gateway/**` (new ops stubs + reject secret values), GATE / run-state types under `apps/mobile/**` that already own task phase (find the existing enum; extend — do not create a parallel state machine), Ask presentation snapshot so Glass can show `needs-secret`  
**Do not touch:** `apps/pc-companion/**`, vault implementation (002), AtlasStore (003), mapper, destination-authority regex titles

## Total picture (read first)

1. [`Cyclone V5 plan/README.md`](../../../README.md)
2. [`01-headset-and-laws.md`](../../../01-headset-and-laws.md)
3. [`08-protocol-gateway.md`](../../../08-protocol-gateway.md)
4. [`orchestrators/CONTRACT.md`](../../CONTRACT.md)
5. `docs/ARCHITECTURE.md` — phone mutates, PC is glass
6. `AGENTS.md` invariants

4.8 fails a Facebook password wall as **Couldn’t finish**. You add the **type** and the **pipe** so a wall is `needs-secret`, and so Glass/Mobile share names.

## Your individual task

1. Add `protocol/cyclone-atlas-v1.schema.json` and `protocol/cyclone-secrets-v1.schema.json` matching CONTRACT names (Place, persona, ops listed there). Atlas schema must be enough for a Follow Me graph later (screens, edges, purpose, slots, danger, layout). Values for secrets are **not** in the schema.
2. Add GATE / task run state **`needs-secret`** (or equivalent existing name — reuse if 4.8 already has a human-input state; do not fork). Presentation snapshot field Glass already consumes must carry it.
3. Gateway: stub `secrets.slots` (booleans), `secrets.request` (no value), `atlas.places` / `atlas.get` returning empty-but-valid documents until 003 fills them. Reject payloads that look like secret values.
4. MCP: do not expose secret values; readonly remote mode must not gain `mapping.start`.
5. Tests: schema validate fixtures; GATE state is not `failed`; gateway rejects a password field.
6. If CONTRACT names had to move, edit CONTRACT.md **in this PR**.

## Required

1. Only this task.
2. PR + CI you can run (`device-gateway` pytest, mobile unit tests for the state you touched).
3. Write [`../returns/RETURN-001-protocol-need-secret.md`](../returns/RETURN-001-protocol-need-secret.md) with PR URL and SHAs.

## Out of scope

Secrets card UI, Keystore, AtlasStore fill from Follow Me, Maps canvas, Ask composer on PC.

## Success

Glass 001 can type against `needs-secret` without guessing. Glass 003 can generate types from the schemas. 002 can hang a card on the state you added.
