import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { createAppKnowledgePage } from "../.test-dist/pages/appKnowledgePage.js";
import { parseRoute, routeHref } from "../.test-dist/core/router.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";
import { parseScenarios, parseVersions } from "../.test-dist/services/knowledge.js";

const GM = "package:com.google.android.gm";
const HOME = "screen:home:aaaaaaaaaaaaaaaa";
const MENU = "screen:menu:bbbbbbbbbbbbbbbb";
const SETTINGS = "screen:settings:cccccccccccccccc";
const SCENARIOS = {
  placeId: GM,
  persona: "mapping",
  entryScreenId: HOME,
  scenarios: [
    { scenarioId: "sc_0123456789abcdef01", title: "Reach Settings", startScreenId: HOME, endScreenId: SETTINGS, route: [HOME, MENU, SETTINGS], steps: 2, danger: true, health: "critical", lastVerifiedAt: Date.now() - 60_000, appVersion: "2026.09.14", runs: [{ runId: "ai-run-2", status: "failed", startedAt: Date.now() - 120_000 }] },
    { scenarioId: "sc_0123456789abcdef02", title: "Reach Menu", startScreenId: HOME, endScreenId: MENU, route: [HOME, MENU], steps: 1, danger: false, health: "passing", lastVerifiedAt: null, appVersion: null, runs: [] },
  ],
};
const VERSIONS = {
  placeId: GM,
  installedVersion: { versionName: "2026.10.01", versionCode: 130 },
  needsRemap: true,
  versions: [
    { versionName: "2026.09.14", versionCode: 120, installed: false, doors: 3, rooms: 4, failingDoors: 1, lastSeenAt: Date.now() - 86_400_000 },
  ],
  staleDoorCount: 3,
  staleDoors: [{ edgeId: "edge:abc", fromScreenId: HOME, toScreenId: MENU, versionName: "2026.09.14", versionCode: 120 }],
};

function open(tab, routes) {
  installMiniDom();
  const gateway = fakeGateway({ "GET /v1/devices/d1/apps": () => ({ apps: [], truncated: false }), ...routes });
  const devices = [parseDevice({ ...READY_DEVICE, mobileVersion: "5.0.0-alpha.11.dev1" })];
  const navigated = [];
  const ctx = { client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "1.0.0-alpha.5", devices, device: devices[0], devicesError: null, navigate: (r) => navigated.push(r), selectDevice() {}, refreshDevices: async () => {} };
  const page = createAppKnowledgePage(ctx, { name: "app", placeId: GM, tab });
  return { page, gateway, navigated };
}

test("app tabs route: map, scenarios, versions", () => {
  assert.equal(parseRoute(`#/apps/${encodeURIComponent(GM)}/scenarios`).tab, "scenarios");
  assert.equal(parseRoute(`#/apps/${encodeURIComponent(GM)}/versions`).tab, "versions");
  assert.equal(parseRoute(`#/apps/${encodeURIComponent(GM)}/nonsense`).tab, "map");
  assert.equal(routeHref({ name: "app", placeId: GM, tab: "versions" }), `#/apps/${encodeURIComponent(GM)}/versions`);
});

test("Scenarios: health, routes and runs from the phone; a route opens on the map", async () => {
  const { page, gateway, navigated } = open("scenarios", { "GET /v1/devices/d1/apps/scenarios": () => SCENARIOS });
  await flush();
  const call = gateway.calls.find((c) => c.path.endsWith("/apps/scenarios"));
  assert.deepEqual(call.query, { placeId: GM, persona: "mapping" });
  const text = page.element.textContent;
  assert.match(text, /Reach Settings/);
  assert.match(text, /Critical/);
  assert.match(text, /Passes a guarded door/);
  assert.match(text, /2 doors/);
  assert.match(text, /Runs that went here/);
  assert.equal(page.element.querySelectorAll(".scenario-card").length, 2);
  assert.equal(page.element.querySelector(".tab.active").textContent, "Scenarios");
  page.element.querySelector('[data-scenario-id="sc_0123456789abcdef01"] .btn').click();
  assert.deepEqual(navigated.at(-1), { name: "app", placeId: GM, tab: "map", route: [HOME, MENU, SETTINGS] });
});

test("Versions: installed version, needs remap, per-version doors and stale doors", async () => {
  const { page, navigated } = open("versions", { "GET /v1/devices/d1/apps/versions": () => VERSIONS });
  await flush();
  const text = page.element.textContent;
  assert.match(text, /2026\.10\.01 \(130\)/);
  assert.match(text, /Needs remap/);
  assert.match(text, /2026\.09\.14 \(120\)/);
  assert.match(text, /3 doors last confirmed on an older version/);
  assert.match(text, /Home screen · aaaa → Menu screen · bbbb/);
  page.element.querySelector(".stale-show").click();
  assert.deepEqual(navigated.at(-1).route, [HOME, MENU]);
});

test("older phones are told to update; empty knowledge explains itself; parsing is defensive", async () => {
  let s = open("scenarios", { "GET /v1/devices/d1/apps/scenarios": () => json({ detail: { code: "PROTOCOL_MISMATCH", message: "x" } }, 426) });
  await flush();
  assert.match(s.page.element.textContent, /Update Cyclone on the phone/);
  s = open("scenarios", { "GET /v1/devices/d1/apps/scenarios": () => ({ ...SCENARIOS, scenarios: [] }) });
  await flush();
  assert.match(s.page.element.textContent, /No scenarios yet/);
  const parsed = parseScenarios({ scenarios: [{ scenarioId: "x", route: [HOME] }, { scenarioId: "y", route: [HOME, "Inbox of alice", MENU], health: "great" }] }, GM);
  assert.equal(parsed.scenarios.length, 1);
  assert.deepEqual(parsed.scenarios[0].route, [HOME, MENU]);
  assert.equal(parsed.scenarios[0].health, "untested");
  assert.equal(parseVersions({ staleDoors: [{ edgeId: "e", fromScreenId: "bad", toScreenId: MENU }] }, GM).staleDoors.length, 0);
});
