/**
 * What Cyclone knows beyond maps (`knowledge.get`): vault slots as set / not set (never values), taught skills and
 * automations, Atlas totals. The phone computes it; Glass only lays it out.
 */
import type { GatewayClient } from "./gateway.js";

export interface VaultSlot {
  placeId: string;
  persona: "live" | "mapping";
  slot: string;
  set: boolean;
  updatedAt: number | null;
}

export interface KnowledgeSummary {
  vault: { slotCount: number; setCount: number; slots: VaultSlot[] };
  skills: Array<{ id: string; name: string; steps: number; enabled: boolean; version: number }>;
  automations: Array<{ id: string; name: string; trigger: string; steps: number; enabled: boolean }>;
  atlas: { places: number; rooms: number; doors: number };
}

export async function getKnowledge(client: GatewayClient, deviceId: string, signal?: AbortSignal): Promise<KnowledgeSummary> {
  return parseKnowledge(await client.get<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/knowledge`, signal));
}

export function parseKnowledge(raw: unknown): KnowledgeSummary {
  const r = record(raw);
  const vault = record(r.vault);
  const atlas = record(r.atlas);
  return {
    vault: {
      slotCount: num(vault.slotCount),
      setCount: num(vault.setCount),
      slots: list(vault.slots)
        .filter((s) => typeof s.placeId === "string" && typeof s.slot === "string")
        .map((s) => ({
          placeId: s.placeId as string,
          persona: s.persona === "mapping" ? "mapping" : "live",
          slot: s.slot as string,
          set: s.set === true,
          updatedAt: typeof s.updatedAt === "number" ? s.updatedAt : null,
        })),
    },
    skills: list(r.skills)
      .filter((s) => typeof s.id === "string")
      .map((s) => ({ id: s.id as string, name: str(s.name) || "Untitled skill", steps: num(s.steps), enabled: s.enabled === true, version: num(s.version) })),
    automations: list(r.automations)
      .filter((s) => typeof s.id === "string")
      .map((s) => ({ id: s.id as string, name: str(s.name) || "Untitled automation", trigger: str(s.trigger), steps: num(s.steps), enabled: s.enabled === true })),
    atlas: { places: num(atlas.places), rooms: num(atlas.rooms), doors: num(atlas.doors) },
  };
}

function list(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? value.map(record) : [];
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
