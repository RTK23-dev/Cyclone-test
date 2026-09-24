/**
 * App → Scenarios and App → Versions (V5 plan 04). The phone computes both: scenarios are known routes from the app's
 * entry room to each destination, with health from the runs that went there; versions say which app versions the map
 * was learned on and which doors may be stale after an update. Glass lays them out and links into the Map and Runs.
 */
import type { GlassContext } from "../app.js";
import type { AppTab, Route } from "../core/router.js";
import { loadApps, type PhoneApp } from "../services/apps.js";
import { GatewayError } from "../services/gateway.js";
import {
  getScenarios,
  getVersions,
  healthLabel,
  kindLabel,
  healthTone,
  versionText,
  type AppVersions,
  type Scenario,
  type ScenarioList,
} from "../services/knowledge.js";
import { listRuns, roomLabel, statusLabel as runStatusLabel, statusTone as runStatusTone, type RunSummary } from "../services/runs.js";
import { phoneClient } from "../services/phone.js";
import { toMapsDocument } from "../maps/atlasDocument.js";
import { toViewModel, type AtlasViewModel, type Persona } from "../maps/atlasViewModel.js";
import { actionButton, card, chip, emptyState, errorState, loadingState, segmented, statTile } from "../ui/components.js";
import { runRow, runsError } from "./runsPage.js";
import { getKnowledge, type VaultSlot } from "../services/knowledgeSummary.js";
import { el, link, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import { icon } from "../ui/icons.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

type KnowledgeTab = Exclude<AppTab, "map">;

export function appTabs(placeId: string, active: AppTab): HTMLElement {
  const tabs = el("nav", "tabs");
  const items: Array<[AppTab, string]> = [["map", "Map"], ["screens", "Screens"]];
  if (placeId.startsWith("package:")) items.push(["scenarios", "Scenarios"], ["versions", "Versions"], ["runs", "Runs"]);
  for (const [tab, label] of items) {
    if (tab === active) {
      const current = el("span", "tab active", label);
      current.setAttribute("aria-current", "page");
      tabs.append(current);
    } else {
      tabs.append(link(label, `#/apps/${encodeURIComponent(placeId)}/${tab}`, "tab"));
    }
  }
  return tabs;
}

export function createAppKnowledgePage(
  ctx: GlassContext,
  route: Extract<Route, { name: "app" }> & { tab: KnowledgeTab },
  deps: { fetch?: typeof fetch } = {},
): GlassPage {
  const placeId = route.placeId;
  const element = el("div", "page page-app page-knowledge");
  const back = link("", "#/apps", "back-link");
  back.append(icon("back"), el("span", undefined, "Apps"));
  element.append(back);
  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;
  const controller = new AbortController();
  const header = el("header", "app-header");
  const body = el("div", "knowledge-body");
  element.append(header, appTabs(placeId, route.tab), body);
  renderHeader(null);
  setChildren(body, loadingState(`Loading ${route.tab} from the phone…`));
  let scenarioView: "cards" | "board" = "cards";
  let screensPersona: Persona = "mapping";
  let scenariosPersona: Persona = "mapping";

  function renderHeader(app: PhoneApp | null): void {
    const title = el("div", "app-title-row");
    const label = app?.label ?? placeId.slice(placeId.indexOf(":") + 1);
    const avatar = el("span", "app-avatar lg", (label[0] ?? "?").toUpperCase());
    const names = el("div", "app-names");
    names.append(el("h1", "page-title", label), el("p", "page-subtitle", app?.packageName ?? placeId));
    title.append(avatar, names);
    setChildren(header, title);
  }

  const showMap = (rooms: string[]): void => ctx.navigate({ name: "app", placeId, tab: "map", route: rooms });

  const load = async (): Promise<void> => {
    void loadApps(ctx.client, deviceId)
      .then((catalog) => renderHeader(catalog.apps.find((app) => app.placeId === placeId) ?? null))
      .catch(() => undefined);
    try {
      if (route.tab === "scenarios") renderScenarios(await getScenarios(ctx.client, deviceId, placeId, scenariosPersona, controller.signal));
      else if (route.tab === "versions") renderVersions(await getVersions(ctx.client, deviceId, placeId, controller.signal));
      else if (route.tab === "screens") await loadScreens();
      else await loadRuns();
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") return;
      setChildren(body, route.tab === "runs" ? runsError(error, () => void load()) : knowledgeError(error, () => void load()));
    }
  };

  async function loadScreens(): Promise<void> {
    const document = await phoneClient(ctx, deviceId, deps.fetch).get(placeId as never, screensPersona);
    if (controller.signal.aborted) return;
    renderScreens(toViewModel(toMapsDocument(document)));
  }

  function renderScreens(model: AtlasViewModel): void {
    const toggle = segmented<Persona>(
      [
        { id: "mapping", label: "Mapping pass" },
        { id: "live", label: "Your teaching" },
      ],
      screensPersona,
      (id) => {
        screensPersona = id;
        setChildren(body, loadingState("Loading screens from the phone…"));
        void loadScreens().catch((error) => setChildren(body, knowledgeError(error, () => void load())));
      },
    );
    if (!model.screens.length) {
      setChildren(body, toggle.element, emptyState({ icon: "map", title: "No screens known yet", body: "Map the app, or show Cyclone around it with Follow Me on the phone." }));
      return;
    }
    const doorsOut = new Map<string, number>();
    const doorsIn = new Map<string, number>();
    for (const edge of model.edges) {
      doorsOut.set(edge.fromScreenId, (doorsOut.get(edge.fromScreenId) ?? 0) + 1);
      doorsIn.set(edge.toScreenId, (doorsIn.get(edge.toScreenId) ?? 0) + 1);
    }
    const table = el("div", "run-table screens-table");
    const head = el("div", "run-row run-row-head screen-row");
    ["Screen", "Purpose", "Doors out", "Doors in", "Confidence", "Last seen"].forEach((label) => head.append(el("span", undefined, label)));
    table.append(head);
    const rows = [...model.screens].sort((a, b) => (doorsOut.get(b.screenId) ?? 0) - (doorsOut.get(a.screenId) ?? 0) || a.label.localeCompare(b.label));
    for (const screen of rows) {
      const row = el("button", "run-row screen-row");
      row.type = "button";
      row.dataset.screenId = screen.screenId;
      const name = el("span", "run-goal");
      name.append(el("span", "run-goal-text", screen.label), el("span", "run-sub", roomLabel(screen.screenId)));
      if (screen.risk.danger) name.append(chip("Guarded", "warning"));
      const confidence = Math.round((Number.isFinite(screen.confidence) ? screen.confidence : 0) * 100);
      row.append(
        name,
        el("span", undefined, screen.purpose || "—"),
        el("span", undefined, String(doorsOut.get(screen.screenId) ?? 0)),
        el("span", undefined, String(doorsIn.get(screen.screenId) ?? 0)),
        el("span", confidence < 50 ? "text-danger" : undefined, `${confidence}%`),
        el("span", "muted", screen.lastObservedAt ? relativeTime(Date.parse(screen.lastObservedAt)) : "—"),
      );
      row.addEventListener("click", () => showMap([screen.screenId]));
      table.append(row);
    }
    const summary = el("p", "muted knowledge-note", `${model.screens.length} screens · ${model.edges.length} doors. Click a screen to find it on the map.`);
    setChildren(body, toggle.element, summary, table);
  }

  async function loadRuns(): Promise<void> {
    const runs = await listRuns(ctx.client, deviceId, "all", 200, controller.signal);
    renderRuns(runs.filter((run) => run.places.some((place) => place.placeId === placeId)), runs.some((run) => run.mapSteps != null));
  }

  function renderRuns(runs: RunSummary[], phoneRecordsApps: boolean): void {
    if (!runs.length) {
      setChildren(
        body,
        emptyState({
          icon: "runs",
          title: "No runs in this app yet",
          body: phoneRecordsApps
            ? "Runs that enter this app show up here. Ask Cyclone something that uses it."
            : "This phone does not record which app each run used yet (Cyclone Mobile 5.0.0-alpha.11 or newer does).",
        }),
      );
      return;
    }
    const failed = runs.filter((run) => run.status === "failed").length;
    const stats = el("div", "stats stats-4");
    const mapSteps = runs.reduce((sum, run) => sum + (run.mapSteps ?? 0), 0);
    const modelSteps = runs.reduce((sum, run) => sum + (run.modelSteps ?? 0), 0);
    stats.append(
      statTile("Runs", String(runs.length)),
      statTile("Finished", String(runs.filter((run) => run.status === "completed").length), "success"),
      statTile("Failed", String(failed), failed ? "danger" : "neutral"),
      statTile("From the map", mapSteps + modelSteps ? `${Math.round((100 * mapSteps) / (mapSteps + modelSteps))}%` : "—"),
    );
    const table = el("div", "run-table");
    table.setAttribute("role", "list");
    table.append(...runs.map(runRow));
    setChildren(body, stats, table);
  }

  function renderScenarios(list: ScenarioList): void {
    const persona = segmented<Persona>(
      [
        { id: "mapping", label: "Mapping pass" },
        { id: "live", label: "Your teaching" },
      ],
      scenariosPersona,
      (id) => {
        scenariosPersona = id;
        setChildren(body, loadingState("Loading scenarios from the phone…"));
        void getScenarios(ctx.client, deviceId, placeId, scenariosPersona, controller.signal)
          .then(renderScenarios)
          .catch((error) => {
            if ((error as { name?: string })?.name !== "AbortError") setChildren(body, knowledgeError(error, () => void load()));
          });
      },
    );
    persona.element.classList.add("persona-toggle");
    if (!list.scenarios.length) {
      const map = actionButton("Open the map", { icon: "map", variant: "primary" });
      map.addEventListener("click", () => ctx.navigate({ name: "app", placeId, tab: "map" }));
      setChildren(
        body,
        persona.element,
        emptyState({
          icon: "map",
          title: "No scenarios yet",
          body: "Scenarios are the routes Cyclone knows from this app's first screen to each other screen. Map the app (or teach it) and they appear here.",
          action: map,
        }),
      );
      return;
    }
    const counts = { passing: 0, warning: 0, critical: 0, untested: 0 };
    list.scenarios.forEach((scenario) => counts[scenario.health]++);
    const stats = el("div", "stats stats-4");
    stats.append(
      statTile("Passing", String(counts.passing), counts.passing ? "success" : "neutral"),
      statTile("Warning", String(counts.warning), counts.warning ? "warning" : "neutral"),
      statTile("Critical", String(counts.critical), counts.critical ? "danger" : "neutral"),
      statTile("Not run yet", String(counts.untested)),
    );
    const note = el(
      "p",
      "muted knowledge-note",
      `Health measures whether Cyclone can get there, from the runs that reached each screen${list.entryScreenId ? `. Every route starts at ${roomLabel(list.entryScreenId)}` : ""}.`,
    );
    const view = segmented<"cards" | "board">(
      [
        { id: "cards", label: "Cards" },
        { id: "board", label: "Board" },
      ],
      scenarioView,
      (id) => {
        scenarioView = id;
        renderScenarios(list);
      },
    );
    const toggles = el("div", "scenario-toggles");
    toggles.append(persona.element, view.element);
    if (scenarioView === "board") {
      setChildren(body, stats, toggles, note, scenarioBoard(list));
      return;
    }
    const grid = el("div", "scenario-grid");
    grid.append(...list.scenarios.map(scenarioCard));
    setChildren(body, stats, toggles, note, grid);
  }

  /** Minitap-style: the entry room on the left, then one column per number of doors away. */
  function scenarioBoard(list: ScenarioList): HTMLElement {
    const board = el("div", "scenario-board");
    const entry = el("section", "scenario-column entry");
    entry.append(el("h3", "scenario-column-title", "Start"));
    const start = el("div", "scenario-mini entry");
    start.append(el("strong", undefined, list.entryScreenId ? roomLabel(list.entryScreenId) : "Entry"), el("span", "muted", "where every route begins"));
    entry.append(start);
    board.append(entry);
    const depths = [...new Set(list.scenarios.map((scenario) => scenario.steps))].sort((a, b) => a - b);
    for (const depth of depths) {
      const column = el("section", "scenario-column");
      column.append(el("h3", "scenario-column-title", `${depth} ${depth === 1 ? "door" : "doors"} away`));
      for (const scenario of list.scenarios.filter((s) => s.steps === depth)) {
        const mini = el("button", `scenario-mini health-${scenario.health}`);
        mini.type = "button";
        mini.dataset.scenarioId = scenario.scenarioId;
        mini.append(el("strong", undefined, scenario.title), chip(healthLabel(scenario.health), healthTone(scenario.health)));
        const kind = kindLabel(scenario.kind);
        if (kind) mini.append(chip(kind, "accent"));
        if (scenario.danger) mini.append(chip("Guarded", "warning"));
        mini.addEventListener("click", () => showMap(scenario.route));
        column.append(mini);
      }
      board.append(column);
    }
    return board;
  }

  /** Whether this app's Vault slots are set (never their values), for the Sign in card. */
  async function vaultState(into: HTMLElement): Promise<void> {
    let slots: VaultSlot[];
    try {
      slots = (await getKnowledge(ctx.client, deviceId, controller.signal)).vault.slots.filter((slot) => slot.placeId === placeId);
    } catch {
      return;
    }
    if (!slots.length) {
      setChildren(into, chip("No password slot yet", "warning"), el("span", "muted", "The phone asks for one the first time this login runs."));
      return;
    }
    setChildren(into, ...slots.map((slot) => chip(`${slot.slot}${slot.persona === "mapping" ? " (test account)" : ""} · ${slot.set ? "set" : "not set"}`, slot.set ? "success" : "warning")));
  }

  function scenarioCard(scenario: Scenario): HTMLElement {
    const node = card(`scenario-card health-${scenario.health}`);
    node.dataset.scenarioId = scenario.scenarioId;
    const top = el("div", "scenario-top");
    top.append(el("h2", "scenario-title", scenario.title), chip(healthLabel(scenario.health), healthTone(scenario.health)));
    const meta = el("div", "scenario-meta");
    meta.append(el("span", "muted", `${scenario.steps} ${scenario.steps === 1 ? "door" : "doors"}`));
    if (scenario.appVersion) meta.append(el("span", "muted", `version ${scenario.appVersion}`));
    if (scenario.lastVerifiedAt) meta.append(el("span", "muted", `last worked ${relativeTime(scenario.lastVerifiedAt)}`));
    const kind = kindLabel(scenario.kind);
    if (kind) meta.append(chip(kind, "accent"));
    if (scenario.danger) meta.append(chip("Passes a guarded door", "warning"));
    const rooms = el("ol", "route-rooms");
    scenario.route.forEach((room, index) => {
      const item = el("li", "route-room");
      item.append(el("span", "route-badge", String(index + 1)), el("span", undefined, roomLabel(room)));
      rooms.append(item);
    });
    const actions = el("div", "scenario-actions");
    const show = actionButton("Show on the map", { icon: "map" });
    show.addEventListener("click", () => showMap(scenario.route));
    actions.append(show);
    node.append(top, meta, rooms);
    if (scenario.kind === "sign-in") {
      node.append(el("p", "muted knowledge-note", "The phone fills the login from its Vault. Glass never sees the password."));
      const vault = el("div", "scenario-vault");
      node.append(vault);
      void vaultState(vault);
    }
    node.append(actions);
    if (scenario.runs.length) {
      const runs = el("ul", "scenario-runs");
      for (const run of scenario.runs) {
        const item = el("li");
        const open = link("", `#/runs/${encodeURIComponent(run.runId)}`, "scenario-run");
        open.append(chip(runStatusLabel(run.status), runStatusTone(run.status)), el("span", "muted", relativeTime(run.startedAt)));
        item.append(open);
        runs.append(item);
      }
      node.append(el("h3", "inspector-section", "Runs that went here"), runs);
    }
    return node;
  }

  function renderVersions(info: AppVersions): void {
    const parts: HTMLElement[] = [];
    const summary = card("versions-summary");
    summary.append(el("h2", "card-title", "Installed now"), el("p", "versions-installed", versionText(info.installedVersion)));
    if (info.needsRemap) {
      summary.classList.add("needs-remap");
      summary.append(chip("Needs remap", "warning"), el("p", "muted", "No door has been confirmed on the installed version yet. Remap so Cyclone trusts its routes again."));
      const remap = actionButton("Remap on the phone", { icon: "play", variant: "primary" });
      remap.addEventListener("click", () => ctx.navigate({ name: "app", placeId, tab: "map" }));
      summary.append(remap);
    } else if (info.versions.length) {
      summary.append(chip("Map matches this version", "success"));
    }
    parts.push(summary);

    if (!info.versions.length) {
      parts.push(
        emptyState({
          icon: "map",
          title: "No versions recorded yet",
          body: "Doors learned from alpha.11 on carry the app version they were seen on. Map the app to record this version.",
        }),
      );
      setChildren(body, ...parts);
      return;
    }

    const table = el("div", "run-table versions-table");
    const head = el("div", "run-row run-row-head version-row");
    ["Version", "Doors", "Rooms", "Failing doors", "Last seen"].forEach((label) => head.append(el("span", undefined, label)));
    table.append(head);
    for (const row of info.versions) {
      const line = el("div", "run-row version-row");
      const name = el("span", "version-name", versionText(row));
      if (row.installed) name.append(chip("Installed", "success"));
      line.append(
        name,
        el("span", undefined, String(row.doors)),
        el("span", undefined, String(row.rooms)),
        el("span", row.failingDoors ? "text-danger" : undefined, String(row.failingDoors)),
        el("span", "muted", row.lastSeenAt ? relativeTime(row.lastSeenAt) : "—"),
      );
      table.append(line);
    }
    parts.push(table);

    if (info.staleDoorCount) {
      const stale = card("stale-card");
      stale.append(
        el("h2", "card-title", `${info.staleDoorCount} ${info.staleDoorCount === 1 ? "door" : "doors"} last confirmed on an older version`),
        el("p", "muted", "They may have moved in the update. The next mapping pass or run that uses them confirms or replaces them."),
      );
      const list = el("ul", "stale-list");
      for (const door of info.staleDoors) {
        const item = el("li", "stale-door");
        item.append(
          el("span", undefined, `${roomLabel(door.fromScreenId)} → ${roomLabel(door.toScreenId)}`),
          el("span", "muted", `seen on ${versionText(door)}`),
        );
        const show = link("Show", "#", "stale-show");
        show.addEventListener("click", (event) => {
          event.preventDefault?.();
          showMap([door.fromScreenId, door.toScreenId]);
        });
        item.append(show);
        list.append(item);
      }
      stale.append(list);
      parts.push(stale);
    }
    setChildren(body, ...parts);
  }

  void load();
  return { element, destroy: () => controller.abort() };
}

export function knowledgeError(error: unknown, retry: () => void): HTMLElement {
  if (error instanceof GatewayError && ["PROTOCOL_MISMATCH", "CAPABILITY_UNAVAILABLE", "UNKNOWN_OPERATION"].includes(error.code)) {
    return emptyState({
      icon: "alert",
      tone: "warning",
      title: "Update Cyclone on the phone",
      body: "Scenarios and Versions need Cyclone Mobile 5.0.0-alpha.11 or newer on the phone.",
    });
  }
  if (error instanceof GatewayError && error.code === "INVALID_REQUEST") {
    return emptyState({ icon: "map", title: "Not available for websites yet", body: "Scenarios and Versions work for apps for now." });
  }
  return errorState("Couldn't load from the phone", { message: error instanceof Error ? error.message : String(error) }, retry);
}
