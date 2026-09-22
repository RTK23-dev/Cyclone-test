import "../ask.css";
import {
  ASK_NEEDS_SECRET_FIXTURE,
  ASK_RUN_STATES,
  ASK_SAMPLE_SNAPSHOTS,
  GLASS_UPDATE_PHONE_COPY,
  GLASS_UPDATE_PHONE_TITLE,
  phoneSupportsGlassAtlas,
  type AskRunState,
  type GlassAskSnapshot,
} from "../core/fleet.js";
import type { DesktopDevice } from "../services/types.js";
import { button, el } from "../ui/dom.js";
import { createSecretsCard } from "../ui/secretsCard.js";

export interface AskPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export interface AskPageOptions {
  devices?: DesktopDevice[];
  mobileVersion?: string;
  /** Immediately render the needs-secret fixture (tests / demo, no phone required). */
  previewNeedsSecret?: boolean;
  onOpenControl?: () => void;
}

export function createAskPage(options: AskPageOptions = {}): AskPageHandle {
  const devices = options.devices ?? [];
  const atlasReady = phoneSupportsGlassAtlas(options.mobileVersion ?? "");
  let snapshot: GlassAskSnapshot | null = options.previewNeedsSecret ? ASK_NEEDS_SECRET_FIXTURE : null;

  const page = el("section", "page content-page ask-page");
  const header = el("header", "page-header");
  const heading = el("div");
  heading.append(
    el("div", "ask-kicker", "CYCLONE GLASS · ASK"),
    el("h1", "page-title", "Ask"),
    el("p", "page-subtitle", "Same phone run as the overlay. Glass commands and displays; the phone executes."),
  );
  const control = button("Take control on Phone", "button secondary compact");
  control.disabled = !options.onOpenControl;
  control.addEventListener("click", () => options.onOpenControl?.());
  header.append(heading, control);

  const banner = el("aside", "glass-compat-banner");
  banner.hidden = atlasReady;
  banner.append(
    el("div", "glass-compat-title", GLASS_UPDATE_PHONE_TITLE),
    el("p", "glass-compat-copy", GLASS_UPDATE_PHONE_COPY),
  );

  const composer = el("article", "ask-composer-card");
  composer.append(el("h2", "ask-section-title", "Goal"));
  const form = el("form", "ask-composer-form") as HTMLFormElement;
  const textarea = el("textarea", "ask-composer-input") as HTMLTextAreaElement;
  textarea.rows = 3;
  textarea.placeholder = "e.g. Check Facebook login status";
  textarea.setAttribute("aria-label", "Ask goal");
  textarea.autocomplete = "off";
  const send = button("Send to phone", "button primary ask-send");
  send.type = "submit";
  send.disabled = true;
  form.append(textarea, send);
  const formStatus = el("p", "ask-form-status", atlasReady
    ? "Send stays off until the phone Ask transport is connected. Glass does not run goals on this PC."
    : "Ask runs on Cyclone Mobile 5. Glass does not execute goals and will not invent a PC executor.");
  composer.append(form, formStatus);

  const hud = el("section", "ask-hud-host");
  hud.setAttribute("aria-live", "polite");

  const demo = el("div", "ask-demo-bar");
  const demoLabel = el("span", "ask-demo-copy", "Sample snapshot (no phone required)");
  const demoSelect = el("select", "ask-demo-select") as HTMLSelectElement;
  demoSelect.setAttribute("aria-label", "Sample Ask snapshot");
  const idleOption = document.createElement("option");
  idleOption.value = "";
  idleOption.textContent = "No run";
  demoSelect.append(idleOption);
  for (const state of ASK_RUN_STATES) {
    const option = document.createElement("option");
    option.value = state;
    option.textContent = sampleLabel(state);
    demoSelect.append(option);
  }
  if (options.previewNeedsSecret) demoSelect.value = "needs-secret";
  demo.append(demoLabel, demoSelect);

  const footer = el("p", "ask-footnote", "Take control remains the existing Phone live handoff. Mapping and Ask both honor HUMAN_HAS_CONTROL.");

  page.append(header, banner, composer, hud, demo, footer);

  const paintHud = (): void => {
    if (!snapshot) {
      const empty = el("div", "ask-hud-empty");
      empty.append(
        el("h2", "ask-hud-empty-title", atlasReady || devices.length === 0
          ? "No Ask run yet"
          : GLASS_UPDATE_PHONE_TITLE),
        el("p", "ask-hud-empty-copy", atlasReady
          ? "Type a goal when Mobile 5 can start the same overlay run. Use a sample snapshot to preview HUD states."
          : GLASS_UPDATE_PHONE_COPY),
      );
      hud.replaceChildren(empty);
      return;
    }
    hud.replaceChildren(renderAskHud(snapshot));
  };

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    formStatus.textContent = "Glass does not execute this goal on the PC. Ask lives on the phone.";
  });

  demoSelect.addEventListener("change", () => {
    const value = demoSelect.value as AskRunState | "";
    snapshot = value ? ASK_SAMPLE_SNAPSHOTS[value] : null;
    paintHud();
  });

  paintHud();

  return {
    element: page,
    destroy: () => undefined,
  };
}

function renderAskHud(snapshot: GlassAskSnapshot): HTMLElement {
  const panel = el("article", `ask-hud ask-hud-${snapshot.state}`);
  panel.dataset.state = snapshot.state;
  panel.append(
    el("div", "ask-hud-kicker", hudKicker(snapshot.state)),
    el("h2", "ask-hud-title", snapshot.title),
  );
  if (snapshot.supportingCopy) {
    panel.append(el("p", "ask-hud-copy", snapshot.supportingCopy));
  }
  if (snapshot.milestones?.length) {
    const list = el("ol", "ask-milestones");
    for (const milestone of snapshot.milestones) {
      const item = el("li", `ask-milestone ask-milestone-${milestone.state}`, milestone.label);
      item.dataset.milestoneState = milestone.state;
      list.append(item);
    }
    panel.append(list);
  }
  if (snapshot.state === "needs-secret") {
    panel.append(createSecretsCard({
      slotLabel: snapshot.slotLabel || "password",
      presence: "waiting",
    }));
  } else if (snapshot.state === "failed") {
    panel.append(el("p", "ask-hud-failed-note", "Failed is terminal. needs-secret is a wait, not this."));
  }
  if (snapshot.sessionId) {
    panel.append(el("p", "ask-hud-session", `session_id ${snapshot.sessionId}`));
  }
  return panel;
}

function hudKicker(state: AskRunState): string {
  if (state === "needs-secret") return "NEEDS YOU";
  if (state === "action-needed") return "ACTION NEEDED";
  if (state === "working") return "WORKING";
  if (state === "done") return "DONE";
  return "FAILED";
}

function sampleLabel(state: AskRunState): string {
  if (state === "needs-secret") return "needs-secret — wait on phone";
  if (state === "action-needed") return "action-needed";
  return state;
}
