# Cyclone Mind — one model, one conversation, one mission

**Status:** built for 5.0.0-alpha.25.dev1 (versionCode 166) on top of the alpha.24 developer candidate. Unit-tested on
the JVM; **physical-phone acceptance is UNVERIFIED** until the device plan below is run. Implements the direction of
[`15-model-is-the-agent.md`](15-model-is-the-agent.md).

## What changed

| Before (alpha.23/24 step agent) | Cyclone Mind (alpha.25) |
|---|---|
| Each decision was a fresh two-message call about "this scene" | One conversation per mission: the goal, every thought, call and result stay in it |
| ~20 local shortcuts ran before the model and could take the turn | The model chooses every action; the harness only enforces boundaries |
| 9 tap-level actions in a JSON reply format | 24 native tools (OpenRouter `tools` / `tool_calls`), text envelope only when a route lacks tools |
| 8–22 s per decision | 180 s per model call, 30 min working time per mission (10/30/60 in settings); owner waits do not count |
| Login autofill and takeover prompts decided on their own | The model decides; secrets go through the Secrets Card on its request; approvals are asked at the action |
| Keyword contracts judged "done" | The model finishes with evidence it saw; the harness records the final screen next to it |
| Nothing survived an interruption | Every turn is journaled; paused, failed and interrupted missions resume with the whole conversation |

## Architecture (`apps/mobile/app/src/main/java/com/cyclone/mobile/mind/`)

```
MindMissions (Android)  ── start / resume / stop / steer, thread "cyclone-mind", trace + task card + overlay
   │
   ├─ MindLoop           ── turns: compact → model → tool calls → results → checkpoint; recovery; budget
   │    ├─ MindModel     ── OpenRouterMindModel: native tools, typed errors, usage/cost, 180 s MISSION budget
   │    ├─ MindConversation ── the memory; compaction keeps the model's own turns, shrinks old results
   │    └─ MindPrompt    ── plain standing instructions, no phrase tables
   │
   ├─ PhoneMindToolbox   ── tools → CycloneAgentEnvironment.act (policy, GATE, PhoneToolExecutor, settle, verify)
   │    └─ MindScreen    ── screens as text with stable refs (e1…), no element IDs or coordinates
   │
   ├─ AndroidMindOwner   ── owner_ask, approvals, Secrets Card, hand-back; OwnerInbox is the single open request
   └─ MissionStore       ── Cyclone Brain/Missions/<id>.mission.json + <id>.journal.json (redacted)
```

### Tools

| Group | Tools |
|---|---|
| See | `screen_read`, `screen_look` (screenshot for vision models), `screen_find` |
| Act | `tap`, `long_press`, `type_text` (+`press_enter`), `press_enter`, `scroll`, `back`, `home`, `wait` |
| Go / do | `open_app`, `open_link` (https, market://, geo, mailto, tel, sms), `open_settings` (allowlisted pages), `set_timer`, `set_alarm` |
| Know | `apps_list`, `recall` (Brain + verified routes) |
| Owner | `owner_ask`, `vault_fill` (Secrets Card) |
| Track / finish | `plan_update`, `note`, `task_finish` (summary + evidence), `task_give_up` |

New executor tools: `phone.open_settings` (navigation only) and `phone.submit_text` (IME Enter; free in search/address
fields, GATE approval elsewhere, blocked in Guided). Fixed: `phone.set_alarm` / `phone.set_timer` were not in the
local agent's allowed mutations, so the alpha.23 clock path could never run.

### Boundaries the harness keeps (the model cannot turn these off)

- `PhoneToolExecutor` remains the only mutation engine; every Mind action passes `CycloneAgentEnvironment.act`.
- One screen-changing action per model turn: later calls from the same turn are returned as NOT RUN with the new screen.
  Typing does not end the turn, so a form can be filled in one turn.
- Pay/send/delete/grant and other consequential taps raise the GATE card; the mission waits for the owner (overlay
  button or the mission's request card), then retries the exact same control once. Declines are final.
- Password, code and card fields are refused for `type_text`; `vault_fill` opens the Secrets Card and the value goes
  from the owner/Vault straight into the field. The model never sees it; journals and traces never contain it.
- CAPTCHAs and human-verification checks are handed to the owner; the prompt forbids working around them.
- Journals and diagnostics are redacted (`MindRedaction`), exclude screenshots and provider reasoning
  (`reasoning_details` is kept in memory only for the same model and dropped when switching to the backup).
- No shell or root access. Root abilities remain an open owner decision (see `AGENTS.md` before adding any).

### Recovery

Rate limit → backup model (same conversation) or bounded waits; deadline → one retry then backup; transient → 3
backoff retries then backup; tools unsupported → text protocol; fatal → backup or honest stop. A model turn without a
tool call gets two nudges, then the mission stops with the model's last words. Three identical failures in a row add
one harness note. At 3 minutes before the working time ends the model is told once.

## Device acceptance plan (owner's Pixel 8)

Record each as PASS/FAIL with the exported run diagnostic.

1. **Timer on a leftover screen.** Leave Chrome on a login page. Ask "set a timer for 5 minutes". Expect `set_timer`,
   Clock showing the countdown, `task_finish` with it as evidence, no login prompts.
2. **Install.** "download Instagram" with Instagram installed → the model reports it is installed (or opens its Play
   Store page) — no silent "open app". With an uninstalled app → Play Store page, Install needs no secret.
3. **Two steps, two apps.** "check which Gmail account I'm logged in with, then open facebook.com in Chrome" → reads
   the account in Gmail, remembers it, opens the site, and reports both with evidence.
4. **Secret.** "log in to <site> with my account" → `vault_fill` opens the Secrets Card on the password field; the
   diagnostic contains no value.
5. **Approval.** "send 'hi' to <contact> in WhatsApp" → GATE card at Send; Approve from the Ask request card works;
   Decline ends honestly.
6. **Question.** An ambiguous request ("book the usual") → `owner_ask`; answer from the overlay composer and from Ask.
7. **Interruption.** Force-stop Cyclone mid-mission → Ask shows it as interrupted → Resume continues with its memory.
8. **Long sprint.** A 15–30 min multi-app task; check working time excludes owner waits and the budget note appears.
9. **Settings off.** Turn Cyclone Mind off → the classic agent runs as in alpha.24.

## Known limits of this alpha

- Foreground only; background workspaces still use the step agent.
- Glass shows Mind runs through the ordinary run trace (MIND_* events) without a dedicated view yet.
- Cost grows with conversation length; the context cap is ~150k characters with older results compacted. Providers
  with automatic prompt caching make repeated turns cheaper.
