/**
 * The phone's runs (`runs.list` / `runs.get`). Steps and the cause of death are computed on the phone (RunInsight);
 * Glass parses defensively and labels them. Nothing here judges a run.
 */
import type { Tone } from "../ui/components.js";
import type { GatewayClient } from "./gateway.js";

export type RunStatus = "running" | "suspended" | "completed" | "failed" | "cancelled";
export type RunFilter = "all" | "failed" | "completed" | "stopped";
export type StepOutcome = "ok" | "failed" | "unverified" | "recovered" | "info";

export interface RunCause {
  kind: string;
  stepIndex: number | null;
  headline: string;
  detail: string;
  fix: string;
}

export interface RunMetrics {
  toolCalls: number;
  toolFailures: number;
  verificationFailures: number;
  recoveries: number;
  visionChecks: number;
  verifiedActions: number;
}

export interface RunSummary {
  runId: string;
  goal: string;
  model: string;
  status: RunStatus;
  startedAt: number;
  endedAt: number | null;
  durationMs: number;
  decisions: number;
  stepCount: number;
  metrics: RunMetrics;
  cause: RunCause | null;
}

export interface RunEvent {
  at: number;
  kind: string;
  text: string;
  code: string | null;
  ok: boolean | null;
  detail: string | null;
}

export interface RunStep {
  index: number;
  startedAt: number;
  endedAt: number;
  title: string;
  action: string | null;
  pageId: string | null;
  outcome: StepOutcome;
  verification: string | null;
  recovery: string | null;
  vision: boolean;
  eventsTruncated: boolean;
  events: RunEvent[];
}

export interface RunDetail extends RunSummary {
  result: string;
  stepsTruncated: boolean;
  steps: RunStep[];
}

const STATUSES = new Set<RunStatus>(["running", "suspended", "completed", "failed", "cancelled"]);
const OUTCOMES = new Set<StepOutcome>(["ok", "failed", "unverified", "recovered", "info"]);

export async function listRuns(client: GatewayClient, deviceId: string, filter: RunFilter = "all", limit = 100, signal?: AbortSignal): Promise<RunSummary[]> {
  const body = await client.get<{ runs?: unknown }>(
    `/v1/devices/${encodeURIComponent(deviceId)}/runs?limit=${limit}&filter=${filter}`,
    signal,
  );
  return Array.isArray(body?.runs) ? body.runs.map(parseRunSummary).filter((run): run is RunSummary => run !== null) : [];
}

export async function getRun(client: GatewayClient, deviceId: string, runId: string, signal?: AbortSignal): Promise<RunDetail> {
  const body = await client.get<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/runs/${encodeURIComponent(runId)}`, signal);
  const detail = parseRunDetail(body);
  if (!detail || detail.runId !== runId) throw new Error("The phone returned a different run.");
  return detail;
}

export function parseRunSummary(raw: unknown): RunSummary | null {
  const r = record(raw);
  const runId = str(r.runId);
  if (!/^[A-Za-z0-9_-]{4,120}$/.test(runId)) return null;
  const metrics = record(r.metrics);
  const cause = record(r.cause);
  return {
    runId,
    goal: str(r.goal) || "(no goal recorded)",
    model: str(r.model),
    status: STATUSES.has(r.status as RunStatus) ? (r.status as RunStatus) : "failed",
    startedAt: num(r.startedAt),
    endedAt: typeof r.endedAt === "number" ? r.endedAt : null,
    durationMs: num(r.durationMs),
    decisions: num(r.decisions),
    stepCount: num(r.stepCount),
    metrics: {
      toolCalls: num(metrics.toolCalls),
      toolFailures: num(metrics.toolFailures),
      verificationFailures: num(metrics.verificationFailures),
      recoveries: num(metrics.recoveries),
      visionChecks: num(metrics.visionChecks),
      verifiedActions: num(metrics.verifiedActions),
    },
    cause: str(cause.kind)
      ? {
          kind: str(cause.kind),
          stepIndex: typeof cause.stepIndex === "number" ? cause.stepIndex : null,
          headline: str(cause.headline) || causeLabel(str(cause.kind)),
          detail: str(cause.detail),
          fix: str(cause.fix),
        }
      : null,
  };
}

export function parseRunDetail(raw: unknown): RunDetail | null {
  const summary = parseRunSummary(raw);
  if (!summary) return null;
  const r = record(raw);
  const steps = Array.isArray(r.steps) ? r.steps.map(parseStep).filter((step): step is RunStep => step !== null) : [];
  return { ...summary, result: str(r.result), stepsTruncated: r.stepsTruncated === true, steps };
}

function parseStep(raw: unknown): RunStep | null {
  const r = record(raw);
  if (typeof r.index !== "number") return null;
  return {
    index: r.index,
    startedAt: num(r.startedAt),
    endedAt: num(r.endedAt),
    title: str(r.title) || `Step ${r.index + 1}`,
    action: str(r.action) || null,
    pageId: str(r.pageId) || null,
    outcome: OUTCOMES.has(r.outcome as StepOutcome) ? (r.outcome as StepOutcome) : "info",
    verification: str(r.verification) || null,
    recovery: str(r.recovery) || null,
    vision: r.vision === true,
    eventsTruncated: r.eventsTruncated === true,
    events: Array.isArray(r.events)
      ? r.events.map((e) => {
          const q = record(e);
          return {
            at: num(q.at),
            kind: str(q.kind),
            text: str(q.text),
            code: str(q.code) || null,
            ok: typeof q.ok === "boolean" ? q.ok : null,
            detail: str(q.detail) || null,
          };
        })
      : [],
  };
}

export function statusLabel(status: RunStatus): string {
  switch (status) {
    case "completed":
      return "Finished";
    case "failed":
      return "Failed";
    case "cancelled":
      return "Stopped";
    case "suspended":
      return "Waiting";
    case "running":
      return "Running";
  }
}

export function statusTone(status: RunStatus): Tone {
  switch (status) {
    case "completed":
      return "success";
    case "failed":
      return "danger";
    case "suspended":
      return "warning";
    case "running":
      return "accent";
    case "cancelled":
      return "neutral";
  }
}

const CAUSE_LABELS: Record<string, string> = {
  "needs-secret": "Login wall",
  gate: "Needs approval",
  "human-took-control": "You took the phone",
  cancelled: "Stopped by you",
  transport: "Phone unavailable",
  timeout: "Out of time",
  unchanged: "Action changed nothing",
  "element-not-found": "Control not found",
  "wrong-room": "Wrong screen",
  "stale-door": "Door no longer works",
  "door-missing": "No known door",
  "verification-failed": "Couldn't prove done",
  "model-gave-up": "Model stuck",
  "provider-error": "Model provider failed",
  blocked: "Hard blocker",
  unknown: "Stopped",
};

export function causeLabel(kind: string): string {
  return CAUSE_LABELS[kind] ?? kind.replace(/-/g, " ");
}

/** Causes the developer can fix in Cyclone's knowledge (map, doors) read as warnings; the rest are neutral facts. */
export function causeTone(kind: string): Tone {
  if (["cancelled", "human-took-control", "gate"].includes(kind)) return "neutral";
  if (["provider-error", "transport", "blocked"].includes(kind)) return "danger";
  return "warning";
}

export function outcomeLabel(outcome: StepOutcome): string {
  switch (outcome) {
    case "ok":
      return "Worked";
    case "failed":
      return "Failed";
    case "unverified":
      return "Not verified";
    case "recovered":
      return "Recovered";
    case "info":
      return "Info";
  }
}

export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return "—";
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${String(seconds % 60).padStart(2, "0")}s`;
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function str(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function num(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}
