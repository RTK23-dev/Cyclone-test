/**
 * Run inspector (V5 plan 11): one run step by step. The cause of death and the step it happened on come from the
 * phone; Glass lays them out so a developer can see exactly where the run broke and what would fix it.
 */
import type { GlassContext } from "../app.js";
import type { Route } from "../core/router.js";
import {
  causeLabel,
  causeTone,
  formatDuration,
  getRun,
  outcomeLabel,
  statusLabel,
  statusTone,
  type RunDetail,
  type RunStep,
} from "../services/runs.js";
import { el, link, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, keyValue, loadingState, statTile, type Tone } from "../ui/components.js";
import { icon } from "../ui/icons.js";
import { relativeTime } from "../ui/format.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";
import { runsError } from "./runsPage.js";

export interface RunPageDeps {
  /** Save a file for the developer; defaults to a Blob download. */
  saveFile?: (name: string, text: string) => void;
}

const OUTCOME_TONE: Record<RunStep["outcome"], Tone> = {
  ok: "success",
  failed: "danger",
  unverified: "warning",
  recovered: "accent",
  info: "neutral",
};

export function createRunPage(ctx: GlassContext, route: Extract<Route, { name: "run" }>, deps: RunPageDeps = {}): GlassPage {
  const element = el("div", "page page-run");
  const back = link("", "#/runs", "back-link");
  back.append(icon("back"), el("span", undefined, "Runs"));
  element.append(back);
  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const deviceId = ctx.device.id;
  const controller = new AbortController();
  const body = el("div", "run-body");
  element.append(body);
  setChildren(body, loadingState("Loading this run from the phone…"));

  const load = async (): Promise<void> => {
    try {
      const run = await getRun(ctx.client, deviceId, route.runId, controller.signal);
      render(run);
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") return;
      setChildren(body, runsError(error, () => void load()));
    }
  };

  const render = (run: RunDetail): void => {
    let selected = run.cause?.stepIndex ?? run.steps.at(-1)?.index ?? 0;
    const timeline = el("ol", "timeline");
    const detail = el("section", "step-detail");

    const header = el("header", "run-header");
    const titles = el("div", "run-titles");
    titles.append(el("h1", "page-title run-title", run.goal));
    const meta = el("div", "run-meta");
    meta.append(
      chip(statusLabel(run.status), statusTone(run.status)),
      el("span", "muted", `Started ${relativeTime(run.startedAt)} · took ${formatDuration(run.durationMs)} · ${run.model || "model unknown"}`),
    );
    titles.append(meta);
    const download = actionButton("Download report", { icon: "download" });
    download.addEventListener("click", () => {
      const save = deps.saveFile ?? saveWithBlob;
      save(`cyclone-run-${run.runId}.json`, JSON.stringify(run, null, 2));
    });
    header.append(titles, download);

    const stats = el("div", "stats stats-6");
    stats.append(
      statTile("Steps", String(run.stepCount)),
      statTile("Actions", String(run.metrics.toolCalls)),
      statTile("Failed actions", String(run.metrics.toolFailures), run.metrics.toolFailures ? "danger" : "neutral"),
      statTile("Failed checks", String(run.metrics.verificationFailures), run.metrics.verificationFailures ? "warning" : "neutral"),
      statTile("Recoveries", String(run.metrics.recoveries), run.metrics.recoveries ? "accent" : "neutral"),
      statTile("Vision checks", String(run.metrics.visionChecks)),
    );

    const parts: Array<HTMLElement | null> = [header, stats];
    if (run.cause) {
      const cause = card(`cause-card cause-${causeTone(run.cause.kind)}`);
      const top = el("div", "cause-top");
      top.append(el("span", "cause-kicker", "Cause of death"), chip(causeLabel(run.cause.kind), causeTone(run.cause.kind)));
      cause.append(top, el("h2", "cause-headline", run.cause.headline));
      if (run.cause.detail) cause.append(el("p", "cause-detail", run.cause.detail));
      const fix = el("p", "cause-fix");
      fix.append(el("strong", undefined, "Fix: "), el("span", undefined, run.cause.fix));
      cause.append(fix);
      if (run.cause.stepIndex != null) {
        const go = actionButton(`Go to step ${run.cause.stepIndex + 1}`, { icon: "chevron" });
        go.addEventListener("click", () => select(run.cause!.stepIndex!));
        cause.append(go);
      }
      parts.push(cause);
    } else if (run.status === "completed") {
      const done = card("cause-card cause-success");
      done.append(el("span", "cause-kicker", "Finished"), el("p", "cause-detail", run.result || "Cyclone reported the goal as done."));
      parts.push(done);
    }

    const layout = el("div", "run-layout");
    const left = el("section", "timeline-card");
    left.append(el("h2", "card-title", "Steps"));
    if (run.stepsTruncated) left.append(el("p", "muted", `Showing the first ${run.steps.length} steps.`));
    left.append(timeline);
    layout.append(left, detail);
    parts.push(layout);
    setChildren(body, ...parts);

    const renderTimeline = (): void => {
      setChildren(
        timeline,
        ...run.steps.map((step) => {
          const item = el("li", `timeline-item outcome-${step.outcome}${step.index === selected ? " selected" : ""}${run.cause?.stepIndex === step.index ? " fatal" : ""}`);
          const button = el("button", "timeline-button");
          button.type = "button";
          button.dataset.step = String(step.index);
          button.append(
            el("span", "timeline-dot"),
            el("span", "timeline-index", String(step.index + 1)),
            el("span", "timeline-text", step.title),
          );
          if (step.action) button.append(el("span", "timeline-action", step.action));
          button.addEventListener("click", () => select(step.index));
          item.append(button);
          return item;
        }),
      );
    };

    const renderDetail = (): void => {
      const step = run.steps.find((s) => s.index === selected);
      if (!step) {
        setChildren(detail, el("p", "muted", "No steps were recorded for this run."));
        return;
      }
      const head = el("div", "step-head");
      head.append(el("span", "cause-kicker", `Step ${step.index + 1}`), chip(outcomeLabel(step.outcome), OUTCOME_TONE[step.outcome]));
      if (run.cause?.stepIndex === step.index) head.append(chip("Where it broke", "danger"));
      const facts = keyValue(
        [
          ["Action", step.action ?? "—"],
          ["Screen", step.pageId ? `page …${step.pageId}` : "not recorded"],
          ["Verification", step.verification ?? "—"],
          ["Recovery", step.recovery ?? "—"],
          ["Vision", step.vision ? "Used a screenshot" : "Structured UI only"],
          ["Offset", `+${formatDuration(step.startedAt - run.startedAt)}`],
        ] as Array<[string, string]>,
      );
      const events = el("ol", "event-list");
      for (const event of step.events) {
        const row = el("li", `event event-${event.ok === false ? "bad" : event.ok === true ? "good" : "neutral"}`);
        const line = el("div", "event-line");
        line.append(el("span", "event-kind", event.kind), el("span", "event-text", event.text));
        if (event.code) line.append(el("code", "event-code", event.code));
        row.append(line);
        if (event.detail) row.append(el("div", "event-detail", event.detail));
        events.append(row);
      }
      setChildren(
        detail,
        head,
        el("h2", "step-title", step.title),
        facts,
        el("h3", "inspector-section", "What happened"),
        events,
        step.eventsTruncated ? el("p", "muted", "Later events in this step were trimmed.") : null,
        el("p", "muted inspector-note", "Rooms on the map and before/after screenshots for each step arrive in the next alpha."),
      );
    };

    const select = (index: number): void => {
      selected = index;
      renderTimeline();
      renderDetail();
    };
    select(selected);
  };

  void load();
  return { element, destroy: () => controller.abort() };
}

function saveWithBlob(name: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
  const anchor = el("a");
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
