/**
 * Live phone view over the gateway's video WebSocket (JPEG-first; H.264 when the gateway sends it).
 *
 * Profiles matter: `thumbnail` only watches, `focus` is the gateway's "a human is at the controls" stream.
 * Glass watches with `thumbnail` so Cyclone can keep working, and switches to `focus` only after Take control.
 */
import { mapPointerGesture } from "../core/coordinates.js";
import type { GatewayClient } from "../services/gateway.js";
import type { StreamProfile, StreamUiState, VideoRenderer, VideoRendererFactoryInput } from "../video/decoder.js";
import { WebCodecsH264Renderer } from "../video/webcodecsH264Decoder.js";
import { el } from "./dom.js";

export interface LiveGesture {
  type: "tap" | "swipe";
  x: number;
  y: number;
  x2?: number;
  y2?: number;
  durationMs?: number;
}

export interface LiveViewOptions {
  client: GatewayClient;
  deviceId: string;
  origin: string;
  onGesture(gesture: LiveGesture): void;
  onState?(state: StreamUiState): void;
  rendererFactory?: (input: VideoRendererFactoryInput) => VideoRenderer;
}

export interface LiveView {
  element: HTMLElement;
  setProfile(profile: StreamProfile): void;
  /** Pointer input reaches the phone only while the developer holds control. */
  setInteractive(interactive: boolean): void;
  state(): StreamUiState;
  destroy(): void;
}

const STATE_COPY: Record<StreamUiState, string> = {
  CONNECTING: "Connecting to the phone…",
  LIVE: "",
  RECONNECTING: "Reconnecting…",
  SLEEPING: "The phone screen is off.",
  STREAM_ERROR: "The live view hit an error. Retrying…",
  UNAVAILABLE: "Live view is unavailable. Check USB / ADB authorization on the phone.",
};

export function createLiveView(options: LiveViewOptions): LiveView {
  const element = el("div", "live-view");
  const canvas = el("canvas", "live-canvas");
  const image = el("img", "live-fallback");
  image.alt = "";
  image.hidden = true;
  const overlay = el("div", "live-overlay");
  overlay.setAttribute("role", "status");
  element.append(canvas, image, overlay);

  let profile: StreamProfile = "thumbnail";
  let renderer: VideoRenderer | null = null;
  let current: StreamUiState = "CONNECTING";
  let interactive = false;
  let pointer: { clientX: number; clientY: number; startedAtMs: number } | null = null;
  const factory = options.rendererFactory ?? ((input) => new WebCodecsH264Renderer(input));

  const setState = (state: StreamUiState): void => {
    current = state;
    overlay.textContent = STATE_COPY[state];
    overlay.hidden = state === "LIVE";
    element.dataset.state = state.toLowerCase();
    options.onState?.(state);
  };

  const start = (): void => {
    renderer?.stop();
    const socket = options.client.socket(`/v1/devices/${encodeURIComponent(options.deviceId)}/video?profile=${profile}`, options.origin);
    renderer = factory({
      device: { id: options.deviceId },
      profile,
      streamUrl: socket.url,
      streamProtocols: socket.protocols,
      fallbackUrl: "",
      target: { container: element, canvas, fallbackImage: image },
      callbacks: {
        onState: setState,
        onError: () => undefined,
        onDiagnostic: () => undefined,
      },
    });
    renderer.start();
  };

  element.addEventListener("pointerdown", (event: PointerEvent) => {
    if (!interactive || event.button !== 0) return;
    pointer = { clientX: event.clientX, clientY: event.clientY, startedAtMs: event.timeStamp };
  });
  element.addEventListener("pointerup", (event: PointerEvent) => {
    const start = pointer;
    pointer = null;
    if (!interactive || !start) return;
    const width = canvas.width || image.naturalWidth;
    const height = canvas.height || image.naturalHeight;
    const gesture = mapPointerGesture(
      start,
      { clientX: event.clientX, clientY: event.clientY, endedAtMs: event.timeStamp },
      (canvas.hidden ? image : canvas).getBoundingClientRect(),
      width,
      height,
      0,
    );
    if (!gesture) return;
    if (gesture.type === "tap") options.onGesture({ type: "tap", x: gesture.x, y: gesture.y });
    else options.onGesture({ type: "swipe", x: gesture.x1, y: gesture.y1, x2: gesture.x2, y2: gesture.y2, durationMs: gesture.durationMs });
  });

  setState("CONNECTING");
  start();

  return {
    element,
    setProfile(next) {
      if (next === profile) return;
      profile = next;
      start();
    },
    setInteractive(value) {
      interactive = value;
      element.classList.toggle("interactive", value);
    },
    state: () => current,
    destroy() {
      renderer?.stop();
      renderer = null;
    },
  };
}
