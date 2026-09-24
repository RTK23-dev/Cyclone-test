# Cyclone Glass (`apps/glass`)

Cyclone Glass is the **developer's eyes on the engine that runs in Cyclone Mobile**: a local website on this PC that shows what
Cyclone knows (apps, maps, versions), what it did (runs), and lets you control the phone. It has **no intelligence of its own**:
no agent loop, no model calls, no API keys. Charter: [`Cyclone V5 plan/03-glass-v1.md`](../../Cyclone%20V5%20plan/03-glass-v1.md).

## Run it

**Windows with Cyclone One installed:** Cyclone One → Settings → **Open Cyclone Glass**. One's own runtime serves Glass and
opens your browser with a one-time link; nothing else to install.

**From a checkout (any OS):**

```bash
python -m pip install -e 'apps/device-gateway[test]'
cd apps/glass && npm ci && npm run build          # builds dist/, which the gateway serves at /glass/
cyclone-device-gateway glass                      # starts the local gateway if needed and opens the browser
```

`cyclone-device-gateway glass` mints a one-time launch code and opens `http://127.0.0.1:<port>/glass/#code=…`. The page exchanges
the code for a session that lives only in that tab. A new tab needs the launcher again.

## Connect a phone (Devices)

Plug the phone in by USB and allow USB debugging. In Glass, **Devices** lists every phone this PC sees:

- **Ready to connect** → **Connect**. Glass shows a six-digit code; the phone shows a "Connect this PC?" notification with
  the same code. Open it, check the code, tap **Allow**. The phone and the PC keep the trust; Glass never stores it.
- **Connected** → **Use in Glass**, **Reconnect** (when the phone was locked or restarted), **Disconnect** (asks first).
- **Needs the phone** → allow USB debugging or reconnect the cable.

Glass opens on Devices whenever no phone is ready. Pay, send, delete and sign-in steps still ask on the phone.

Development with hot reload: `npm run dev` (Vite on `127.0.0.1:5178`, proxying `/v1` to `CYCLONE_GATEWAY_URL`, default
`http://127.0.0.1:8765`). Open the launch link the CLI prints with `--print-url` and swap in the dev port.

## Layout

| Path | What |
|---|---|
| `src/app.ts` | Shell: sidebar, phone picker, one mounted page |
| `src/core/` | Router (hash routes), session bootstrap (launch code → tab session) |
| `src/services/` | Gateway client (same origin, bearer, error normalisation), devices, phone-owned data clients |
| `src/ui/` | DOM helpers, icons, the component set every page uses |
| `src/pages/` | One file per page; pages compose components and never restyle them |
| `src/styles/` | `tokens.css` (the only place colours, spacing and type are defined), base, components, shell |
| `tests/` | `node --test` against compiled `.test-dist`, DOM via `tests/helpers/mini-dom.mjs` |

## Rules

- Phone thinks and mutates. Glass shows, inspects and sends commands through the gateway.
- No secret values on the wire, in the page, in `localStorage` or in logs. The session bearer lives in memory + `sessionStorage` for one tab.
- One design system. New UI uses `ui/components.ts` and `tokens.css`; no page-level colour values.

```bash
npm test          # unit + DOM tests
npm run build     # typecheck + production bundle
```
