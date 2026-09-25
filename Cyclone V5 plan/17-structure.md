# Cyclone structure — building blocks with contracts

**Status:** phase 1 (Task Kit) built in 5.0.0-alpha.29.dev1. Later phases proposed.

## Why

Measured on alpha.28: 480 Kotlin files (~96,500 lines), **5** task engines (Cyclone Mind, classic adaptive agent,
quick agent, local agent, background workspace service), 9 files that start or command tasks, owner-facing
prompts spread over ~18 files, **9** notification builders, 5 theme wrappers and 10 design-system files, 88 raw
`Card`/`Surface` uses outside a shared component set.

The alpha.27 "I'm done does nothing" bug came from exactly that shape: a button (surface) sent a string command
through a background-workspace service (engine 5), which forwarded it to the classic agent's resume path (engine 2),
for a task owned by the Mind (engine 1). No single piece was wrong; nothing owned the meaning of "I'm done" for that
task. With 5 engines × 5+ surfaces × 6+ commands, gaps like this are guaranteed unless there is one contract.

## Principle

Like a storefront platform's sections and blocks: every element has one definition and one contract; pages only
assemble elements; guards stop drift. Nothing is rewritten at once: the new structure grows next to the old, one
path moves at a time, and an old path is removed only when the new one is proven (strangler pattern). Every phase
ships on its own.

## The four layers

| Layer | One owner for | Contract |
|---|---|---|
| **Task Kit** (core) | what a task is and what the owner can tell it | `TaskEngine`, `TaskCommand`, `TaskController`, `TaskCommands` bus |
| **Owner Moments** (core) | every time Cyclone needs the owner: question, values, secret, approval, takeover | one inbox, one request model, engines never draw UI |
| **Cyclone Kit** (design) | how everything looks | tokens (colour, type, spacing, shape, motion) shared with Glass; primitives; blocks (OwnerCard, TaskCard, ApprovalCard, SecretsCard, MissionTimeline) |
| **Surfaces** | where the owner sees it | overlay, app, notification, lock screen, Glass — they compose blocks and send commands |

Import direction: surfaces → kit/core; engines → core; core → nothing above it. Engines never import UI.

## Phases

| Phase | Release | Contents | Proof |
|---|---|---|---|
| 1 Task Kit | alpha.29 | typed commands, engine per task, one bus, controllers for Mind / classic / background, every entry point rerouted, trace record | bus tests, engine × command contract matrix, CI guard against bypassing the bus |
| 2 Owner Moments | alpha.30 | classic agent and background workspaces publish takeover / secret / confirmation into the same inbox; one card family everywhere; actionable notifications (reply, approve, decline inline) | contract test: every request kind renders on every surface and every answer reaches its engine |
| 3 Cyclone Kit | alpha.31 | tokens + primitives consolidated; overlay, Ask and task cards moved first; token file shared with Glass | guard: no new raw `Card`/`Surface`/`Notification.Builder` outside the kit |
| 4 One notification renderer | alpha.32 | notifications derived from task + owner-moment state | guard on builders |
| 5 Boundaries | ongoing | import-direction guard, later Gradle modules (`:core:task`, `:core:owner`, `:kit`, `:engine:mind`, `:engine:classic`, `:device`); giant files split when touched | guard script |
| 6 Engine consolidation | after evals | background workspaces on the Mind; older engines retired only when evals show no loss | eval suite |

## Phase 1 as built

- `task/TaskKit.kt` (pure): `TaskEngine`, `TaskCommand` (Stop, TakeOver, Pause, Done, Autofill, Confirm, Approve,
  Decline) with legacy wire names, `TaskController`, `TaskEngines.of`, `TaskCommandBus`.
- `task/TaskCommands.kt` (Android): the bus with `MindTaskController`, `ClassicForegroundTaskController`,
  `BackgroundWorkspaceTaskController`; notification `PendingIntent`s; the private `TaskCommandReceiver`; every command
  and outcome in the run trace as `TASK_COMMAND`.
- Rerouted: `WorkspaceTasks.command`, the workspace service's foreground forward, overlay take-control, notification
  actions, the progress screen's confirm, the Ask mission panel's Stop, the owner card's I'm done.
- `WorkspaceTaskUi.engine` records the owning engine; Mind missions set it explicitly.
