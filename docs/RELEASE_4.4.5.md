# Cyclone Mobile 4.4.5 — Visual repair and overlay stability

Cyclone Mobile **4.4.5 / versionCode 105** is built directly on the published 4.4.4 release. It keeps the existing phone-control, task, workspace, camera-streaming, security, recovery and consent architecture while repairing the visual and runtime issues found during the 4.4.4 Liquid Glass rollout.

## Ask Cyclone overlay

- Rebuilds the resting overlay around one 66dp dark Liquid Glass composer capsule instead of a large stacked sheet.
- Keeps the host application unobscured: the accessibility overlay is content-sized and deliberately does not create a full-screen Kyant backdrop.
- Uses a dedicated tools menu for Camera, Files & photos, Share screen, Cross-app share and Model & intelligence.
- Adds explicit send, voice and stop semantics and preserves swipe-to-minimize through an invisible grab zone.
- Adds safe non-Kyant fallbacks for backdrop-free overlay controls so Material actions remain visible and interactive instead of crashing when the overlay correctly has no local backdrop.

## Liquid Glass hierarchy and controls

- Reduces nested/double-glass appearance by using lighter shared optics and inset selection lenses.
- Rebalances bottom navigation, segmented controls, toggles, search and routine controls around one optical owner per interaction region.
- Keeps content cards as quiet Material surfaces instead of turning the entire interface into glass.
- Preserves the Kyant0/AndroidLiquidGlass Backdrop 1.0.0 + Capsule 2.1.1 renderer and the existing upstream-style LiquidButton optical recipe.

## AI, model and reasoning stability

- Removes backdrop-dependent controls from `DropdownMenu` popup windows and keeps model, intelligence and reasoning controls in the same Compose/window hierarchy.
- Bounds long model pickers with scrolling so expanded controls cannot grow beyond a practical phone viewport.
- Keeps exact OpenRouter reasoning selections and phone-autonomy controls intact.

## Home, Profiles, Routines and Settings cleanup

- Compacts the Home Ask Cyclone launcher and removes duplicate Settings/readiness actions.
- Rebuilds Profiles hierarchy around the active profile rather than competing top-level actions.
- Simplifies Routines organization to Apps / Categories / All and replaces the mismatched popup creation dialog with an in-layout Liquid panel.
- Moves affected Routine Detail, Quick Setup and Settings actions into the same coherent interaction language.

## Release and compatibility safety

- Minimum SDK remains 33, target SDK remains 35 and compileSdk remains 36.
- Kotlin/Compose compiler and AGP compatibility remain unchanged from 4.4.4.
- Cyclone One 1.5.5 compatibility and the secure native-aspect camera viewer are preserved.
- Mobile CI continues to run repository/security guards, Gateway/MCP contracts, unit tests, Android lint and unsigned release assembly before publication.
- Both Mobile CI and the full release signer now explicitly request supported Android SDK packages instead of relying on the retired aggregate `tools` package.
- Publication is allowed only from the exact release-branch SHA after checksum/provenance validation and signer continuity with the published 4.4.4 APK succeed.

Physical-device Liquid Glass visual/interaction acceptance remains **UNVERIFIED** until tested on real hardware. This release does not claim hardware acceptance that has not occurred.
