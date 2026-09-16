# Cyclone Mobile 4.6.1

Android versionCode 121; builds on published 4.6.0.

A leftover Layer-2 workspace lease could turn a correct `phone.open_app` (Facebook, and any other installed app) into `CAPABILITY_UNAVAILABLE`, then into a terminal HARD_BLOCKER after one action. That is why “open Facebook and login” died in ~10s with no recovery observation.

## What 4.6.1 changes

- Ask Cyclone / default-foreground tasks release a leftover Layer-2 lease before mutating. Armed workspace jobs stay queued.
- Scope failures keep a typed code (`WORKSPACE_SCOPE_CONFLICT`, `TARGET_SCOPE_MISMATCH`, `ACTION_FAILED`) instead of collapsing into `CAPABILITY_UNAVAILABLE`.
- Generic capability misses are retryable recovery incidents. Only a policy denial or a tool the model invented (`not exposed`) is a hard blocker.
- After a failed mutation Cyclone still re-observes the same session, unless Accessibility is down or the human owns input.

“open Facebook and login” should now land Facebook, then continue into login autofill. If Facebook is not installed, the executor still reports `APP_NOT_FOUND` and recovery can use the website.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates. Physical Pixel 8 remains UNVERIFIED.
