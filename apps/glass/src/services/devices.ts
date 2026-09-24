/** Phones as the local gateway sees them (`GET /v1/fleet`). Glass shows them; the gateway owns discovery and trust. */
import type { GatewayClient } from "./gateway.js";

export type AiTrust = "TRUSTED" | "UNPAIRED" | "CONFIRMATION_REQUIRED" | "EXPIRED" | "REVOKED" | string;

export interface GlassDevice {
  id: string;
  name: string;
  model: string;
  state: string;
  paired: boolean;
  connectionLabel: string;
  aiTrust: AiTrust;
  mobileVersion: string | null;
  /** USB, LAN or VIRTUAL. */
  transport: string;
  /** Trusted phone session is open right now (not only remembered). */
  sessionReady: boolean;
  /** Six digits shown on the phone while it is being connected. */
  matchCode: string | null;
  /** Gateway's own words for why a trusted phone is not usable yet (phone locked, gateway off, …). */
  problem: string | null;
}

/** Glass features that read the Atlas need Cyclone Mobile 5.x. Unknown version fails closed. */
export type DeviceReadiness =
  | { ready: true }
  | { ready: false; reason: NotReadyReason; message: string };

export type NotReadyReason = "disconnected" | "unpaired" | "connecting" | "needs-update" | "version-unknown";

export async function listDevices(client: GatewayClient, signal?: AbortSignal): Promise<GlassDevice[]> {
  const body = await client.get<{ devices?: unknown }>("/v1/fleet", signal);
  const rows = Array.isArray(body?.devices) ? body.devices : [];
  return rows.map(parseDevice).filter((device): device is GlassDevice => device !== null);
}

export function parseDevice(raw: unknown): GlassDevice | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  const id = text(record.deviceId) || text(record.id);
  if (!id) return null;
  const planes = (record.planes && typeof record.planes === "object" ? record.planes : {}) as Record<string, unknown>;
  const model = text(record.model);
  const trust = (record.trust && typeof record.trust === "object" ? record.trust : {}) as Record<string, unknown>;
  const matchCode = text(trust.matchCode);
  return {
    id,
    name: text(record.name) || model || "Android phone",
    model,
    state: text(record.state) || "DISCONNECTED",
    paired: record.paired === true,
    connectionLabel: text(record.connectionLabel),
    aiTrust: text(planes.aiTrust) || "UNPAIRED",
    mobileVersion: text(record.mobileVersion) || null,
    transport: text(record.source) || "USB",
    sessionReady: trust.sessionReady === true,
    matchCode: /^\d{6}$/.test(matchCode) ? matchCode : null,
    problem: gatewayProblem(record, trust),
  };
}

/** First concrete reason the gateway gives for a phone that is present but not usable. */
function gatewayProblem(record: Record<string, unknown>, trust: Record<string, unknown>): string | null {
  const trustError = text(trust.lastSafeError);
  if (trustError) return trustError;
  const health = (record.health && typeof record.health === "object" ? record.health : {}) as Record<string, unknown>;
  const planes = (health.planes && typeof health.planes === "object" ? health.planes : {}) as Record<string, unknown>;
  for (const name of ["gateway", "accessibility", "tokenSession"]) {
    const plane = planes[name] as Record<string, unknown> | undefined;
    if (plane && plane.ready === false && text(plane.message)) return text(plane.message);
  }
  const connection = (record.connectionHealth && typeof record.connectionHealth === "object" ? record.connectionHealth : {}) as Record<string, unknown>;
  return text(connection.lastError) || text(record.lastSafeError) || null;
}

export function deviceReadiness(device: GlassDevice): DeviceReadiness {
  if (device.state === "DISCONNECTED" || device.state === "UNAUTHORIZED") {
    return { ready: false, reason: "disconnected", message: "The phone is not connected to this PC. Check the USB cable or wireless debugging." };
  }
  if (device.aiTrust === "REVOKED") {
    return { ready: false, reason: "unpaired", message: "The phone logged this PC out. Connect again: Glass shows a code, you tap Allow on the phone." };
  }
  if (!device.paired) {
    return { ready: false, reason: "unpaired", message: "Connect this phone in Devices: Glass shows a code, you tap Allow on the phone." };
  }
  const major = majorVersion(device.mobileVersion);
  if (major === null) {
    if (!device.sessionReady || device.problem) {
      return {
        ready: false,
        reason: "connecting",
        message: device.problem
          ? `Connected before, not reachable right now: ${device.problem}`
          : "Connected before; Cyclone is reopening the session with the phone. Unlock the phone if it is locked.",
      };
    }
    return { ready: false, reason: "version-unknown", message: "Cyclone on the phone has not reported its version yet." };
  }
  if (major < 5) {
    return { ready: false, reason: "needs-update", message: `Update Cyclone on the phone. Glass needs Cyclone Mobile 5, this phone runs ${device.mobileVersion}.` };
  }
  return { ready: true };
}

/** Keep the chosen phone when it is still listed; otherwise prefer a ready phone, then any phone. */
export function pickDevice(devices: GlassDevice[], preferredId: string | null): GlassDevice | null {
  if (preferredId) {
    const kept = devices.find((device) => device.id === preferredId);
    if (kept) return kept;
  }
  return devices.find((device) => deviceReadiness(device).ready) ?? devices[0] ?? null;
}

export function majorVersion(version: string | null): number | null {
  const match = /^(\d+)\./.exec(version ?? "");
  return match ? Number(match[1]) : null;
}

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}
