/**
 * One app (a "house"): header facts from `apps.list`, the rooms-and-doors board from `atlas.get`, and mapping
 * commanded on the phone (`mapping.*`) with the board following `atlas.diff`. Glass decides nothing: it shows the
 * phone's map and forwards the developer's Start / Pause / Stop.
 */
import type { GlassContext } from "../app.js";
import type { Route } from "../core/router.js";
import { toMapsDocument } from "../maps/atlasDocument.js";
import {
  inspectorState,
  inspectorStateForEdge,
  statusLabel as roomStatusLabel,
  toViewModel,
  type AtlasViewModel,
  type InspectorState,
  type Persona,
} from "../maps/atlasViewModel.js";
import { createMappingWatcher, isActiveMapping, mappingStatusLine, type MappingWatcher } from "../maps/mappingWatcher.js";
import type { MappingJobView } from "../services/atlasClient.js";
import { loadApps, statusLabel, statusTone, versionLabel, type PhoneApp } from "../services/apps.js";
import { mappingErrorCopy, phoneClient } from "../services/phone.js";
import { el, link, setChildren } from "../ui/dom.js";
import { actionButton, chip, emptyState, errorState, loadingState, segmented } from "../ui/components.js";
import { createAppMapCanvas } from "../ui/appMapCanvas.js";
import { icon } from "../ui/icons.js";
import { plural, relativeTime } from "../ui/format.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

export interface AppPageDeps {
  fetch?: typeof fetch;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

export function createAppPage(ctx: GlassContext, route: Extract<Route, { name: "app" }>, deps: AppPageDeps = {}): GlassPage {
  const placeId = route.placeId;
  const element = el("div", "page page-app");
  const back = link("", "#/apps", "back-link");
  back.append(icon("back"), el("span", undefined, "Apps"));
  element.append(back);

  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;
  const phone = phoneClient(ctx, deviceId, deps.fetch);

  let app: PhoneApp | null = null;
  let persona: Persona = "mapping";
  let model: AtlasViewModel | null = null;
  let job: MappingJobView | null = null;
  let selection: { kind: "screen" | "edge"; id: string } | null = null;
  let destroyed = false;
  let loadSeq = 0;

  const header = el("header", "app-header");
  const controls = el("div", "mapping-controls");
  const statusLine = el("div", "mapping-status");
  statusLine.setAttribute("role", "status");
  const personaSwitch = segmented<Persona>(
    [
      { id: "mapping", label: "Mapping pass" },
      { id: "live", label: "Your teaching" },
    ],
    persona,
    (id) => {
      if (id === persona) return;
      persona = id;
      personaSwitch.set(persona);
      void loadAtlas(true);
    },
  );
  const tabs = el("nav", "tabs");
  const mapTab = el("span", "tab active", "Map");
  mapTab.setAttribute("aria-current", "page");
  tabs.append(mapTab);

  const coverage = el("div", "board-coverage");
  const bar = el("div", "board-bar");
  bar.append(personaSwitch.element, coverage, statusLine);

  const canvas = createAppMapCanvas({
    onSelectScreen: (id) => {
      if (id) selection = { kind: "screen", id };
      else if (selection?.kind === "screen") selection = null;
      renderInspector();
    },
    onSelectEdge: (id) => {
      if (id) selection = { kind: "edge", id };
      else if (selection?.kind === "edge") selection = null;
      renderInspector();
    },
  });
  const inspector = el("aside", "inspector");
  const boardArea = el("div", "board-area");
  const board = el("section", "board");
  board.append(boardArea, inspector);

  element.append(header, tabs, bar, board);

  const watcher: MappingWatcher = createMappingWatcher({
    ops: phone,
    onJob: (next) => {
      job = next;
      canvas.setCursorScreenId(next.placeId === placeId && isActiveMapping(next) ? next.currentAtlasNodeId : null);
      renderControls();
    },
    onAtlasChanged: (changed) => {
      if (changed === placeId && persona === "mapping") void loadAtlas(false);
      if (changed === placeId && !isActiveMapping(job)) void loadHeader();
    },
    onError: (error) => {
      statusLine.textContent = error instanceof Error ? error.message : String(error);
      statusLine.dataset.tone = "danger";
    },
    setTimer: deps.setTimer,
    clearTimer: deps.clearTimer,
  });

  const renderHeader = (): void => {
    const title = el("div", "app-title-row");
    const avatar = el("span", `app-avatar lg${placeId.startsWith("chrome:") ? " web" : ""}`, ((app?.label ?? placeId.split(":")[1] ?? "?")[0] ?? "?").toUpperCase());
    const names = el("div", "app-names");
    names.append(el("h1", "page-title", app?.label ?? placeId.slice(placeId.indexOf(":") + 1)));
    names.append(el("p", "page-subtitle", app?.kind === "chrome-origin" ? `Web place · ${app.origin ?? ""}` : app?.packageName ?? placeId));
    title.append(avatar, names, controls);
    const facts = el("div", "app-facts");
    if (app) {
      facts.append(chip(statusLabel(app), statusTone(app)));
      if (app.kind === "package") facts.append(chip(`Installed ${versionLabel(app.installedVersion)}`, "neutral"));
      for (const version of app.mappedVersions.slice(0, 4)) facts.append(chip(`Mapped on ${versionLabel(version)}`, "neutral"));
      if (app.needsRemap) facts.append(chip("Map learned on an older version", "warning"));
      if (app.installed === false) facts.append(chip("No longer installed", "neutral"));
    }
    setChildren(header, title, facts);
  };

  const renderControls = (): void => {
    const active = isActiveMapping(job) && job?.placeId === placeId;
    const otherPlaceBusy = isActiveMapping(job) && job?.placeId !== placeId;
    const buttons: HTMLElement[] = [];
    if (!active) {
      const start = actionButton(model && model.screens.length ? "Remap" : "Start mapping", { icon: "play", variant: "primary" });
      start.disabled = otherPlaceBusy || !placeId.startsWith("package:") || app?.installed === false;
      if (!placeId.startsWith("package:")) start.title = mappingErrorCopy("PLACE_NOT_LAUNCHABLE");
      start.addEventListener("click", () => void command(() => watcher.start(placeId), true));
      buttons.push(start);
    } else {
      const paused = job?.state === "paused" || job?.state === "human-control";
      const toggle = actionButton(paused ? "Resume" : "Pause", { icon: paused ? "play" : "pause" });
      toggle.addEventListener("click", () => void command(() => (paused ? watcher.resume() : watcher.pause()), false));
      const stop = actionButton("Stop", { icon: "stop", variant: "danger" });
      stop.addEventListener("click", () => void command(() => watcher.stop(), false));
      buttons.push(toggle, stop);
    }
    setChildren(controls, ...buttons);
    statusLine.dataset.tone = job?.state === "failed" || job?.state === "needs-secret" ? "warning" : "neutral";
    statusLine.textContent = otherPlaceBusy
      ? "The phone is mapping another app."
      : job && job.placeId === placeId
        ? mappingStatusLine(job)
        : "";
  };

  const command = async (run: () => Promise<void>, switchToMapping: boolean): Promise<void> => {
    try {
      if (switchToMapping && persona !== "mapping") {
        persona = "mapping";
        personaSwitch.set(persona);
        void loadAtlas(true);
      }
      await run();
    } catch (error) {
      const code = (error as { code?: string })?.code ?? "";
      statusLine.textContent = mappingErrorCopy(code);
      statusLine.dataset.tone = "danger";
    }
  };

  const renderInspector = (): void => {
    if (!model) {
      setChildren(inspector);
      return;
    }
    const state: InspectorState =
      selection?.kind === "edge" ? inspectorStateForEdge(model, selection.id) : inspectorState(model, selection?.kind === "screen" ? selection.id : null);
    setChildren(inspector, ...inspectorNodes(state, model));
  };

  const renderBoard = (fit: boolean): void => {
    if (!model) return;
    coverage.textContent = model.screens.length
      ? `${plural(model.coverage.screens, "room")} · ${plural(model.coverage.doors, "door")}${model.coverage.dark ? ` · ${model.coverage.dark} dark` : ""} · verified ${relativeTime(model.lastVerifiedAt)}`
      : "";
    if (!model.screens.length) {
      setChildren(
        boardArea,
        emptyState({
          icon: "map",
          title: persona === "mapping" ? "Not mapped yet" : "Nothing taught yet",
          body:
            persona === "mapping"
              ? "Start mapping and the phone walks this app's tabs, menus and settings. It never pays, sends, deletes or grants permissions. Rooms appear here as it goes."
              : "Rooms you show Cyclone with Follow Me on the phone appear here. They stay separate from the mapping pass.",
        }),
      );
      return;
    }
    if (canvas.element.parentElement !== boardArea) setChildren(boardArea, canvas.element);
    canvas.setViewModel(model, { fit });
  };

  const loadAtlas = async (fit: boolean): Promise<void> => {
    const seq = ++loadSeq;
    if (!model || fit) setChildren(boardArea, loadingState("Asking the phone for this map…"));
    try {
      const document = await phone.get(placeId, persona);
      if (destroyed || seq !== loadSeq) return;
      model = toViewModel(toMapsDocument(document));
      if (selection?.kind === "screen" && !model.screens.some((s) => s.screenId === selection?.id)) selection = null;
      renderBoard(fit);
      renderInspector();
      renderControls();
    } catch (error) {
      if (destroyed || seq !== loadSeq) return;
      model = null;
      renderInspector();
      setChildren(boardArea, errorState("Couldn't load this map", { message: error instanceof Error ? error.message : String(error) }, () => void loadAtlas(true)));
    }
  };

  const loadHeader = async (): Promise<void> => {
    try {
      const catalog = await loadApps(ctx.client, deviceId);
      if (destroyed) return;
      app = catalog.apps.find((entry) => entry.placeId === placeId) ?? null;
    } catch {
      app = null; // Older phones without apps.list still get the board.
    }
    renderHeader();
    renderControls();
  };

  renderHeader();
  renderControls();
  void (async () => {
    await loadHeader();
    if (destroyed) return;
    // Show the persona that has rooms: the mapping pass by default, the user's own teaching otherwise.
    const loaded = app as PhoneApp | null; // assigned by loadHeader(); TS cannot see through the closure
    const mapping = loaded?.personas.find((p) => p.persona === "mapping");
    const live = loaded?.personas.find((p) => p.persona === "live");
    if ((!mapping || mapping.rooms === 0) && live && live.rooms > 0) {
      persona = "live";
      personaSwitch.set(persona);
    }
    await loadAtlas(true);
    if (!destroyed) await watcher.attach().catch(() => undefined);
  })();

  return {
    element,
    destroy() {
      destroyed = true;
      watcher.dispose();
      canvas.destroy();
    },
  };
}

function inspectorNodes(state: InspectorState, model: AtlasViewModel): HTMLElement[] {
  if (state.kind === "empty") {
    return [
      el("h2", "inspector-title", "Inspector"),
      el("p", "muted", model.screens.length ? "Click a room or a door on the board to see what Cyclone knows about it." : "Rooms and doors appear here once the app is mapped."),
    ];
  }
  const nodes: HTMLElement[] = [el("div", "inspector-kicker", state.kind === "edge" ? "Door" : "Room"), el("h2", "inspector-title", state.title)];
  if (state.subtitle) nodes.push(el("p", "muted", state.subtitle));
  const facts = el("dl", "kv");
  const add = (key: string, value: string): void => {
    facts.append(el("dt", "kv-key", key), el("dd", "kv-value", value));
  };
  if (state.kind === "edge") {
    add("From", state.fromLabel ?? "—");
    add("To", state.toLabel ?? "—");
    if (state.actionHint) add("Action", state.actionHint);
  } else {
    add("Purpose", state.purpose ?? "—");
    if (state.region) add("Region", state.region);
    if (state.tone) add("State", roomStatusLabel(state.tone));
  }
  add("Confidence", state.confidence != null ? `${Math.round(state.confidence * 100)}%` : "unknown");
  add("Verified", relativeTime(state.lastVerifiedAt ?? null));
  nodes.push(facts);

  if (state.kind === "screen") {
    nodes.push(el("h3", "inspector-section", "Facts on this screen"));
    if (!state.factSlots.length) nodes.push(el("p", "muted", "None recorded."));
    for (const slot of state.factSlots) {
      const row = el("div", "slot");
      row.append(el("span", "slot-name", slot.displayLabel), el("span", "slot-value", slot.maskedValue));
      if (slot.description) row.append(el("span", "slot-copy muted", slot.description));
      nodes.push(row);
    }
    nodes.push(el("h3", "inspector-section", "Doors out"));
    if (!state.doors.length) nodes.push(el("p", "muted", "No doors mapped from this room yet."));
    for (const door of state.doors) {
      const row = el("div", `door${door.dark ? " dark" : ""}${door.risk.danger ? " danger" : ""}`);
      row.append(el("span", "door-name", door.actionHint), el("span", "door-to muted", `to ${door.toLabel}${door.dark ? " · not verified" : ""}`));
      nodes.push(row);
    }
    nodes.push(el("p", "muted inspector-note", "Screenshots of rooms arrive with the run inspector in a later alpha."));
  }
  return nodes;
}
