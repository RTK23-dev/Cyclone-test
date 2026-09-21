# 03 — Cyclone Glass 1.0

One today is an **operator console**: fleet, JPEG live, MCP, ChatGPT Attach, camera. That remains.

Glass V1 is the **human HUD** for V5. Same Tauri app (`apps/pc-companion`), new product surface — not a second `PhoneToolExecutor`.

The Maps board is specified in full in [04-app-maps-canvas.md](04-app-maps-canvas.md). This doc is the rest of the PC product.

## G0 — Identity

- Brand the window **Cyclone Glass** (keep One installer path one release if you must; doctor should say Glass).
- Pairing unchanged: QR / four-letter, loopback gateway.
- **Require mobile ≥ 5.0.** Refuse atlas APIs on 4.8 phones with a clear “update the phone.”
- Session planes stay labeled: foreground · named VD · Layer 2. Mapping and Ask declare which plane.

Installer still lands in `%LOCALAPPDATA%\Cyclone One` for one cut if that avoids a second product fighting MCP paths. Doctor reports the Glass name and the required mobile version.

## G1 — Shell

Today (`app.ts`): Home, Control, Tasks, Connections, ChatGPT, Settings.

Glass V1:

| Nav | Job |
|---|---|
| **Phone** | Focused live (JPEG), Take control / Give to AI — existing `focusedPhonePage` / `livePhoneView` |
| **Ask** | Same run as the overlay. Type a goal on the PC. HUD + stages + logs download |
| **Maps** | **Full Minitap-class board.** Place catalog, canvas, inspector, Start mapping. See [04](04-app-maps-canvas.md) |
| **Vault** | Slot inventory only. “Facebook password: set. Dummy Gmail: set.” Never values |
| **Tasks** | Runs, mapping jobs, needs-secret badges |
| **Connections** | MCP / ChatGPT — existing |
| **Settings** | Pairing, camera, autonomy — existing + Maps defaults (budget, never-pay) |

Don’t dump Maps into Settings. It’s a primary verb.

New files (indicative):

```text
apps/pc-companion/src/pages/askPage.ts
apps/pc-companion/src/pages/mapsPage.ts
apps/pc-companion/src/pages/vaultPage.ts
apps/pc-companion/src/ui/appMapCanvas.ts
apps/pc-companion/src/ui/secretsCard.ts
apps/pc-companion/src/services/atlasClient.ts
apps/pc-companion/src/maps.css
```

## G2 — Maps on a big screen

**This is a Glass 1.0 exit criterion, not a polish item.** Full spec: [04](04-app-maps-canvas.md).

Summary:

- Catalog synced from the phone (`atlas.places`).
- Canvas = phone atlas. Click node → purpose, slots, last **redacted** frame.
- **Start mapping** sends `mapping.start`; Glass **watches**. Board animates; JPEG can sit beside it.
- Pause / teach: Take control, tap the account header, “this is signed-in email,” Give back.
- Chrome place: “Map facebook.com in Chrome” even if katana missing.
- Glass does not crawl. It **commands and displays**. The mapper process is Android.

## G3 — Secrets on a keyboard (without a PC vault)

Three legal paths. V1 ships 1+2, or **only 1** if 2 cannot be proven leak-free.

1. **Phone card** — Glass shows “Waiting for password on the phone.”
2. **Glass card, phone storage** — type on the PC; payload is a **one-shot encrypted fill** over the pairing channel; phone vault stores; Glass memory wiped; not in logs, not in `localStorage`, not in MCP.
3. Later: OS password-manager handshake. Not V1.

A secrets card that writes `Documents/password.txt` is a product failure. See [05](05-secrets-vault.md).

## G4 — Ask from Glass

- Composer on PC → `ask.start` on phone with `session_id`.
- HUD is the **same presentation snapshot** 4.8 already projects (stages, needs-input, run information).
- Take control anytime (existing handoff). Mapping and Ask both honor `HUMAN_HAS_CONTROL`.
- Multi-phone: Ask and Maps are **per device**. No “one atlas for the fleet” unless we later add explicit copy.

## G5 — Gateway / MCP 5.0

See [08](08-protocol-gateway.md). Mapping is a **local** operator act. Remote MCP stays readonly by default.

## G6 — Keep from One 1.5.5

JPEG-first live, `HUMAN_HAS_CONTROL`, `PHONE_LOCKED`, session tiles, ChatGPT Attach, camera fan-out, doctor, pairing. Don’t regress fleet. Glass V1 is extra pages + extra gateway ops, not a rewrite of `livePhoneController`.
