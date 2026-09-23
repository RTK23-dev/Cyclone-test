import test from "node:test";
import assert from "node:assert/strict";
import { deviceReadiness, majorVersion, parseDevice, pickDevice } from "../.test-dist/services/devices.js";

const base = { deviceId: "d1", name: "Pixel 8", model: "Pixel 8", state: "READY", paired: true, planes: { aiTrust: "TRUSTED" }, mobileVersion: "5.0.0-alpha.7.dev1" };

test("parses the gateway device shape and ignores rows without an id", () => {
  assert.equal(parseDevice({ ...base, deviceId: "" }), null);
  assert.equal(parseDevice(null), null);
  const device = parseDevice(base);
  assert.equal(device.id, "d1");
  assert.equal(device.aiTrust, "TRUSTED");
  assert.equal(parseDevice({ id: "x" }).name, "Android phone");
});

test("readiness fails closed: disconnected, unpaired, unknown and old versions", () => {
  assert.deepEqual(deviceReadiness(parseDevice(base)), { ready: true });
  assert.equal(deviceReadiness(parseDevice({ ...base, state: "DISCONNECTED" })).reason, "disconnected");
  assert.equal(deviceReadiness(parseDevice({ ...base, paired: false })).reason, "unpaired");
  assert.equal(deviceReadiness(parseDevice({ ...base, mobileVersion: undefined })).reason, "version-unknown");
  const old = deviceReadiness(parseDevice({ ...base, mobileVersion: "4.8.0" }));
  assert.equal(old.reason, "needs-update");
  assert.match(old.message, /4\.8\.0/);
  assert.equal(majorVersion("10.1"), 10);
  assert.equal(majorVersion("v5"), null);
});

test("device picking keeps the chosen phone, else prefers a ready one", () => {
  const ready = parseDevice({ ...base, deviceId: "ready" });
  const old = parseDevice({ ...base, deviceId: "old", mobileVersion: "4.8.0" });
  assert.equal(pickDevice([old, ready], "old").id, "old");
  assert.equal(pickDevice([old, ready], "gone").id, "ready");
  assert.equal(pickDevice([old], null).id, "old");
  assert.equal(pickDevice([], "x"), null);
});
