/**
 * Apps (home): every app and web place on the phone, what Cyclone has mapped, and on which version.
 * The phone owns the list (`apps.list`); Glass filters and links into each app's map.
 */
import type { GlassContext } from "../app.js";
import { routeHref, type Route } from "../core/router.js";
import {
  catalogStats,
  scenarioSummary,
  filterApps,
  loadApps,
  sortApps,
  statusLabel,
  statusTone,
  versionLabel,
  type AppCatalog,
  type AppFilter,
  type PhoneApp,
} from "../services/apps.js";
import { GatewayError } from "../services/gateway.js";
import { listRuns, statusLabel as runStatusLabel, statusTone as runStatusTone, type RunSummary } from "../services/runs.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, chip, emptyState, errorState, loadingState, pageHeader, searchInput, segmented, statTile } from "../ui/components.js";
import { icon } from "../ui/icons.js";
import { plural, relativeTime } from "../ui/format.js";
import { createAppPage } from "./appPage.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

const FILTERS: Array<{ id: AppFilter; label: string }> = [
  { id: "all", label: "All" },
  { id: "mapped", label: "Mapped" },
  { id: "needs-remap", label: "Needs remap" },
  { id: "unmapped", label: "Not mapped" },
  { id: "web", label: "Web" },
  { id: "failing", label: "Last run failed" },
];

export function createAppsPage(ctx: GlassContext, route: Route): GlassPage {
  if (route.name === "app") return createAppPage(ctx, route);

  const element = el("div", "page page-apps");
  const refresh = actionButton("Refresh", { icon: "refresh" });
  element.append(pageHeader("Apps", "Every app on this phone, what Cyclone has mapped, and on which version.", [refresh]));

  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;

  let catalog: AppCatalog | null = null;
  /** Latest run per app (phones from alpha.11 record which apps a run entered). Loaded after the list; optional. */
  let lastRuns = new Map<string, RunSummary>();
  let filter: AppFilter = "all";
  let query = "";
  let controller: AbortController | null = null;

  const stats = el("div", "stats");
  const filters = segmented(FILTERS, filter, (id) => {
    filter = id;
    render();
  });
  const toolbar = el("div", "toolbar");
  toolbar.append(
    searchInput("Search apps", (value) => {
      query = value;
      render();
    }),
    filters.element,
  );
  const body = el("div", "apps-body");
  element.append(stats, toolbar, body);

  const render = (): void => {
    if (!catalog) return;
    const all = catalog.apps;
    const s = catalogStats(all);
    setChildren(
      stats,
      statTile("Apps", String(s.total)),
      statTile("Mapped", String(s.mapped), "success"),
      statTile("Needs remap", String(s.needsRemap), s.needsRemap ? "warning" : "neutral"),
      statTile("Rooms known", String(s.rooms), "accent"),
    );
    filters.set(filter, {
      all: all.length,
      mapped: s.mapped,
      "needs-remap": s.needsRemap,
      unmapped: all.length - s.mapped,
      web: all.filter((app) => app.kind === "chrome-origin").length,
    });
    const visible = sortApps(filter === "failing"
      ? filterApps(all, "all", query).filter((app) => lastRuns.get(app.placeId)?.status === "failed")
      : filterApps(all, filter, query));
    if (!visible.length) {
      setChildren(body, emptyState({ icon: "search", title: all.length ? "No apps match" : "No apps reported", body: all.length ? "Try another filter or search." : "The phone did not report any launchable apps." }));
      return;
    }
    const table = el("div", "app-table");
    table.setAttribute("role", "list");
    table.append(tableHeader());
    for (const app of visible) table.append(appRow(app, lastRuns.get(app.placeId)));
    setChildren(body, table, catalog.truncated ? el("p", "muted table-note", "Showing the first 600 apps the phone reported.") : null);
  };

  const load = async (): Promise<void> => {
    controller?.abort();
    controller = new AbortController();
    if (!catalog) setChildren(body, loadingState("Asking the phone for its apps…"));
    try {
      catalog = await loadApps(ctx.client, deviceId, controller.signal);
      render();
      void loadLastRuns(controller.signal);
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") return;
      catalog = null;
      setChildren(stats);
      setChildren(body, appsError(error, () => void load()));
    }
  };

  async function loadLastRuns(signal: AbortSignal): Promise<void> {
    try {
      const runs = await listRuns(ctx.client, deviceId, "all", 200, signal);
      const latest = new Map<string, RunSummary>();
      for (const run of runs) {
        for (const place of run.places) {
          const known = latest.get(place.placeId);
          if (!known || known.startedAt < run.startedAt) latest.set(place.placeId, run);
        }
      }
      lastRuns = latest;
      if (catalog) render();
    } catch {
      /* Older phones: the Apps page works without run facts. */
    }
  }

  refresh.addEventListener("click", () => void load());
  void load();
  return {
    element,
    destroy() {
      controller?.abort();
    },
  };
}

function tableHeader(): HTMLElement {
  const row = el("div", "app-row app-row-head");
  row.append(
    el("span", "col-app", "App"),
    el("span", "col-status", "Status"),
    el("span", "col-version", "Installed"),
    el("span", "col-mapped", "Mapped versions"),
    el("span", "col-size", "Rooms · doors"),
    el("span", "col-when", "Verified · last run"),
    el("span", "col-go"),
  );
  return row;
}

function appRow(app: PhoneApp, lastRun?: RunSummary): HTMLAnchorElement {
  const row = el("a", "app-row");
  row.href = routeHref({ name: "app", placeId: app.placeId, tab: "map" });
  row.setAttribute("role", "listitem");
  row.dataset.placeId = app.placeId;

  const who = el("span", "col-app");
  const avatar = el("span", `app-avatar${app.kind === "chrome-origin" ? " web" : ""}`, (app.label[0] ?? "?").toUpperCase());
  const names = el("span", "app-names");
  names.append(el("span", "app-name", app.label), el("span", "app-sub", app.kind === "chrome-origin" ? `Web · ${app.origin ?? ""}` : app.packageName ?? ""));
  who.append(avatar, names);

  const statusCell = el("span", "col-status");
  statusCell.append(chip(statusLabel(app), statusTone(app)));
  if (app.installed === false) statusCell.append(chip("Uninstalled", "neutral"));

  const mapped = el("span", "col-mapped");
  if (!app.mappedVersions.length) mapped.append(el("span", "muted", "—"));
  for (const version of app.mappedVersions.slice(0, 3)) mapped.append(chip(versionLabel(version), "neutral"));
  if (app.mappedVersions.length > 3) mapped.append(el("span", "muted", `+${app.mappedVersions.length - 3}`));

  const go = el("span", "col-go");
  go.append(icon("chevron"));

  row.append(
    who,
    statusCell,
    el("span", "col-version", app.kind === "chrome-origin" ? "Web" : versionLabel(app.installedVersion)),
    mapped,
    sizeCell(app),
    lastRunCell(app, lastRun),
    go,
  );
  return row;
}

function appsError(error: unknown, retry: () => void): HTMLElement {
  if (error instanceof GatewayError && (error.code === "PROTOCOL_MISMATCH" || error.code === "CAPABILITY_UNAVAILABLE")) {
    return emptyState({
      icon: "alert",
      tone: "warning",
      title: "Update Cyclone on the phone",
      body: "This phone's Cyclone does not share its app list yet. Glass needs Cyclone Mobile 5.0.0-alpha.7 or newer.",
    });
  }
  const message = error instanceof Error ? error.message : String(error);
  return errorState("Couldn't load the phone's apps", { message }, retry);
}

function sizeCell(app: PhoneApp): HTMLElement {
  const cell = el("span", "col-size", app.rooms ? `${plural(app.rooms, "room")} · ${plural(app.doors, "door")}` : "—");
  const summary = scenarioSummary(app);
  if (summary) {
    cell.append(el("br"), chip(summary.text, summary.tone));
  }
  return cell;
}

function lastRunCell(app: PhoneApp, lastRun?: RunSummary): HTMLElement {
  const cell = el("span", "col-when muted", app.rooms ? `verified ${relativeTime(app.lastVerifiedAt)}` : "—");
  if (lastRun) {
    cell.replaceChildren(chip(`Last run ${runStatusLabel(lastRun.status).toLowerCase()}`, runStatusTone(lastRun.status)));
    cell.title = `${lastRun.goal} · ${relativeTime(lastRun.startedAt)}`;
  }
  return cell;
}
