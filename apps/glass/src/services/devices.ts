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
}

/** Glass features that read the Atlas need Cyclone Mobile 5.x. Unknown version fails closed. */
export type DeviceReadiness =
  | { ready: true }
  | { ready: false; reason: "disconnected" | "unpaired" | "needs-update" | "version-unknown"; message: string };

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
  return {
    id,
    name: text(record.name) || model || "Android phone",
    model,
    state: text(record.state) || "DISCONNECTED",
    paired: record.paired === true,
    connectionLabel: text(record.connectionLabel),
    aiTrust: text(planes.aiTrust) || "UNPAIRED",
    mobileVersion: text(record.mobileVersion) || null,
  };
}

export function deviceReadiness(device: GlassDevice): DeviceReadiness {
  if (device.state === "DISCONNECTED" || device.state === "UNAUTHORIZED") {
    return { ready: false, reason: "disconnected", message: "The phone is not connected to this PC. Check the USB cable or wireless debugging." };
  }
  if (!device.paired) {
    return { ready: false, reason: "unpaired", message: "Pair this phone with Cyclone One first. Pairing moves into Glass in a later alpha." };
  }
  const major = majorVersion(device.mobileVersion);
  if (major === null) {
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
