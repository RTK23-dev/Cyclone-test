import {
  applyBoardFilters,
  formatCoverage,
  inspectorState,
  resolveSelection,
  statusLabel,
  toViewModel,
  type AtlasViewModel,
  type BoardFilters,
  type InspectorState,
  type Persona,
  type PlaceKind,
  type PlaceSummary,
} from "../maps/atlasViewModel.js";
import {
  defaultMapsPlaceId,
  mockMapsDataSource,
  type MapsDataSource,
} from "../maps/mockAtlas.js";
import { button, el, setChildren } from "../ui/dom.js";
import { createAppMapCanvas, type AppMapCanvasHandle } from "../ui/appMapCanvas.js";

export interface MapsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export interface MapsPageOptions {
  source?: MapsDataSource;
}

const ALPHA_HINT = "phone alpha.3";

let mapsCssLinked = false;

function ensureMapsCss(): void {
  if (mapsCssLinked) return;
  mapsCssLinked = true;
  if (typeof document === "undefined" || !document.head) return;
  if (document.getElementById("cyclone-maps-css")) return;
  const link = document.createElement("link");
  link.id = "cyclone-maps-css";
  link.rel = "stylesheet";
  try {
    link.href = new URL("../maps.css", import.meta.url).href;
  } catch {
    link.href = "/src/maps.css";
  }
  document.head.appendChild(link);
}

export function createMapsPage(options: MapsPageOptions = {}): MapsPageHandle {
  ensureMapsCss();
  const source = options.source ?? mockMapsDataSource;
  const page = el("section", "page maps-page");
  page.setAttribute("aria-label", "Maps");

  let persona: Persona = "live";
  let placeId = defaultMapsPlaceId();
  let selectedScreenId: string | null = null;
  let viewMode: "screens" | "capabilities" = "screens";
  let railQuery = "";
  let mappedOnly = false;
  let kindFilter: "all" | PlaceKind = "all";
  const filters: BoardFilters = { stale: false, blocked: false, danger: false };

  const top = el("div", "maps-top");
  const heading = el("div", "maps-place-heading");
  const kicker = el("div", "maps-kicker", "Maps");
  const placeTitle = el("div", "maps-place-title", "Gmail");
  heading.append(kicker, placeTitle);

  const personaSeg = segment("persona", [
    { id: "live", label: "Live" },
    { id: "mapping", label: "Dummy" },
  ]);
  const viewSeg = segment("view", [
    { id: "screens", label: "Screens" },
    { id: "capabilities", label: "Capabilities" },
  ]);
  const coverage = el("div", "maps-coverage");
  const start = button("Start mapping", "button primary compact maps-start");
  start.disabled = true;
  start.title = ALPHA_HINT;

  const filterStale = chip("Stale", "stale");
  const filterBlocked = chip("Blocked", "blocked");
  const filterDanger = chip("Danger", "danger");
  const filtersWrap = el("div", "maps-filters");
  filtersWrap.append(filterStale, filterBlocked, filterDanger, start);

  top.append(heading, personaSeg.root, viewSeg.root, coverage, el("div", "maps-top-spacer"), filtersWrap);

  const body = el("div", "maps-body");
  const rail = el("aside", "maps-rail");
  rail.append(el("div", "maps-pane-head", "Places"));
  const search = el("input", "maps-search") as HTMLInputElement;
  search.type = "search";
  search.placeholder = "Search places";
  search.setAttribute("aria-label", "Search places");
  const railFilters = el("div", "maps-rail-filters");
  const mappedChip = chip("Mapped only");
  const nativeChip = chip("Native");
  const chromeChip = chip("Chrome");
  railFilters.append(mappedChip, nativeChip, chromeChip);
  const placeList = el("div", "maps-place-list");
  rail.append(search, railFilters, placeList);

  const boardHost = el("div", "maps-board-host");
  boardHost.style.minWidth = "0";
  boardHost.style.minHeight = "0";
  boardHost.style.display = "flex";
  const canvas: AppMapCanvasHandle = createAppMapCanvas({
    onSelectScreen(screenId) {
      selectedScreenId = screenId;
      renderInspector();
    },
  });
  canvas.element.style.flex = "1";
  const capabilities = el("div", "maps-capabilities");
  capabilities.hidden = true;
  boardHost.append(canvas.element, capabilities);

  const inspector = el("aside", "maps-inspector");
  const inspectorHead = el("div", "maps-pane-head", "Inspector");
  const inspectorBody = el("div", "maps-inspector-body");
  inspector.append(inspectorHead, inspectorBody);

  body.append(rail, boardHost, inspector);
  page.append(top, body);

  const loadModel = (): AtlasViewModel => {
    const document = source.getDocument(placeId, persona);
    const summaries = source.listSummaries(persona);
    const summary = summaries.find((item) => item.place.placeId === placeId);
    if (!document) {
      return toViewModel({
        place: summary?.place ?? { placeId, kind: "package", label: placeId, packageName: "com.unknown.app" },
        persona,
        mapStatus: "unmapped",
        screens: [],
        edges: [],
        capabilities: [],
        confidence: 0,
        lastObservedAt: null,
        lastVerifiedAt: null,
      });
    }
    return applyBoardFilters(toViewModel(document), filters);
  };

  const renderRail = (): void => {
    const summaries = source.listSummaries(persona).filter((item) => {
      if (mappedOnly && item.mapStatus === "unmapped") return false;
      if (kindFilter !== "all" && item.place.kind !== kindFilter) return false;
      const q = railQuery.trim().toLowerCase();
      if (!q) return true;
      return `${item.place.label} ${item.place.placeId}`.toLowerCase().includes(q);
    });
    setChildren(
      placeList,
      ...summaries.map((item) => placeRow(item, item.place.placeId === placeId, () => {
        placeId = item.place.placeId;
        selectedScreenId = null;
        renderAll(true);
      })),
    );
  };

  const renderInspector = (): void => {
    const model = loadModel();
    const state = inspectorState(model, selectedScreenId);
    setChildren(inspectorBody, ...inspectorNodes(state));
  };

  const renderCapabilities = (model: AtlasViewModel): void => {
    if (!model.capabilities.length) {
      setChildren(capabilities, el("div", "maps-muted", "No capabilities recorded for this place yet."));
      return;
    }
    setChildren(
      capabilities,
      ...model.capabilities.map((name) => {
        const card = el("article", "maps-cap-card");
        card.append(el("div", "maps-cap-name", name), el("div", "maps-cap-copy", "Place capability · phone atlas"));
        return card;
      }),
    );
  };

  const renderAll = (fit: boolean): void => {
    const model = loadModel();
    selectedScreenId = resolveSelection(model, selectedScreenId);
    placeTitle.textContent = model.place.label;
    coverage.textContent = formatCoverage(model.coverage);
    personaSeg.set(persona);
    viewSeg.set(viewMode);
    canvas.element.hidden = viewMode !== "screens";
    capabilities.hidden = viewMode === "screens";
    canvas.setViewModel(model, { fit: viewMode === "screens" && fit });
    canvas.setSelectedScreenId(selectedScreenId);
    if (viewMode === "capabilities") renderCapabilities(model);
    renderRail();
    renderInspector();
  };

  personaSeg.root.addEventListener("click", (event) => {
    const next = (event.target as HTMLElement | null)?.closest("button")?.dataset.id;
    if (next !== "live" && next !== "mapping") return;
    persona = next;
    selectedScreenId = null;
    renderAll(true);
  });
  viewSeg.root.addEventListener("click", (event) => {
    const next = (event.target as HTMLElement | null)?.closest("button")?.dataset.id;
    if (next !== "screens" && next !== "capabilities") return;
    viewMode = next;
    renderAll(false);
  });
  filterStale.addEventListener("click", () => {
    filters.stale = !filters.stale;
    filterStale.classList.toggle("active", filters.stale);
    renderAll(false);
  });
  filterBlocked.addEventListener("click", () => {
    filters.blocked = !filters.blocked;
    filterBlocked.classList.toggle("active", filters.blocked);
    renderAll(false);
  });
  filterDanger.addEventListener("click", () => {
    filters.danger = !filters.danger;
    filterDanger.classList.toggle("active", filters.danger);
    renderAll(false);
  });
  mappedChip.addEventListener("click", () => {
    mappedOnly = !mappedOnly;
    mappedChip.classList.toggle("active", mappedOnly);
    renderRail();
  });
  const setKindFilter = (next: "all" | PlaceKind): void => {
    kindFilter = next;
    nativeChip.classList.toggle("active", kindFilter === "package");
    chromeChip.classList.toggle("active", kindFilter === "chrome-origin");
    renderRail();
  };
  nativeChip.addEventListener("click", () => {
    setKindFilter(kindFilter === "package" ? "all" : "package");
  });
  chromeChip.addEventListener("click", () => {
    setKindFilter(kindFilter === "chrome-origin" ? "all" : "chrome-origin");
  });
  search.addEventListener("input", () => {
    railQuery = search.value;
    renderRail();
  });

  renderAll(true);

  return {
    element: page,
    destroy(): void {
      canvas.destroy();
    },
  };
}

function placeRow(summary: PlaceSummary, selected: boolean, onSelect: () => void): HTMLButtonElement {
  const row = el("button", `maps-place${selected ? " selected" : ""}`) as HTMLButtonElement;
  row.type = "button";
  row.append(
    el("span", "maps-place-name", summary.place.label),
    el("span", `maps-status ${summary.mapStatus}`, statusLabel(summary.mapStatus)),
    el("span", "maps-place-id", summary.place.placeId),
  );
  row.addEventListener("click", onSelect);
  return row;
}

function inspectorNodes(state: InspectorState): HTMLElement[] {
  if (state.kind === "empty") {
    return [
      el("div", "maps-inspector-title", state.title),
      el("p", "maps-muted", state.subtitle),
    ];
  }
  const nodes: HTMLElement[] = [
    el("div", "maps-inspector-title", state.title),
    el("div", "maps-inspector-sub", state.subtitle),
    el("div", "maps-block-label", "Purpose"),
    el("div", "maps-purpose", state.purpose ?? ""),
    el("div", "maps-block-label", "Redacted frame"),
    el("div", "maps-frame", state.frameCopy),
    el("div", "maps-block-label", "Fact slots"),
  ];
  if (!state.factSlots.length) nodes.push(el("div", "maps-muted", "No fact slots on this room."));
  for (const slot of state.factSlots) {
    const card = el("div", "maps-slot");
    card.append(
      el("div", "maps-slot-name", slot.displayLabel),
      el("div", "maps-slot-value", slot.maskedValue),
      el("div", "maps-slot-copy", slot.description),
    );
    nodes.push(card);
  }
  nodes.push(el("div", "maps-block-label", "Doors"));
  if (!state.doors.length) nodes.push(el("div", "maps-muted", "No outgoing doors mapped from this room."));
  for (const door of state.doors) {
    const card = el("div", `maps-door${door.dark ? " dark" : ""}`);
    card.append(
      el("div", "maps-door-name", door.actionHint),
      el("div", "maps-door-copy", `to ${door.toLabel}${door.dark ? " · still dark" : ""}`),
    );
    nodes.push(card);
  }
  if (state.lastVerifiedAt || state.confidence != null) {
    const proof = el("div", "maps-muted");
    const pct = state.confidence != null ? `${Math.round(state.confidence * 100)}% confidence` : "confidence unknown";
    proof.textContent = `${pct} · last verified ${state.lastVerifiedAt ?? "never"}`;
    nodes.push(proof);
  }
  const actions = el("div", "maps-actions");
  actions.append(disabledAction("Pin"), disabledAction("Remap this room"), disabledAction("Never"));
  nodes.push(actions);
  return nodes;
}

function disabledAction(label: string): HTMLButtonElement {
  const node = button(label, "button secondary compact");
  node.disabled = true;
  node.title = ALPHA_HINT;
  return node;
}

function chip(label: string, extraClass = ""): HTMLButtonElement {
  const node = el("button", `maps-chip${extraClass ? ` ${extraClass}` : ""}`, label) as HTMLButtonElement;
  node.type = "button";
  return node;
}

function segment(name: string, items: Array<{ id: string; label: string }>): { root: HTMLElement; set(id: string): void } {
  const root = el("div", "maps-seg");
  root.setAttribute("role", "group");
  root.setAttribute("aria-label", name);
  const buttons = items.map((item) => {
    const node = el("button", "", item.label) as HTMLButtonElement;
    node.type = "button";
    node.dataset.id = item.id;
    root.append(node);
    return node;
  });
  return {
    root,
    set(id: string) {
      for (const node of buttons) node.classList.toggle("active", node.dataset.id === id);
    },
  };
}

export { inspectorState, toViewModel, applyBoardFilters };
