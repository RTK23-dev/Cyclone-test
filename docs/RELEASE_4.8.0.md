# Cyclone Mobile 4.8.0 — destination-scoped trajectory + gesture completion

Android versionCode 140, based on published 4.7.9. Not publication-authorized.

4.7.9 polished the Ask card. It did not change how a multi-app request is executed. A Gmail → Facebook-in-Chrome ask still compiled Gmail's SCENE as whole-goal `goal_contract`, so the executor never left Gmail and `FastPathLanding.resolve(goal)` kept re-opening the first named app.

4.8.0 makes the trajectory the execution authority:

- Destinations keep their own until-conditions. "Find the signed-in Gmail address" completes only when a unique email is visible. Opening Gmail is not enough.
- Facebook named inside Chrome is a host landing (`https://facebook.com` in Chrome), not `com.facebook.katana`.
- The current waypoint chooses `phone.open_app` / `phone.launch_intent`. Whole-goal resolve is not used after the plan is set.
- A login wall on the current destination is `NEED_HUMAN` (`trajectory.login_wall`). Gmail being signed in cannot skip Facebook's password wall.
- Several visible Gmail accounts ask which one to use. The raw address stays in run memory; the card only sees `a***@gmail.com`.

Human Gesture completion (any AI path):

- `dispatchGesture` returning true only means Android queued the stroke. Success is `GestureResultCallback.onCompleted`.
- Queue timeout is no longer treated as success. The tool returns `TIMEOUT`, CIP reports `UNCERTAIN` / `NEVER_RETRY_MUTATION`, and PhoneToolExecutor will not fire a second click on the same observation.
- CIP tap/long-press/scroll/swipe now send `humanize=auto` so ChatGPT/Codex get the same Human Gesture path as on-device Ask.
- GATE, MutationGrounding, Fast Path Unchanged, and Instagram `humanize=off` are unchanged.

Gateway/MCP remain 4.1.0 at the package version; CIP and gesture projection consume the mobile completion contract. Physical Pixel 8 remains UNVERIFIED.

## Validation

Unit tests cover destination-scoped waypoints, Gmail email evidence, ambiguous accounts, Facebook-in-Chrome login handoff, CIP `humanize=auto`, CIP timeout → UNCERTAIN, and source contracts that timeout is not success. Publication stays false until Mobile CI and a later Pixel matrix.
