# 04 — App Maps canvas (Glass operator board)

The PC is the **operator table**. The phone is the **hands**.

Glass Maps V1 is a **Minitap-class full mapping view**: dotted board, cards, edges, zoom, filters, inspector. The operator must get a look and feel of every mapped app — not a list of package names.

If they cannot pan the whole Gmail house on a monitor and explain the rooms from the canvas alone, this page is not done.

## Operator bar (copy Mini’s *feel*, not their QA objects)

MiniTest’s demo worked because you could **see the house**: dotted board, cards, edges, zoom, click a room, know if it was healthy. Glass must do that for **screens and doors**.

| Mini (miniTest demo) | Cyclone Glass Maps |
|---|---|
| App switcher (Demo App) | Place switcher: Gmail, Chrome · facebook.com, … |
| Tabs: SCENARIOS / PERSONAS | **Screens** / **Capabilities** / **Live vs mapping** |
| Journey cards (“Register a New Account”) | Screen cards (purpose, coverage, stale/blocked) |
| Edges = story order | Edges = **doors** (“open avatar”, “Create account”) |
| Passed / warning / critical | Mapped / stale / blocked (secret) / danger (pay) |
| Click → recording + acceptance criteria | Click → redacted frame + slots + doors + last proof |
| Mini orb while authoring | Live **mapping cursor** on the node being walked |
| Filters | Region, status, danger, Chrome vs native |
| 4/12 bar | Doors mapped / still dark |
| “Watch Mini author one now” | Start mapping from the board; cards spawn live |

## Layout

```text
┌──────────┬──────────────────────────────────────┬─────────────────┐
│ Places   │  Board (dotted grid, pan / zoom / fit)│ Inspector      │
│ catalog  │                                      │ (sheet)        │
│          │   [Account] [Inbox] [Chat] [Settings] │ purpose        │
│ Gmail    │      ●────────●────────●              │ redacted frame │
│ Chrome · │                                      │ slots, doors   │
│  fb.com  │   coverage: 18 screens · 41 doors    │ pin / remap    │
│ …        │                                      │                │
└──────────┴──────────────────────────────────────┴─────────────────┘
 Top: place name | Live/Dummy | Start mapping | filters | Take control
```

### Left rail — All places

- Every launcher app on **this** phone + Chrome origins.
- Status chip: Not mapped · Mapping… · Mapped · Stale · Blocked.
- Native missing: row still exists as `Chrome · facebook.com`.
- Search. Mapped-only filter.
- One tap focuses that atlas on the board.
- Device picker stays the existing fleet control: the board is **this phone’s** atlas. No merged-fleet graph in V1.

### Center — the board

- Infinite dotted grid. Pan, scroll/pinch zoom, `+` `−` fit.
- Cards clustered by region: Account · Inbox · Compose · Chat · Settings · Danger.
- Card shows: purpose title, 2–3 landmark labels, status color, capability glyphs.
- Edge lines (bezier or ortho) with English on hover: *open account photo*. Confidence as opacity.
- Fit-all on open so the operator gets the shape in one glance.
- Empty place: one card **Start mapping** (not a blank grid).
- Operator may drag to tidy. Layout persists **on the phone atlas** so phone and Glass agree.

### Right / sheet — inspector (Mini’s scenario drawer)

Open a **screen**:

- Purpose (account-switcher, dm-list, login, signup, …)
- Last redacted snapshot (vault fields stripped on the phone before the JPEG leaves)
- Fact slots: *signed-in email (which row is current)* — no live address in the operator UI unless they expand a **masked** value
- Doors: unmapped ones marked dark
- Last verified, confidence, app version
- Actions: **Pin** (“this is DMs”), **Remap this room**, **Never** (payment)

Open an **edge**: last success/fail, English action, hint target (text / id). No password as the headline. No xpath as the headline.

### Top bar

- Place name + persona toggle **Live / Dummy** (two atlases, never mixed on one board)
- Coverage: `18 screens · 41 doors · 3 dark`
- **Start mapping** / Pause / Take control
- Filters: stale, blocked-secret, danger, unmapped doors, Chrome vs native
- View toggle: Screens (default) · Capabilities (index of what this place is good for)

## Live mapping theater

JPEG live stays available on **Phone**. Maps board **animates**:

- Current node pulses (`mapping.status.nodeId`)
- New cards spawn, new edges draw from `atlas.diff`
- Blocked node (needs secret): board dims, **Secrets card** on Glass, crawl resumes, node flips mapped
- Operator can sit on Maps and *feel* the crawl — Mini’s “watch it author one now” beat

Pause / Take control uses the existing One handoff (`HUMAN_HAS_CONTROL`). Teach from the desk: take the mouse, tap the account header, pin “signed-in email,” give back.

## Phone vs Glass

| Surface | Canvas |
|---|---|
| Mobile Settings → App Maps | Catalog + **small** graph + Start / peek chip |
| Glass → Maps | **Full board.** This is the look-and-feel product |

Do not duplicate the monitor on a phone. Do not ship Glass Maps as a settings list.

## Sync (60fps pan, phone is truth)

```text
atlas.places
atlas.get(placeId, persona)     → full graph + layout + redacted thumbs
atlas.diff(placeId, since)      → live crawl
mapping.status                  → current node id (cursor)
mapping.start | pause | stop
```

Glass holds a local replica for pan/zoom. Diffs apply in place so the board does not rebuild from scratch every edge.

4.8 phone: Maps shows **Update Cyclone on the phone**, not a fake graph.

## Build tasks (G2)

| ID | Task | Ship |
|---|---|---|
| G2.1 | Board engine | `mapsPage.ts` + `appMapCanvas.ts`: pan/zoom/fit, dotted grid, region layout |
| G2.2 | Node cards + edges | Purpose, status color, capability icons, hover English, confidence opacity |
| G2.3 | Layout | Seed by region; persist operator drags on the phone atlas |
| G2.4 | Atlas sync | `atlasClient.ts` replica + diff apply |
| G2.5 | Inspector | Redacted still, slots, doors, proofs as English, pin/remap/never |
| G2.6 | Catalog + filters | Places, search, Chrome vs native, personas, status |
| G2.7 | Live mapping | Cursor, spawn, secrets interrupt, Take control on the same bar |
| G2.8 | Empty / loading / stale | Honest copy, one primary action |
| G2.9 | Phone small canvas | List + mini-graph only — must not block Glass |

## Exit tests

**alpha.2 (read-only board):** operator opens Glass → Maps → Gmail (after a Follow Me or first atlas) and can **explain the app’s rooms from the canvas alone** (where identity lives, where inbox is, what’s unmapped). If they still need logs, the canvas failed.

**alpha.3:** Start mapping from the board; live cursor; diffs spawn cards; secrets card pauses then resumes.

**rc:** Chrome places, persona split, coverage bar, pin/remap room.

**Glass 1.0:** Encrypted secrets resume on the same board; stale/refresh in the catalog.

## What not to copy from Mini

- Acceptance-criteria **as the graph**. Those are Ask proofs, shown in the inspector, not the nodes.
- “Passed 8/8” as if mapping were a test suite. Mapping is coverage of **doors**, not product QA.
- A Mini orb that *is* the agent. Cyclone’s agent is on the phone; the pulse is just the cursor.
- Rapid-fire along edges from the pretty picture.
- Storing dummy emails as the signed-in identity of the live persona.

The board is how **you** see the atlas. The phone still **looks** when it walks.
