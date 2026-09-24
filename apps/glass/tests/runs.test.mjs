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

const FB = "package:com.facebook.katana";
const HOME = "screen:home:0123456789abcdef";
const LIST = "screen:list:aaaaaaaaaaaaaaaa";
const V2 = {
  ...DETAIL,
  mapSteps: 1,
  modelSteps: 1,
  places: [{ placeId: FB, appVersion: "512.0.0", route: [HOME, LIST] }],
  steps: [
    DETAIL.steps[0],
    { ...DETAIL.steps[1], roomId: HOME, roomAfter: LIST, placeId: FB, appVersion: "512.0.0", decisionSource: "map" },
    { ...DETAIL.steps[2], roomId: LIST, roomAfter: null, placeId: FB, appVersion: "512.0.0", decisionSource: "model" },
  ],
};

test("run record v2: rooms, app version and map vs model per step; the route opens on the app's map", async () => {
  installMiniDom();
  const navigated = [];
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs/ai-run-1": () => V2 });
  const context = { ...ctx(gateway.fetch), navigate: (route) => navigated.push(route) };
  const page = createRunPage(context, { name: "run", runId: "ai-run-1" });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /From the map/);
  assert.match(text, /1 of 2/);
  assert.match(text, /Route on the map/);
  assert.match(text, /Facebook/);
  assert.match(text, /version 512\.0\.0/);
  assert.match(text, /Home screen · 0123/);
  assert.equal(page.element.querySelectorAll(".timeline-source").length, 1, "only the map-chosen step carries the map badge");

  page.element.querySelectorAll(".timeline-button")[1].click();
  const detail = page.element.querySelector(".step-detail").textContent;
  assert.match(detail, /A known route \(no model call\)/);
  assert.match(detail, /List screen · aaaa/);

  [...page.element.querySelectorAll(".route-card .btn")].find((b) => /Show on the map/.test(b.textContent)).click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: FB, tab: "map", route: [HOME, LIST], runId: "ai-run-1" });
  [...page.element.querySelectorAll(".step-detail .btn")].find((b) => /Open this room/.test(b.textContent)).click();
  assert.deepEqual(navigated.at(-1).route, [HOME, LIST]);
  page.destroy();
});

test("v2 parsing drops rooms and places that are not structural; app routes carry the route", () => {
  const parsed = parseRunDetail({ ...V2, places: [{ placeId: "https://evil", route: [] }, { placeId: FB, appVersion: "x y", route: [HOME, "Inbox of alice"] }] });
  assert.equal(parsed.places.length, 1);
  assert.deepEqual(parsed.places[0].route, [HOME]);
  assert.equal(parsed.places[0].appVersion, null);
  assert.equal(parseRunSummary(FAILED).mapSteps, null, "older phones have no map/model split");
  const href = routeHref({ name: "app", placeId: FB, tab: "map", route: [HOME, LIST], runId: "ai-run-1" });
  assert.deepEqual(parseRoute(href), { name: "app", placeId: FB, tab: "map", route: [HOME, LIST], runId: "ai-run-1" });
  assert.deepEqual(parseRoute(`#/apps/${encodeURIComponent(FB)}/map?route=bad,${HOME}`).route, [HOME]);
});

test("Runs can be narrowed to one reason and one app", async () => {
  installMiniDom();
  const place = (id) => [{ placeId: id, appVersion: null, route: [] }];
  const runs = [
    { ...FAILED, runId: "ai-run-a", places: place("package:com.facebook.katana"), mapSteps: 0, modelSteps: 2 },
    { ...FAILED, runId: "ai-run-b", cause: { ...FAILED.cause, kind: "timeout" }, places: place("package:com.google.android.gm"), mapSteps: 0, modelSteps: 2 },
    { ...DONE, places: place("package:com.google.android.gm"), mapSteps: 1, modelSteps: 0 },
  ];
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs": () => ({ runs }) });
  const page = createRunsPage(ctx(gateway.fetch));
  await flush();
  const [causeSelect, appSelect] = page.element.querySelectorAll("select.runs-select");
  assert.equal(page.element.querySelectorAll("a.run-row").length, 3);
  causeSelect.value = "timeout";
  causeSelect.dispatchEvent({ type: "change" });
  assert.deepEqual(page.element.querySelectorAll("a.run-row").map((r) => r.dataset.runId), ["ai-run-b"]);
  causeSelect.value = "";
  causeSelect.dispatchEvent({ type: "change" });
  appSelect.value = "package:com.google.android.gm";
  appSelect.dispatchEvent({ type: "change" });
  assert.deepEqual(page.element.querySelectorAll("a.run-row").map((r) => r.dataset.runId), ["ai-run-b", "ai-run-2"]);
  assert.match(causeSelect.textContent, /Login wall/);
  page.destroy();
});

test("mapping passes are listed as runs and marked as such", async () => {
  installMiniDom();
  const pass = { ...DONE, runId: "ai-map-1", goal: "Map Gmail", model: "cyclone-mapper", mapSteps: 0, modelSteps: 0, places: [{ placeId: "package:com.google.android.gm", appVersion: "1", route: [] }] };
  const gateway = fakeGateway({ "GET /v1/devices/d1/runs": () => ({ runs: [pass] }) });
  const page = createRunsPage(ctx(gateway.fetch));
  await flush();
  const row = page.element.querySelector("a.run-row").textContent;
  assert.match(row, /Mapping pass/);
  assert.doesNotMatch(row, /cyclone-mapper/);
  page.destroy();
});
