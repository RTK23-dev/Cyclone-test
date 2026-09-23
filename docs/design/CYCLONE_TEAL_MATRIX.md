# Cyclone Teal Matrix

Teal Matrix is Cyclone's single in-app visual language, introduced in 5.0.0-alpha.8. It started as the
Ask Cyclone capsule's material and now applies to every screen.

## Principles

- **One palette.** Deep teal canvas, teal ink, mint for success, coral for attention. A light device
  theme no longer produces a white/blue variant. The palette lives in `SignatureScheme` and
  `TealMatrix` (`ui/v32/CycloneSignatureGlass.kt`, `ui/v32/CycloneTealMatrix.kt`).
- **One material.** Cards, trays, tiles and the tab bar are the same translucent teal glass as
  the Ask Cyclone capsule (`CycloneSignatureGlass`), at a calmer intensity (no dotted whorls).
- **Quiet canvas.** `TealMatrixBackdrop` draws a layered gradient, two aurora blooms and diagonal
  ribbons of dots. It is cached per size and has no animation loop.
- **State by tint, not by layout.** `MatrixTone` changes the glass rim: coral when Cyclone needs
  you, mint when it's done, bright teal while it's working. Copy stays equally readable.

## Components

| Component | Use |
| --- | --- |
| `CycloneSignatureGlass(accent, focused)` | Ask Cyclone capsule and base material. `focused` lights the rim; a specular top sweep and a bottom bloom make it read as lit glass. |
| `CycloneMatrixCard(tone)` | Any content card. `CycloneSimpleCard`, `CycloneSurface`, `CycloneGlassSurface` and `CycloneHeroCard` all use it. |
| `CycloneMatrixAppBar` | Menu or back, centered Cyclone word mark, spiral mark. |
| `CycloneMatrixQuickAction` | Home quick actions (icon tile + label). They prefill the Ask bar; the user still sends. |
| `CycloneMatrixCheck` / `CycloneMatrixRing` / `CycloneMatrixAttention` | Done, running and needs-you markers for list rows. |
| `CycloneMatrixSectionHeader` | Section title with an optional teal action ("See all"). |

## Home

Centered greeting, four quick actions, the live task card, **Recent activity**, routines, and one
Ask Cyclone capsule pinned above the tab bar.

Recent activity (`CycloneRecentActivity`) is **in memory only**. Task goals can contain personal
text, so it is never written to disk, Brain or diagnostics. User-stopped tasks are dropped.

## Status

Verified by unit tests and by rendering the Home, task-card and Settings screens offline
(Robolectric, native graphics). **Not verified on a physical phone.**
