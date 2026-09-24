import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush, json, READY_DEVICE } from "./helpers/fakeGateway.mjs";
import { createAppPage } from "../.test-dist/pages/appPage.js";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseDevice } from "../.test-dist/services/devices.js";

const PLACE = "package:com.example.clock";
const ROOM = (n) => `screen:list:${String(n).padStart(16, "a")}`;
const CURSOR = (n) => `c1:0123456789abcdef0123:${n}`;

function screen(id, index) {
  return {
    screenId: id,
    label: `Room ${index + 1}`,
    purpose: index === 0 ? "home" : "settings",
    factSlots: [],
    risk: { danger: false, classes: [] },
    confidence: 0.8,
    lastObservedAt: null,
    lastVerifiedAt: null,
    layout: { x: index * 260, y: 0 },
  };
}

function atlasDoc(persona, rooms, placeId = PLACE) {
  const place = placeId.startsWith("chrome:")
    ? { placeId, kind: "chrome-origin", label: "example.com", origin: placeId.slice(7) }
    : { placeId, kind: "package", label: "Clock", packageName: "com.example.clock" };
  return {
    place,
    persona,
    mapStatus: rooms.length ? "partial" : "unmapped",
    screens: rooms.map(screen),
    edges: rooms.slice(1).map((id, i) => ({
      edgeId: `door:${i}`,
      fromScreenId: rooms[0],
      toScreenId: id,
      actionHint: `Open ${id.slice(-4)}`,
      risk: { danger: false, classes: [] },
      confidence: 0.9,
      lastVerifiedAt: null,
    })),
    capabilities: [],
    confidence: 0.8,
    lastObservedAt: null,
    lastVerifiedAt: null,
  };
}

const CLOCK_APP = {
  placeId: PLACE,
  kind: "package",
  label: "Clock",
  packageName: "com.example.clock",
  origin: null,
  installed: true,
  installedVersion: { versionName: "8.1", versionCode: 81 },
  mapStatus: "partial",
  rooms: 1,
  doors: 0,
  lastVerifiedAt: null,
  needsRemap: false,
  personas: [{ persona: "mapping", mapStatus: "partial", rooms: 1, doors: 0, lastVerifiedAt: null }],
  mappedVersions: [{ versionName: "8.1", versionCode: 81, doors: 0 }],
};

function fakePhone({ rooms = [ROOM(1)], app = CLOCK_APP } = {}) {
  const phone = { rooms: [...rooms], job: null, cursor: rooms.length };
  const job = (state) => ({
    mappingJobId: "job-12345678",
    placeId: PLACE,
    state,
    currentAtlasNodeId: phone.rooms.at(-1) ?? null,
    progress: { newScreens: phone.rooms.length, verifiedMutations: 0 },
    failureCode: null,
    atlasStatus: "partial",
  });
  phone.gateway = fakeGateway({
    "GET /v1/devices/d1/apps": () => ({ apps: app ? [app] : [], truncated: false }),
    "GET /v1/devices/d1/atlas": ({ query }) => atlasDoc(query.persona, query.persona === "mapping" ? phone.rooms : [], query.placeId),
    "GET /v1/devices/d1/atlas/diff": ({ query }) => {
      const from = query.since ? Number(query.since.split(":")[2]) : phone.cursor;
      return {
        cursor: CURSOR(phone.rooms.length),
        resyncRequired: false,
        changes: phone.rooms.slice(from).map((id, i) => ({ cursor: CURSOR(from + i + 1), entity: "screen", change: "upsert", id })),
      };
    },
    "POST /v1/devices/d1/mapping/start": ({ body }) => {
      assert.equal(body.placeId, PLACE);
      assert.equal(body.persona, "mapping");
      assert.equal(body.sessionId, "default-foreground");
      phone.job = "running";
      return job("running");
    },
    "POST /v1/devices/d1/mapping/status": () => (phone.job ? job(phone.job) : { mappingJobId: null, placeId: null, state: "idle", currentAtlasNodeId: null, progress: {}, failureCode: null, atlasStatus: null }),
    "POST /v1/devices/d1/mapping/stop": () => {
      phone.job = "stopped";
      return job("stopped");
    },
  });
  phone.walk = (n, state = "running") => {
    phone.rooms.push(ROOM(n));
    phone.job = state;
  };
  return phone;
}

function manualTimer() {
  const pending = [];
  return {
    setTimer: (fn) => pending.push(fn),
    clearTimer: () => (pending.length = 0),
    async fire() {
      for (const fn of pending.splice(0)) fn();
      await flush();
    },
  };
}

function open(phone, timer = manualTimer(), placeId = PLACE, extra = {}) {
  installMiniDom();
  const client = new GatewayClient({ token: "t", fetch: phone.gateway.fetch });
  const devices = [parseDevice(READY_DEVICE)];
  const ctx = { client, version: "1.0.0-alpha.1", devices, device: devices[0], devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} };
  const page = createAppPage(ctx, { name: "app", placeId, tab: "map", ...extra }, { fetch: phone.gateway.fetch, setTimer: timer.setTimer, clearTimer: timer.clearTimer });
  return { page, timer };
}

test("app header shows phone facts; the board shows the phone's rooms and doors", async () => {
  const phone = fakePhone({ rooms: [ROOM(1), ROOM(2)] });
  const { page } = open(phone);
  await flush();
  const text = page.element.textContent;
  assert.match(page.element.querySelector(".page-title").textContent, /Clock/);
  assert.match(text, /Installed 8\.1/);
  assert.match(text, /Mapped on 8\.1/);
  assert.equal(page.element.querySelectorAll(".map-card").length, 2);
  assert.match(page.element.querySelector(".board-coverage").textContent, /2 rooms · 1 door/);
  assert.match(page.element.querySelector(".inspector").textContent, /Click a room/);
  assert.equal(page.element.querySelector(".mapping-controls").textContent.trim(), "Remap");
  page.destroy();
});

test("clicking a room opens the inspector with its doors", async () => {
  const phone = fakePhone({ rooms: [ROOM(1), ROOM(2)] });
  const { page } = open(phone);
  await flush();
  page.element.querySelectorAll(".map-card")[0].click();
  const inspector = page.element.querySelector(".inspector").textContent;
  assert.match(inspector, /Room/);
  assert.match(inspector, /Doors out/);
  assert.match(inspector, /Open/);
  page.destroy();
});

test("Start mapping follows the phone: cursor, new rooms from atlas.diff, then Stop", async () => {
  const phone = fakePhone({ rooms: [] });
  const { page, timer } = open(phone);
  await flush();
  assert.match(page.element.textContent, /Not mapped yet/);
  const start = page.element.querySelector(".mapping-controls .btn");
  assert.match(start.textContent, /Start mapping/);
  phone.rooms.push(ROOM(1));
  start.click();
  await flush();
  assert.match(page.element.querySelector(".mapping-status").textContent, /Mapping on the phone/);
  assert.match(page.element.querySelector(".mapping-controls").textContent, /Pause/);

  phone.walk(2);
  await timer.fire();
  await flush();
  assert.equal(page.element.querySelectorAll(".map-card").length, 2);
  assert.equal(page.element.querySelectorAll(".map-card.mapping-cursor").length, 1);

  const stop = page.element.querySelectorAll(".mapping-controls .btn").find((b) => /Stop/.test(b.textContent));
  stop.click();
  await flush();
  assert.match(page.element.querySelector(".mapping-status").textContent, /Mapping stopped/);
  assert.match(page.element.querySelector(".mapping-controls").textContent, /Remap/);
  assert.equal(page.element.querySelectorAll(".map-card.mapping-cursor").length, 0);
  page.destroy();
});

test("mapping errors are explained, not swallowed", async () => {
  const busy = fakePhone({ rooms: [] });
  const original = busy.gateway.fetch;
  busy.gateway.fetch = async (input, init = {}) =>
    String(input).includes("/mapping/start")
      ? json({ detail: { code: "HUMAN_HAS_CONTROL", message: "human" } }, 409)
      : original(input, init);
  const { page } = open(busy);
  await flush();
  page.element.querySelector(".mapping-controls .btn").click();
  await flush();
  assert.match(page.element.querySelector(".mapping-status").textContent, /You have control of the phone/);
  page.destroy();
});

test("web places show the board but cannot start mapping yet", async () => {
  const web = { ...CLOCK_APP, placeId: "chrome:https://example.com", kind: "chrome-origin", label: "example.com", packageName: null, origin: "https://example.com", installed: null, installedVersion: null };
  const phone = fakePhone({ app: web });
  const { page } = open(phone, manualTimer(), "chrome:https://example.com");
  await flush();
  assert.equal(page.element.querySelectorAll(".map-card").length, 1, "the web place's rooms still render");
  const start = page.element.querySelector(".mapping-controls .btn");
  assert.equal(start.disabled, true);
  assert.match(start.title, /Websites come later/);
  page.destroy();
});

test("a run's route lights up its rooms in order, with the doors between them", async () => {
  const phone = fakePhone({ rooms: [ROOM(1), ROOM(2), ROOM(3)] });
  const { page } = open(phone, manualTimer(), PLACE, { route: [ROOM(1), ROOM(3)], runId: "ai-run-1" });
  await flush();
  const onRoute = page.element.querySelectorAll(".map-card.on-route");
  assert.equal(onRoute.length, 2);
  assert.deepEqual(onRoute.map((card) => card.querySelector(".route-badge").textContent), ["1", "2"]);
  assert.equal(page.element.querySelectorAll(".map-edge.on-route").length, 1, "only the door ROOM(1) → ROOM(3) is on the route");
  const banner = page.element.querySelector(".route-banner").textContent;
  assert.match(banner, /2 rooms/);
  assert.match(banner, /Back to the run/);
  page.destroy();
});
