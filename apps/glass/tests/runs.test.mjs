import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { parseRoute, routeHref, sectionOf } from "../.test-dist/core/router.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";
import { causeLabel, formatDuration, parseRunDetail, parseRunSummary } from "../.test-dist/services/runs.js";
import { createRunsPage } from "../.test-dist/pages/runsPage.js";
import { createRunPage } from "../.test-dist/pages/runPage.js";

const FAILED = {
  runId: "ai-run-1",
  goal: "open Gmail, check my current logged in email, then go to facebook and find the dm of Louella",
  model: "model-x",
  status: "suspended",
  startedAt: Date.now() - 10 * 60_000,
  endedAt: null,
  durationMs: 94_000,
  decisions: 3,
  stepCount: 3,
  metrics: { toolCalls: 2, toolFailures: 1, verificationFailures: 0, recoveries: 1, visionChecks: 0, verifiedActions: 1 },
  cause: { kind: "needs-secret", stepIndex: 2, headline: "Stopped at a login wall", detail: "Facebook wants a password", fix: "Set the password slot for this app on the phone, then ask again." },
};
const DONE = { ...FAILED, runId: "ai-run-2", goal: "open clock", status: "completed", cause: null, durationMs: 8_000, stepCount: 2, metrics: { ...FAILED.metrics, toolFailures: 0 } };
const step = (index, outcome, title, events) => ({ index, startedAt: FAILED.startedAt + index * 1000, endedAt: FAILED.startedAt + index * 1000 + 500, title, action: index ? `click:${title}` : null, pageId: index ? "bbbbbbbbbbbbbbbb" : null, outcome, verification: null, recovery: index === 1 ? "recover.back" : null, vision: false, eventsTruncated: false, events });
const DETAIL = {
  ...FAILED,
  result: "Waiting for a password",
  stepsTruncated: false,
  steps: [
    step(0, "info", "Starting the task", [{ at: 1, kind: "START", text: "Starting task", code: "task.start", ok: null, detail: null }]),
    step(1, "recovered", "Messages", [{ at: 2, kind: "ANDROID_EXECUTION", text: "Tapped Messages", code: "executor.failed", ok: false, detail: "action=click:Messages" }]),
    step(2, "failed", "Log in", [{ at: 3, kind: "GATE_SUSPEND", text: "Facebook wants a password", code: "gate.need_secret", ok: null, detail: null }]),
  ],
};

function ctx(fetch, device = READY_DEVICE) {
  const devices = [parseDevice(device)];
  return { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.2", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
}

test("run routes round-trip and belong to the Runs section", () => {
  assert.deepEqual(parseRoute("#/runs"), { name: "runs" });
  assert.deepEqual(parseRoute(routeHref({ name: "run", runId: "ai-3f2a" })), { name: "run", runId: "ai-3f2a" });
  assert.deepEqual(parseRoute("#/runs/..%2Fetc"), { name: "runs" });
  assert.equal(sectionOf({ name: "run", runId: "ai-3f2a" }), "runs");
});

test("a phone-side 401/403 is not mistaken for an ended Glass session", async () => {
  let expired = 0;
  const client = new GatewayClient({
    token: "t",
    onSessionExpired: () => expired++,
    fetch: async (url) =>
      url.endsWith("/pairing")
        ? json({ detail: { code: "PAIRING_REQUIRED", message: "Pair the phone." } }, 401)
        : json({ detail: "Invalid bearer token" }, 403),
  });
  await assert.rejects(client.get("/pairing"), (e) => e.code === "PAIRING_REQUIRED" && !e.sessionExpired);
  assert.equal(expired, 0);
  await assert.rejects(client.get("/other"), (e) => e.sessionExpired);
  assert.equal(expired, 1);
});

test("run parsing is defensive and labels are plain", () => {
  assert.equal(parseRunSummary({ runId: "../x" }), null);
  const run = parseRunSummary({ ...FAILED, status: "exploded", metrics: null });
  assert.equal(run.status, "failed");
  assert.equal(run.metrics.toolCalls, 0);
  const detail = parseRunDetail({ ...DETAIL, steps: [...DETAIL.steps, { nope: true }] });
  assert.equal(detail.steps.length, 3);
  assert.equal(causeLabel("needs-secret"), "Login wall");
  assert.equal(causeLabel("something-new"), "something new");
  assert.equal(formatDuration(94_000), "1m 34s");
  assert.equal(formatDuration(0), "—");
});

test("Runs page lists runs with how they ended and why, and filters on the phone", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/devices/d1/runs": ({ query }) => ({ runs: query.filter === "failed" ? [FAILED] : [FAILED, DONE] }),
  });
  const page = createRunsPage(ctx(gateway.fetch));
  await flush();
  const rows = page.element.querySelectorAll("a.run-row");
  assert.equal(rows.length, 2);
  assert.equal(rows[0].href, "#/runs/ai-run-1");
  assert.match(rows[0].textContent, /Waiting/);
  assert.match(rows[0].textContent, /Login wall/);
  assert.match(rows[0].textContent, /1 failed/);
  assert.match(rows[1].textContent, /Finished/);
  assert.equal(gateway.calls[0].query.limit, "100");

  page.element.querySelectorAll(".segment").find((s) => s.dataset.id === "failed").click();
  await flush();
  assert.equal(gateway.calls.at(-1).query.filter, "failed");
  assert.equal(page.element.querySelectorAll("a.run-row").length, 1);

  page.element.querySelector(".search-input").value = "clock";
  page.element.querySelector(".search-input").dispatchEvent({ type: "input" });
  assert.match(page.element.textContent, /No runs match/);
  page.destroy();
});

test("an older phone is told to update; an empty phone says how runs appear", async () => {
  installMiniDom();
  const old = fakeGateway({ "GET /v1/devices/d1/runs": () => json({ detail: { code: "PROTOCOL_MISMATCH", message: "Unsupported gateway operation: runs.list" } }, 502) });
  let page = createRunsPage(ctx(old.fetch));
  await flush();
  assert.match(page.element.textContent, /5\.0\.0-alpha\.8/);
  const empty = fakeGateway({ "GET /v1/devices/d1/runs": () => ({ runs: [] }) });
  page = createRunsPage(ctx(empty.fetch));
  await flush();
  assert.match(page.element.textContent, /No runs yet/);
});

test("run inspector opens on the step where the run broke, with cause and fix", async () => {
  installMiniDom();
  const saved = [];
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => DETAIL });
  const page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-1" }, { saveFile: (name, text) => saved.push({ name, text }) });
  await flush();
  const text = page.element.textContent;
  assert.match(page.element.querySelector(".run-title").textContent, /Louella/);
  assert.match(text, /Cause of death/);
  assert.match(text, /Stopped at a login wall/);
  assert.match(text, /Set the password slot/);
  assert.equal(page.element.querySelectorAll(".timeline-item").length, 3);
  assert.equal(page.element.querySelector(".timeline-item.selected .timeline-button").dataset.step, "2");
  assert.match(page.element.querySelector(".step-detail").textContent, /Where it broke/);
  assert.match(page.element.querySelector(".step-detail").textContent, /GATE_SUSPEND/);

  page.element.querySelectorAll(".timeline-button")[1].click();
  const detail = page.element.querySelector(".step-detail").textContent;
  assert.match(detail, /Recovered/);
  assert.match(detail, /recover\.back/);
  assert.match(detail, /executor\.failed/);

  page.element.querySelectorAll(".cause-card .btn")[0].click();
  assert.equal(page.element.querySelector(".timeline-item.selected .timeline-button").dataset.step, "2");

  page.element.querySelector(".run-header .btn").click();
  assert.equal(saved[0].name, "cyclone-run-ai-run-1.json");
  assert.equal(JSON.parse(saved[0].text).cause.kind, "needs-secret");
  page.destroy();
});

test("a finished run shows its result; a missing run is named", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "GET /v1/devices/d1/runs/ai-run-2": () => ({ ...DETAIL, ...DONE, result: "Clock is open.", steps: DETAIL.steps.slice(0, 2) }),
    "GET /v1/devices/d1/runs/ai-gone": () => json({ detail: { code: "RUN_NOT_FOUND", message: "No run" } }, 404),
  });
  let page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-run-2" });
  await flush();
  assert.doesNotMatch(page.element.textContent, /Cause of death/);
  assert.match(page.element.textContent, /Clock is open\./);
  page = createRunPage(ctx(gateway.fetch), { name: "run", runId: "ai-gone" });
  await flush();
  assert.match(page.element.textContent, /Run not found/);
});
