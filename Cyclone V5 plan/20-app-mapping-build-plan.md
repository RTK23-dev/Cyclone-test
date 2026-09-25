# 20 — App mapping: final build plan (alpha.36 → alpha.38)

The owner's goal: **Cyclone knows every app on the phone the way a person who uses it daily does.** It knows how to
get in (signed out, register, sign in, already signed in), the base pages (Instagram: Home, Reels, Search, DMs,
Profile, Settings), every door on those pages and where it leads. It learns this in three ways: a dedicated mapping
mission, every normal run, and a **Learn** button that turns past runs into map. The more you use Cyclone, the faster
and more confident it gets. You can inspect all of it visually in Glass.

This plan builds on [04 App Maps canvas](04-app-maps-canvas.md) and [06 Atlas and mapper](06-atlas-and-mapper.md),
which remain the product spec. This document is the **build plan**: what exists, what is missing, the decisions, and
the release-by-release work.

---

## 1. Where we really are (inventory from the code, 2026-09-25)

| Piece | Status | Where |
|---|---|---|
| **Atlas**: places, screens (rooms), edges (doors), evidence, confidence, live/mapping personas, layout, danger, fact slots, versions, diff journal | Built, durable, one graph | `brain/graphv2/AtlasStore.kt`, `applearner/graphv2/*`, `mapping/session/AtlasDiffJournal.kt` |
| Sources feeding the Atlas | Follow Me (`FollowMeAtlasPromoter`), the legacy classic agent (`PageAwarenessEngine`, `PcVerifiedRouteLearning`), the deterministic mapper | `applearner/*`, `mapping/*` |
| Deterministic mapper: one safe door at a time, danger blocking, secret walls, budget | Built, unit-tested, **never physically accepted** | `mapping/crawl/SafeMapperWalker.kt`, `mapping/run/MappingDriverRuntime.kt` |
| Glass: app catalog, per-app Map board, Screens, Scenarios, Versions, Runs, Issues, live mapping cursor | Built | `apps/glass/src/pages/appsPage.ts`, `appKnowledgePage.ts`, `ui/appMapCanvas.ts` |
| Gateway ops `apps.list`, `atlas.*`, `mapping.*`, `scenarios.list`, `runs.*` | Built | `GatewayV5*Adapter.kt`, `v5_contract.py` |
| **Cyclone Mind** (runs every Ask since alpha.25) | Built | `mind/*` |
| Mission journals: the last 40 missions' full redacted tool history | Built | `mind/mission/MissionStore.kt` |
| Owner recipes (Marketplace, alpha.35) | Built | `market/*` |

**The five gaps that make this "not V5 yet":**

1. **The Mind neither reads nor writes the Atlas.** Nothing under `mind/` touches it. Every run is thrown away as
   knowledge, and the map never makes a run faster. *This is the single biggest gap.*
2. **The mapper has no judgement.** A structural walker cannot get through sign-up or sign-in flows, name base pages
   ("this tab is Reels"), fill forms or choose what matters. It has also never run on the Pixel.
3. **Brain → Outcomes is old.** "View details" is text rather than a button, and there is no *Learn* and no *Save skill*.
4. **No test identity.** The `mapping` persona exists in the Atlas, but there is no way to give Cyclone a test
   account to map with.
5. **No proof it helps.** Nothing measures whether a map makes runs faster or more reliable.

---

## 2. What Minitap gets right, and what Cyclone takes from it

Minitap (minitap.ai) is an autonomous mobile QA agent. It maps every flow of an app starting from each
authentication state (registration, sign-in, already signed in), turns them into scenarios, and tests *outcomes* ("can
the user achieve the goal") with **"acceptance criteria, no selectors to maintain"**, so UI changes don't break it. It
attaches a replay, logs and repro steps to every failure. It reports **116/116 on AndroidWorld**.

| Take | Why |
|---|---|
| **Start from the auth states** and lay the app out left to right: entry → landing → base pages → depth | That is how people understand an app, and the order the board should show |
| **Outcome-based, selector-light**: a door is "open Reels", with the last selector only a hint | Maps survive app updates; Cyclone re-finds the control by meaning |
| **Scenarios** = routes to an end result, with health from real runs | Already specified in 04. Now fed by Learn |
| **A replay for every failure** | Already there: run inspector and Lab |

| Don't take | Why |
|---|---|
| QA framing ("is the app broken") | Map health measures **whether Cyclone can get there**, not whether the app works |
| Blind replay of recorded steps | Law 1: every door is followed by looking again; a map step is fast, never blind |
| Mapping on a real account by default | Exploration taps everything; that is what a test identity is for |

---

## 3. The decisions (the billion-dollar calls)

1. **One map, many sources.** Everything writes the same Atlas with typed evidence: `FOLLOW_ME`, `MAPPER`,
   `MIND_RUN` (live learning), `LEARNED_RUN` (Learn button), `OWNER_PIN`. Confidence comes from evidence: many
   independent successful sightings beat one. Owner pins always win. No second graph, ever.
2. **Model for understanding, machine for breadth.** Mapping missions are **Mind missions in explore mode**. The Mind
   decides what a page *is*, gets through sign-in or registration, names base pages and chooses where to go next. The
   deterministic walker does the cheap sweeps: tap each safe door on a known page, look, back, with no model call per
   door. That hybrid is what makes mapping thousands of doors affordable.
3. **Runs are the main mapper.** Most knowledge will come from normal use, not dedicated missions. So every Mind step
   records a structured **trail** (screen before → action → screen after), cheaply and privately. **Learn** promotes
   trails into the Atlas. Auto-learn from successful runs becomes the default once the Lab proves it helps.
4. **The map is advice, never authority.** The Mind gets *known routes* as hints and a `go_to` tool that walks known
   doors while **checking the room after each one**, handing back to reasoning the moment the screen differs.
5. **Structure is shareable, your life is not.** The Atlas stores structure (rooms, doors, purposes, danger) with
   structural labels, never what you typed, messages, names or secrets. That is what later allows **App Packs**:
   downloadable maps of popular apps in the Marketplace (Phase 4 network effect).
6. **Tiers make "fully mapped" measurable.** T0 Entry (auth states) · T1 Base pages (bottom nav, tabs, drawer) · T2
   Doors on base pages · T3 Depth (sub-pages, settings trees). Coverage is reported per tier, so "Instagram: Entry
   100%, Base 100%, Doors 64%, Depth 12%" means something.

---

## 4. The data model (additions to the existing Atlas)

| Concept | Existing | Added |
|---|---|---|
| Place | ✓ (`AtlasPlace`, package or Chrome origin) | coverage per tier, last learned run |
| Room (screen) | ✓ (`PageNode` + `AtlasScreenMetadata`) | **tier** (entry / base / page / deep), **entry state** (signed-out / register / sign-in / signed-in), base-page name ("Reels"), fingerprint = `structuralKey` + package + activity + landmark hash |
| Door | ✓ (`ElementNode` + `TransitionNode`) | kind (tab, nav, button, menu, list-item-sample), success and failure counts, last route used |
| Scenario | ✓ (spec 04, `scenarios.list`) | **learned from runs**: goal template → route of rooms, with success and failure counts per app version |
| Evidence | ✓ (`TemporalKnowledgeEdge`) | sources `MIND_RUN`, `LEARNED_RUN`; weighting: success > seen > failed; negative evidence for doors that did not lead where expected |
| Trail (new, per mission) | – | `MissionTrail`: steps of {before fingerprint, action (tool, target role and structural label, never typed text), after fingerprint, changedScreen, ok, ms} |

Privacy stays as enforced today by `AtlasPrivacy`: structural labels only, redacted frames, no text field values and
no secrets. A new CI guard checks that the learner never passes free text into the Atlas.

---

## 5. How the map grows: four paths

### 5.1 Every run records a trail (automatic, alpha.36)
`MindTrailRecorder` is a `MindListener`, like `MissionMetrics`. It takes each observation's `AgentPageCard`
(`structuralKey`, `packageName`, `activity`, `legacyPage`) and each tool call (`tap e7` → the ref's role and
structural label) and writes a `MissionTrail` next to the mission (bounded to 400 steps, redacted). Cost: no model
calls, a few kilobytes per mission.

### 5.2 Learn: one button turns a run into map (alpha.36)
`MissionLearner` reads a trail and writes to the Atlas through the existing `FollowMeAtlasPromoter` primitives
(`observeScreen`, `demonstrateTransition`) with `LEARNED_RUN` evidence on the **live** persona:
- every distinct room it saw, with the tier guessed from structure (bottom-nav or tab bar present → base page);
- every door it used that changed the screen, as a transition;
- for a **successful** mission: the route as a **scenario** ("goal → rooms"), so the next similar goal finds it;
- for a failed step: negative evidence on that door (confidence down), never a route;
- idempotent: a mission is learned once (`learnedAt` on the mission), and learning it again is a no-op;
- missions from before alpha.36 have no trail; Learn falls back to the journal (tool calls and results) and does
  best-effort rooms and doors, marked lower confidence.

Result shown to the owner: *"Learned 7 screens, 12 doors and 1 route in Instagram."*

### 5.3 Save skill (alpha.36)
From a **successful** run: *Save skill* creates an owner recipe in the Marketplace (source `saved`). The goal becomes
the template, and Cyclone proposes inputs by spotting values in the sentence (numbers, names, places) that the owner
confirms. It also stores the learned route as the recipe's preferred path. Next time it runs map-guided (alpha.37)
and falls back to the Mind if the app changed.

### 5.4 Mapping missions (alpha.38)
Glass → Apps → any app (even 0% mapped) → **Start mapping**:
- **Identity**: *Map with my account (look, don't change)* or *Map with a test account*. Test credentials go into the
  phone's Vault through the Secrets Card and are used with `vault_fill`; the map stores the mapping persona separately.
  Creating a test account is done by the owner, or by Cyclone with the owner's approval at the final submit.
  CAPTCHAs and SMS verification are always the owner's hands.
- **Plan by tier**: detect the entry state → map T0 (signed-out, register and sign-in screens, up to the final submit) →
  T1 base pages (every tab and nav item, named) → T2 doors on each base page (deterministic sweep) → T3 depth by
  frontier (unmapped doors nearest to base pages first).
- **Budget**: Quick (10 min: T0+T1) · Standard (30 min: +T2) · Deep (2 h: +T3), or pages chosen on the board.
- **Never**: send, post, pay, buy, subscribe, follow or unfollow, delete, change account security, or open more than one
  item of a feed or inbox. Danger rooms are marked and left. Consequential mapping actions stop at the GATE.
- **Report**: coverage per tier before → after, new rooms, danger found, walls hit (needs secret, needs you), cost.

---

## 6. How the map makes runs faster (alpha.37)

- **Route hints in the prompt.** For the app(s) in the goal, the Mind gets a compact *map card*: base pages, known
  scenarios close to the goal, and dangerous rooms. That is at most about 40 lines, cached by prompt caching.
- **`go_to(room)` tool.** Walks known doors with the fast path, **re-observing after each door** (the Fast Path settle
  that already exists), and stops at the first mismatch, returning "arrived", or "diverged at room X, here is the
  screen". No model call per step on the known part.
- **Feedback loop.** A successful map-guided step adds `MIND_RUN` evidence; a divergence lowers confidence and marks
  the room *stale*, so the next mapping pass re-checks it.
- **Proof in the Lab.** New variant knob `useMap` (on/off). The same missions A/B, measuring success rate, turns, time
  and cost. Target: −30% turns and −30% time on mapped apps with no loss in success. Auto-learn becomes the default only
  if this holds.

---

## 7. The experience

### Phone
- **After every run** (task result card and mission card): **Learn** · **Save skill** · View details.
- **Brain → Outcomes, rebuilt**: cards with outcome, app icons, time, steps and whether it's learned (✓ Learned / Learn).
  **View details** is a real button. Select mode: tick 10 runs → **Learn 10**, which runs in the background with progress
  and a summary ("Learned 64 screens across Instagram, WhatsApp and Gmail").
- **Settings → App Maps**: app list with tier coverage, test identity per app, *Learn automatically from successful runs*
  (off in alpha.36), and a Start-mapping chip for phone-only owners.

### Glass (Minitap-class)
- **Apps**: every app on the phone (synced by `apps.list`, including 0% ones), with a tier coverage bar, status, last
  learned or mapped, and sort by most used or least mapped.
- **App board**: left to right, **Entry** (signed-out / register / sign-in) → **Home** → **base pages** → **depth**, like
  Minitap's journey layout. Dark doors glow as the frontier; stale rooms are amber; danger rooms are red. Click a room
  for the redacted frame, its doors, the runs that passed through and its evidence sources.
- **Start mapping panel**: identity, budget, focus, and Pause / Take control. A live cursor and log ("Opened Reels · 3
  new doors"), with cards spawning as `atlas.diff` streams in (the theater from 04).
- **Learn queue**: the Runs page gets a **Learn** button per run and *Learn selected*; each app shows "12 runs not
  learned yet".

---

## 8. Invariants (non-negotiable, guarded in CI)

- `PhoneToolExecutor` stays the only mutation path; mapping and `go_to` are ordinary phone actions with settle and
  re-observe.
- The Atlas never stores typed text, message contents, contact names, account identifiers or secrets. The live
  persona's facts are never learned from the mapping persona.
- Learn never writes a route from a failed or unverified step.
- Mapping never sends, posts, pays, deletes or changes security; creating an account needs the owner's approval, and
  CAPTCHAs and verification codes are the owner's.
- Owner pins beat everything.

---

## 9. Release plan

### alpha.36: Learn (the foundation)
| # | Work | Files |
|---|---|---|
| 36.1 | `MissionTrail` model + `MindTrailRecorder` listener, stored with the mission, bounded and redacted | `mind/learn/*`, `MindMissions.kt` |
| 36.2 | `MissionLearner` → Atlas (`LEARNED_RUN` evidence, tiers, scenarios, negative evidence, idempotent), journal fallback for old runs | `mind/learn/MissionLearner.kt`, `applearner/graphv2/AtlasIngestion.kt` |
| 36.3 | Learn queue: background learning of many missions, progress, summary | `mind/learn/LearnQueue.kt` |
| 36.4 | Save skill → owner recipe (template and proposed inputs, preferred route) | `market/*` |
| 36.5 | Brain → Outcomes rebuilt: Learn / Save skill / View details buttons, select-and-learn; the same buttons on the mission card | `ui/v32/CycloneV39BrainPage.kt`, `CycloneMissionPanel.kt` |
| 36.6 | Gateway ops `learn.run`, `learn.status`; Glass Runs: Learn per run plus Learn selected; Apps: tier coverage | `GatewayV5LearnAdapter.kt`, `v5_contract.py`, Glass |
| 36.7 | Tests: trail → Atlas golden tests (a fake Instagram session → base pages and routes), privacy guard, idempotency | tests, `scripts/ci/tests` |

**Exit:** the owner asks 10 ordinary Instagram questions, taps Learn on them, and Glass shows Instagram's Home, Reels,
Search, DMs and Profile with the doors between them. The run count and "learned" state agree on the phone and in Glass.

### alpha.37: Map-guided runs
Map card in the prompt, the `go_to(room)` tool with verified door walking, confidence feedback and stale marking,
Save-skill recipes running map-guided, the Lab `useMap` knob and an A/B on the core suite. **Exit:** a measured A/B;
auto-learn defaults on only if map-guided is not worse.

### alpha.38: Mapping missions
Explore-mode Mind missions by tier with frontier selection, a deterministic sweep executor for T2 doors, test identity
per app, Glass Start-mapping panel with the live theater and report, and the phone Start chip. **Exit:** a Standard
mapping of a real app with a test account reaches T0 and T1 at 100% and T2 at 60% or more within 30 minutes, with zero
consequential actions, and the report shows it.

### Later (Phase 4 of the marketplace)
App Packs: download a structure-only map of popular apps. Scenario library. Cross-phone learning on opt-in, structure
only.

---

## 10. How the studio would measure it

| Metric | Target by alpha.38 |
|---|---|
| Runs learned per active phone per week | ≥ 10 |
| Base-page coverage of the owner's 10 most-used apps | ≥ 90% |
| Map-hit rate (steps taken from the map without a model call) | ≥ 40% of steps in mapped apps |
| Turns and time on mapped apps vs unmapped (Lab A/B) | −30% / −30%, success not lower |
| Divergence rate of map steps (room didn't match) | < 10%, each one marks stale |
| Privacy incidents (free text found in the Atlas by the guard) | 0 |

---

## 11. Risks and how the plan handles them

| Risk | Handling |
|---|---|
| Fingerprints split one page into many (feeds change content) | Room identity uses `structuralKey` (layout, not content) + activity + landmark hash. The learner merges rooms with the same structure, and Glass lets the owner merge rooms (pin) |
| Apps A/B test their UI; maps rot | Evidence decays by age and version; divergences mark stale; `go_to` never replays blind |
| Map poisoning from bad runs | Only successful steps become routes; failures are negative evidence; many sightings beat one |
| Mapping cost | The hybrid (the model decides, the machine sweeps); budgets per tier; a cheaper model for explore mode is a Lab question |
| Infinite feeds and inboxes | Sample one item, then back; content is never mapped |
| Privacy | Structural labels only; the CI guard on the learner; redacted frames; test identity kept apart from the live persona |

---

## 12. Decisions for the owner
1. **Learn automatically** from successful runs: off in alpha.36 (explicit Learn), on after the alpha.37 A/B? *(recommended)*
2. **Test accounts**: will you create them yourself, or should Cyclone do the sign-up with your approval at the final
   submit? CAPTCHAs and SMS codes stay yours either way.
3. **Start with alpha.36 = Learn** (runs become the map) before dedicated mapping missions? *(recommended: it gives
   value from the first day, costs no extra model calls, and gives the mapping mission a head start)*
