# Glass agent handoffs

Orchestrator writes `HANDOFF-00N-<slug>.md` here.  
Agents do not edit these files. Agents write to [`../returns/`](../returns/).

## Wave 1 — issued

- [HANDOFF-001-shell-ask-secret.md](HANDOFF-001-shell-ask-secret.md) — issued; waits for `v5/integration`
- [HANDOFF-002-maps-canvas.md](HANDOFF-002-maps-canvas.md) — issued; standalone mock canvas, no `app.ts` fight
- [HANDOFF-003-atlas-client.md](HANDOFF-003-atlas-client.md) — issued but execution waits for 001 nav + Mobile 001 schemas

Current blocker is tracked in GitHub issue **#137 `v5-orch-sync`**. Do not branch product code from stale `main` or directly from the 4.8 release branch.

Template: [`../../TEMPLATES/AGENT-HANDOFF.md`](../../TEMPLATES/AGENT-HANDOFF.md)
