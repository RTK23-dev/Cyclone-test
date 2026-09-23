/** Runs: every Cyclone run on this phone, newest first, with how it ended and why. */
import type { GlassContext } from "../app.js";
import { routeHref } from "../core/router.js";
import { GatewayError } from "../services/gateway.js";
import { causeLabel, causeTone, formatDuration, listRuns, statusLabel, statusTone, type RunFilter, type RunSummary } from "../services/runs.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, chip, emptyState, errorState, loadingState, pageHeader, searchInput, segmented } from "../ui/components.js";
import { icon } from "../ui/icons.js";
import { relativeTime } from "../ui/format.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

const FILTERS: Array<{ id: RunFilter; label: string }> = [
  { id: "all", label: "All" },
  { id: "failed", label: "Failed" },
  { id: "completed", label: "Finished" },
  { id: "stopped", label: "Stopped or waiting" },
];

export function createRunsPage(ctx: GlassContext): GlassPage {
  const element = el("div", "page page-runs");
  const refresh = actionButton("Refresh", { icon: "refresh" });
  element.append(pageHeader("Runs", "Every task Cyclone ran on this phone. Open one to see each step and why it ended.", [refresh]));
  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;
  let filter: RunFilter = "all";
  let query = "";
  let runs: RunSummary[] | null = null;
  let controller: AbortController | null = null;

  const filters = segmented(FILTERS, filter, (id) => {
    filter = id;
    filters.set(filter);
    void load();
  });
  const toolbar = el("div", "toolbar");
  toolbar.append(
    searchInput("Search goals", (value) => {
      query = value;
      render();
    }),
    filters.element,
  );
  const body = el("div", "runs-body");
  element.append(toolbar, body);

  const render = (): void => {
    if (!runs) return;
    const q = query.trim().toLowerCase();
    const visible = q ? runs.filter((run) => run.goal.toLowerCase().includes(q)) : runs;
    if (!visible.length) {
      setChildren(
        body,
        emptyState({
          icon: "runs",
          title: runs.length ? "No runs match" : filter === "all" ? "No runs yet" : "No runs in this view",
          body: runs.length ? "Try another search." : "Ask Cyclone something on the phone or from the Phone page; runs appear here.",
        }),
      );
      return;
    }
    const table = el("div", "run-table");
    table.setAttribute("role", "list");
    const head = el("div", "run-row run-row-head");
    head.append(el("span", undefined, "Goal"), el("span", undefined, "Result"), el("span", undefined, "Why it ended"), el("span", undefined, "Steps"), el("span", undefined, "Started"), el("span", undefined, "Took"), el("span"));
    table.append(head);
    for (const run of visible) table.append(runRow(run));
    setChildren(body, table);
  };

  const load = async (): Promise<void> => {
    controller?.abort();
    controller = new AbortController();
    setChildren(body, loadingState("Asking the phone for its runs…"));
    try {
      runs = await listRuns(ctx.client, deviceId, filter, 100, controller.signal);
      render();
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") return;
      runs = null;
      setChildren(body, runsError(error, () => void load()));
    }
  };

  refresh.addEventListener("click", () => void load());
  void load();
  return { element, destroy: () => controller?.abort() };
}

function runRow(run: RunSummary): HTMLAnchorElement {
  const row = el("a", `run-row status-${run.status}`);
  row.href = routeHref({ name: "run", runId: run.runId });
  row.setAttribute("role", "listitem");
  row.dataset.runId = run.runId;
  const goal = el("span", "run-goal");
  goal.append(el("span", "run-goal-text", run.goal), el("span", "run-sub", run.model));
  const result = el("span");
  result.append(chip(statusLabel(run.status), statusTone(run.status)));
  const why = el("span", "run-why");
  if (run.cause) {
    why.append(chip(causeLabel(run.cause.kind), causeTone(run.cause.kind)));
    why.title = run.cause.headline;
  } else why.append(el("span", "muted", run.status === "completed" ? "Done" : "—"));
  const failures = run.metrics.toolFailures + run.metrics.verificationFailures;
  const steps = el("span", "run-steps", String(run.stepCount));
  if (failures) steps.append(el("span", "run-failures", ` · ${failures} failed`));
  const go = el("span", "col-go");
  go.append(icon("chevron"));
  row.append(goal, result, why, steps, el("span", "muted", relativeTime(run.startedAt)), el("span", "muted", formatDuration(run.durationMs)), go);
  return row;
}

export function runsError(error: unknown, retry: () => void): HTMLElement {
  if (error instanceof GatewayError && (error.code === "PROTOCOL_MISMATCH" || error.code === "CAPABILITY_UNAVAILABLE")) {
    return emptyState({
      icon: "alert",
      tone: "warning",
      title: "Update Cyclone on the phone",
      body: "This phone's Cyclone does not share its runs yet. The run inspector needs Cyclone Mobile 5.0.0-alpha.8 or newer.",
    });
  }
  if (error instanceof GatewayError && error.code === "RUN_NOT_FOUND") {
    return emptyState({ icon: "search", title: "Run not found", body: "The phone no longer has this run." });
  }
  return errorState("Couldn't load runs", { message: error instanceof Error ? error.message : String(error) }, retry);
}
