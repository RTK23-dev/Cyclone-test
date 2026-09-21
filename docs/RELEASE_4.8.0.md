# Cyclone Mobile 4.8.0 — destination authority + honest Human Gesture

Android **4.8.0** / versionCode **140**, based on published **4.7.9**. Candidate on `agent/cyclone-4.8.0-destination-authority`. **Not publication-authorized** until Mobile CI signs this SHA and a Pixel 8 matrix is recorded.

This cut is one product: a Gmail → Facebook-in-Chrome ask, or any other AI driving the phone, must leave the first app, land a real finger-like stroke on the host (not the overlay ball), wait until that stroke completes, and use the same cubic path on a named virtual display.

## 1. Destination-scoped execution

4.7.9 polished the Ask card. It still compiled a multi-app request as one Gmail SCENE with whole-goal `goal_contract`, so the executor never left Gmail and `FastPathLanding.resolve(goal)` kept re-opening the first named app.

4.8.0 makes the trajectory the execution authority:

- Destinations keep their own until-conditions. "Find the signed-in Gmail address" completes only when a unique email is visible.
- Facebook named inside Chrome is a host landing (`https://facebook.com` in Chrome), not `com.facebook.katana`.
- The current waypoint chooses `phone.open_app` / `phone.launch_intent`. Whole-goal resolve is not used after the plan is set.
- A login wall on the current destination is `NEED_HUMAN`. Gmail being signed in cannot skip Facebook's password wall.
- Several visible Gmail accounts ask which one to use. The raw address stays in run memory; the card only sees a masked form.

## 2. Gesture completion

`dispatchGesture` returning true only queued the stroke. 4.7.8 waited for `GestureResultCallback`, then treated timeout as success so a retry would not double-tap. Every AI then saw `ok` and fired the next action.

Timeout / cancel / not-queued are **not performed**. `PhoneToolExecutor` will not retry a gesture that already crossed the dispatch boundary. CIP maps `TIMEOUT` to `UNCERTAIN` / `NEVER_RETRY_MUTATION`. CIP tap / long-press / scroll / swipe send `humanize=auto`.

## 3. Overlay yield (the ball)

`FLAG_NOT_TOUCHABLE` on `TYPE_ACCESSIBILITY_OVERLAY` does not stop Android from delivering host gestures to Cyclone chrome. The idle 48dp ball and the expanded Ask card sat in the accessibility hit tree, so swipe/scroll landed on Cyclone instead of Gmail or Chrome.

During an authorized host stroke those windows go **GONE**, drop important-for-accessibility, keep `FLAG_NOT_TOUCHABLE`, and wait one WindowManager frame before `dispatchGesture`. They return after `onCompleted`. Overlay buttons still never click host nodes.

## 4. Named virtual display cubic path

Workspace sessions injected `/system/bin/input -d <id> tap|swipe` (start/end/duration) and reported Human Gesture anyway. That is not a finger.

Workspace taps, long-presses, scrolls and swipes now use the same `HumanGestureDispatch` cubic `GestureDescription`, targeted with `Builder.setDisplayId`. Lease / generation / GATE / stale observation still run first. Shell `input` remains for Back and typing only. A named display that does not queue the gesture fails closed; it does not silently fall back to the shell line.

Capabilities now report named virtual display `cubicPath=true` / `humanGesture=true` / `accessibility_dispatch_gesture`.

## Unchanged

GATE, MutationGrounding, Fast Path Unchanged, Instagram `humanize=off`, overlay buttons never clicking host nodes, Gateway/MCP package version **4.1.0**.

## Validation

- Destination-scoped waypoints, Gmail email evidence, ambiguous accounts, Facebook-in-Chrome login handoff
- CIP `humanize=auto`, CIP timeout → `UNCERTAIN`
- Overlay host-yield source contract (GONE + not important-for-accessibility + committed frame)
- Timeout is not success; incomplete gestures are not retried
- Named VD capabilities are cubic; click serialization does not invent endpoint-duration
- CIP unit tests passed in this cut

Physical Pixel 8 remains **UNVERIFIED**. Publication stays false until Mobile CI on this SHA and a later Pixel matrix.

## Operator cut (after CI)

Do not invent a one-off publish workflow.

1. Merge this branch; push `release/cyclone-mobile-v4.8.0` from the merged SHA.
2. Wait for **Cyclone Mobile CI** success. Copy `build_run_id` and artifact name (`Cyclone-Android-4.8.0`).
3. Dispatch **Cyclone Mobile Release Signing** with those inputs.
4. `gh release create v4.8.0 --title "Cyclone 4.8.0" --notes-file docs/RELEASE_4.8.0.md` attaching the signed APK. Never overwrite an existing tag.
