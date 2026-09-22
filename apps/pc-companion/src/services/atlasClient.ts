/**
 * Glass V5 atlas/secrets client. Phone is source of truth. Glass only commands and displays.
 *
 * Gateway HTTP routes from apps/device-gateway/.../api/v5_contract_api.py,
 * matching CONTRACT.md op names (do not invent a second vocabulary):
 *
 *   atlas.places      GET  /v1/devices/{device_id}/atlas/places
 *   atlas.get         GET  /v1/devices/{device_id}/atlas?placeId=&persona=
 *   secrets.slots     GET  /v1/devices/{device_id}/secrets/slots?placeId=&persona=
 *   secrets.request   POST /v1/devices/{device_id}/secrets/request
 *                     body is frozen to { placeId, persona, slot, reason }
 *
 * session_id is required on every scoped call. Sent as `session_id` query and
 * `X-Cyclone-Session-Id` header. Never placed on the secrets.request JSON body
 * (gateway rejects unexpected fields). Never defaulted to display 0 / default-foreground.
 *
 * This client does not implement mapping.start.
 */

import type {
  AtlasDocument,
  Edge,
  FactSlot,
  Place,
  PlaceCatalog,
  PlaceId,
  PlaceSummary,
  Persona,
  MapStatus,
  Screen,
  SecretsRequestResult,
  SlotPresence,
} from "./atlasTypes.js";
import { ATLAS_DOCUMENT_KEYS, PLACE_SUMMARY_KEYS } from "./atlasTypes.js";
import {
  SECRET_PAYLOAD_REJECTED,
  SecretPayloadRejectedError,
  assertNoSecretValues,
  isSecretLookingKey,
  looksLikeSecretValue,
} from "./secretGuards.js";

export { SECRET_PAYLOAD_REJECTED, SecretPayloadRejectedError, assertNoSecretValues } from "./secretGuards.js";
export { ATLAS_DOCUMENT_KEYS, PLACE_SUMMARY_KEYS } from "./atlasTypes.js";
export type {
  AtlasDocument,
  Place,
  PlaceCatalog,
  PlaceId,
  PlaceSummary,
  Persona,
  MapStatus,
  SecretsRequestResult,
  SecretsRequestStatus,
  SlotPresence,
  SecretRequest,
} from "./atlasTypes.js";

/** Never auto-enable the demo graph for a V5 phone. Demo is an explicit opt-in only. */
export const DEMO_ATLAS_DISABLED_WHEN_PHONE_V5 = true;

const PERSONAS = new Set<Persona>(["live", "mapping"]);
const MAP_STATUSES = new Set<MapStatus>(["unmapped", "partial", "mapped", "stale", "blocked"]);
const PLACE_ID_RE =
  /^(package:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+|chrome:https?:\/\/[^\s/]+(?::[0-9]{1,5})?)$/;
const SLOT_RE = /^[A-Za-z][A-Za-z0-9._-]{0,63}$/;
const REASON_RE = /^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,119}$/;
const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const ORIGIN_RE = /^https?:\/\/[^\s/]+(?::[0-9]{1,5})?$/;

export class AtlasClientError extends Error {
  readonly code: string;
  readonly status?: number;
  readonly retryable: boolean;

  constructor(code: string, message: string, options: { status?: number; retryable?: boolean } = {}) {
    super(message.slice(0, 220));
    this.name = "AtlasClientError";
    this.code = code;
    this.status = options.status;
    this.retryable = options.retryable === true;
  }
}

export interface AtlasClientOptions {
  baseUrl: string;
  getBearer: () => string;
  getSessionId: () => string;
  /** Required by gateway `/v1/devices/{device_id}/…` routes. */
  getDeviceId: () => string;
  getPhoneVersion?: () => string | null | undefined;
  /** Explicit demo path. Default false. Never silently substituted for a connected V5 phone. */
  useDemoGraph?: boolean;
  fetch?: typeof fetch;
}

export interface AtlasClient {
  readonly usingDemoGraph: boolean;
  places(): Promise<PlaceCatalog>;
  get(placeId: PlaceId, persona: Persona): Promise<AtlasDocument>;
  secretsSlots(placeId: PlaceId, persona: Persona): Promise<{ slots: Record<string, boolean> }>;
  secretsRequest(
    placeId: PlaceId,
    persona: Persona,
    slot: string,
    reason: string,
  ): Promise<SecretsRequestResult>;
}

/**
 * Mobile 5.x (including 5.0.0-alpha.1) supports Glass atlas/Vault.
 * Mobile 4.8.0 does not.
 */
export function supportsGlassAtlas(version: string): boolean {
  const trimmed = String(version ?? "").trim().replace(/^v/i, "");
  const match = trimmed.match(/^(\d+)\.(\d+)/);
  if (!match) return false;
  const major = Number(match[1]);
  return major >= 5;
}

export function createAtlasClient(options: AtlasClientOptions): AtlasClient {
  const baseUrl = stripSlash(options.baseUrl);
  const useDemoGraph = options.useDemoGraph === true;
  const fetchImpl = options.fetch ?? fetch.bind(globalThis);

  function sessionId(): string {
    const value = String(options.getSessionId?.() ?? "").trim();
    if (!value) {
      throw new AtlasClientError("SESSION_REQUIRED", "session_id is required on atlas/secrets operations.");
    }
    return value;
  }

  function deviceId(): string {
    const value = String(options.getDeviceId?.() ?? "").trim();
    if (!value) {
      throw new AtlasClientError("DEVICE_REQUIRED", "device_id is required on atlas/secrets operations.");
    }
    return value;
  }

  function bearer(): string {
    const value = String(options.getBearer?.() ?? "").trim();
    if (!value) {
      throw new AtlasClientError("AUTH_REJECTED", "Gateway bearer token is required.");
    }
    return value;
  }

  function assertPhoneCapability(): void {
    const version = options.getPhoneVersion?.();
    if (version == null || version === "") return;
    if (!supportsGlassAtlas(version)) {
      throw new AtlasClientError(
        "PHONE_VERSION_UNSUPPORTED",
        "Update the phone to Cyclone Mobile 5.0 to use Maps, Ask, and Vault.",
      );
    }
  }

  async function requestJson(path: string, init: RequestInit = {}): Promise<unknown> {
    assertPhoneCapability();
    const sid = sessionId();
    const did = deviceId();
    const token = bearer();
    const separator = path.includes("?") ? "&" : "?";
    const url = `${baseUrl}${path.replace("{device_id}", encodeURIComponent(did))}${separator}session_id=${encodeURIComponent(sid)}`;
    const response = await fetchImpl(url, {
      ...init,
      cache: "no-store",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
        "X-Cyclone-Session-Id": sid,
        ...(init.headers ?? {}),
      },
    });
    if (!response.ok) {
      throw await errorFromResponse(response);
    }
    try {
      return await response.json();
    } catch {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Android V5 result must be an object.", {
        status: response.status,
      });
    }
  }

  return {
    usingDemoGraph: useDemoGraph,
    async places(): Promise<PlaceCatalog> {
      if (useDemoGraph) {
        sessionId();
        return demoPlaceCatalog();
      }
      const payload = await requestJson(`/v1/devices/{device_id}/atlas/places`);
      return parsePlaceCatalog(payload);
    },
    async get(placeId: PlaceId, persona: Persona): Promise<AtlasDocument> {
      assertPlacePersona(placeId, persona);
      if (useDemoGraph) {
        sessionId();
        return demoAtlasDocument(placeId, persona);
      }
      const payload = await requestJson(
        `/v1/devices/{device_id}/atlas?placeId=${encodeURIComponent(placeId)}&persona=${encodeURIComponent(persona)}`,
      );
      return parseAtlasDocument(payload, placeId, persona);
    },
    async secretsSlots(placeId: PlaceId, persona: Persona): Promise<{ slots: Record<string, boolean> }> {
      assertPlacePersona(placeId, persona);
      if (useDemoGraph) {
        sessionId();
        return { slots: {} };
      }
      const payload = await requestJson(
        `/v1/devices/{device_id}/secrets/slots?placeId=${encodeURIComponent(placeId)}&persona=${encodeURIComponent(persona)}`,
      );
      return parseSlotPresence(payload, placeId, persona);
    },
    async secretsRequest(
      placeId: PlaceId,
      persona: Persona,
      slot: string,
      reason: string,
    ): Promise<SecretsRequestResult> {
      assertPlacePersona(placeId, persona);
      if (!SLOT_RE.test(slot)) {
        throw new AtlasClientError("INVALID_REQUEST", "slot must be metadata only.");
      }
      if (!REASON_RE.test(reason) || looksLikeSecretValue(reason)) {
        throw new AtlasClientError("INVALID_REQUEST", "reason must be a bounded-safe-label.");
      }
      const body = { placeId, persona, slot, reason };
      assertNoSecretValues(body);
      if (useDemoGraph) {
        sessionId();
        return { status: "waiting" };
      }
      const payload = await requestJson(`/v1/devices/{device_id}/secrets/request`, {
        method: "POST",
        body: JSON.stringify(body),
      });
      return parseSecretsRequestResult(payload);
    },
  };
}

export function parsePlaceCatalog(payload: unknown): PlaceCatalog {
  assertObject(payload, "atlas.places");
  assertNoSecretValues(payload);
  const places = (payload as { places?: unknown }).places;
  if (!Array.isArray(places)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android atlas.places result is malformed.");
  }
  return { places: places.map((item) => parsePlaceSummary(item)) };
}

export function parseAtlasDocument(payload: unknown, expectedPlaceId?: PlaceId, expectedPersona?: Persona): AtlasDocument {
  assertObject(payload, "atlas.get");
  assertNoSecretValues(payload);
  requireKeys(payload, ATLAS_DOCUMENT_KEYS, "Atlas document");
  const record = payload as Record<string, unknown>;
  const place = parsePlace(record.place);
  const persona = parsePersona(record.persona);
  if (expectedPlaceId && place.placeId !== expectedPlaceId) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source returned the wrong placeId.");
  }
  if (expectedPersona && persona !== expectedPersona) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source returned the wrong persona.");
  }
  if (!Array.isArray(record.screens) || !Array.isArray(record.edges) || !Array.isArray(record.capabilities)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas document screens/edges/capabilities must be arrays.");
  }
  return {
    place,
    persona,
    mapStatus: parseMapStatus(record.mapStatus),
    screens: record.screens.map((item) => parseScreen(item)),
    edges: record.edges.map((item) => parseEdge(item)),
    capabilities: record.capabilities.map((item) => parseCapability(item)),
    confidence: parseConfidence(record.confidence),
    lastObservedAt: parseTimestamp(record.lastObservedAt),
    lastVerifiedAt: parseTimestamp(record.lastVerifiedAt),
  };
}

export function parseSlotPresence(payload: unknown, expectedPlaceId?: PlaceId, expectedPersona?: Persona): SlotPresence {
  assertObject(payload, "secrets.slots");
  assertNoSecretValues(payload, { slotPresence: true });
  const record = payload as Record<string, unknown>;
  const placeId = parsePlaceId(record.placeId);
  const persona = parsePersona(record.persona);
  if (expectedPlaceId && placeId !== expectedPlaceId) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android secrets.slots identity mismatch.");
  }
  if (expectedPersona && persona !== expectedPersona) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android secrets.slots identity mismatch.");
  }
  const slotsRaw = record.slots;
  if (slotsRaw === null || typeof slotsRaw !== "object" || Array.isArray(slotsRaw)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Android secrets.slots presence map is malformed.");
  }
  const slots: Record<string, boolean> = {};
  for (const [slot, present] of Object.entries(slotsRaw as Record<string, unknown>)) {
    if (!SLOT_RE.test(slot) || typeof present !== "boolean") {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Secret slot presence must be boolean metadata only.");
    }
    slots[slot] = present;
  }
  return { placeId, persona, slots };
}

export function parseSecretsRequestResult(payload: unknown): SecretsRequestResult {
  assertObject(payload, "secrets.request");
  assertNoSecretValues(payload);
  const record = payload as Record<string, unknown>;
  if (record.state === "needs-secret") {
    return { status: "waiting" };
  }
  const status = record.status ?? record.state;
  if (status === "waiting" || status === "filled" || status === "skipped" || status === "cancelled") {
    return { status };
  }
  throw new AtlasClientError("PROTOCOL_MISMATCH", "Android secrets.request acknowledgement is malformed.");
}

export function unmappedAtlasDocument(placeId: PlaceId, persona: Persona): AtlasDocument {
  return {
    place: placeFromId(placeId),
    persona,
    mapStatus: "unmapped",
    screens: [],
    edges: [],
    capabilities: [],
    confidence: 0,
    lastObservedAt: null,
    lastVerifiedAt: null,
  };
}

function parsePlaceSummary(payload: unknown): PlaceSummary {
  assertObject(payload, "place summary");
  requireKeys(payload, PLACE_SUMMARY_KEYS, "Atlas place summary");
  const record = payload as Record<string, unknown>;
  return {
    place: parsePlace(record.place),
    persona: parsePersona(record.persona),
    mapStatus: parseMapStatus(record.mapStatus),
    confidence: parseConfidence(record.confidence),
    lastObservedAt: parseTimestamp(record.lastObservedAt),
    lastVerifiedAt: parseTimestamp(record.lastVerifiedAt),
  };
}

function parsePlace(payload: unknown): Place {
  assertObject(payload, "place");
  const record = payload as Record<string, unknown>;
  const placeId = parsePlaceId(record.placeId);
  const kind = record.kind;
  const label = record.label;
  if (typeof label !== "string" || label.length < 1 || label.length > 120) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source place label is required.");
  }
  if (kind === "package") {
    const packageName = record.packageName;
    if (typeof packageName !== "string" || !PACKAGE_RE.test(packageName)) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source packageName does not match placeId.");
    }
    if (!placeId.startsWith("package:") || placeId.slice("package:".length) !== packageName) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source packageName does not match placeId.");
    }
    return { placeId, kind, label, packageName };
  }
  if (kind === "chrome-origin") {
    const origin = record.origin;
    if (typeof origin !== "string" || !ORIGIN_RE.test(origin.replace(/\/$/, ""))) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source origin does not match placeId.");
    }
    const canonical = origin.replace(/\/$/, "");
    if (!placeId.startsWith("chrome:") || placeId.slice("chrome:".length) !== canonical) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source origin does not match placeId.");
    }
    return { placeId, kind, label, origin: canonical };
  }
  throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source place kind does not match placeId.");
}

function parsePlaceId(value: unknown): PlaceId {
  if (typeof value !== "string" || !PLACE_ID_RE.test(value)) {
    throw new AtlasClientError("INVALID_REQUEST", "Invalid placeId.");
  }
  return value;
}

function parsePersona(value: unknown): Persona {
  if (value !== "live" && value !== "mapping") {
    throw new AtlasClientError("INVALID_REQUEST", "persona must be live or mapping.");
  }
  return value;
}

function parseMapStatus(value: unknown): MapStatus {
  if (typeof value !== "string" || !MAP_STATUSES.has(value as MapStatus)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas source returned an invalid mapStatus.");
  }
  return value as MapStatus;
}

function parseConfidence(value: unknown): number {
  if (typeof value !== "number" || Number.isNaN(value) || value < 0 || value > 1) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas confidence must be a number between 0 and 1.");
  }
  return value;
}

function parseTimestamp(value: unknown): string | null {
  if (value === null) return null;
  if (typeof value === "string") return value;
  throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas timestamp must be a date-time string or null.");
}

function parseScreen(payload: unknown): Screen {
  assertObject(payload, "screen");
  assertNoSecretValues(payload);
  const record = payload as Record<string, unknown>;
  if (typeof record.screenId !== "string" || !record.screenId || typeof record.purpose !== "string" || !record.purpose) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas screen is malformed.");
  }
  if (!Array.isArray(record.factSlots)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas screen factSlots must be an array.");
  }
  return {
    screenId: record.screenId,
    label: typeof record.label === "string" ? record.label : undefined,
    purpose: record.purpose,
    factSlots: record.factSlots.map((item) => parseFactSlot(item)),
    risk: parseRisk(record.risk),
    confidence: parseConfidence(record.confidence),
    lastObservedAt: parseTimestamp(record.lastObservedAt),
    lastVerifiedAt: parseTimestamp(record.lastVerifiedAt),
    layout: parseLayout(record.layout),
  };
}

function parseFactSlot(payload: unknown): FactSlot {
  assertObject(payload, "factSlot");
  const record = payload as Record<string, unknown>;
  if (typeof record.name !== "string" || !record.name) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas factSlot is malformed.");
  }
  if (isSecretLookingKey(record.name)) {
    throw new SecretPayloadRejectedError();
  }
  const factType = record.factType;
  if (
    factType !== "text" &&
    factType !== "boolean" &&
    factType !== "number" &&
    factType !== "timestamp" &&
    factType !== "identifier"
  ) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas factSlot is malformed.");
  }
  if (typeof record.required !== "boolean") {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas factSlot is malformed.");
  }
  return {
    name: record.name,
    factType,
    required: record.required,
    description: typeof record.description === "string" ? record.description : undefined,
  };
}

function parseEdge(payload: unknown): Edge {
  assertObject(payload, "edge");
  const record = payload as Record<string, unknown>;
  if (
    typeof record.edgeId !== "string" ||
    typeof record.fromScreenId !== "string" ||
    typeof record.toScreenId !== "string" ||
    typeof record.actionHint !== "string"
  ) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas edge is malformed.");
  }
  return {
    edgeId: record.edgeId,
    fromScreenId: record.fromScreenId,
    toScreenId: record.toScreenId,
    actionHint: record.actionHint,
    risk: parseRisk(record.risk),
    confidence: parseConfidence(record.confidence),
    lastVerifiedAt: parseTimestamp(record.lastVerifiedAt),
  };
}

function parseRisk(payload: unknown): { danger: boolean; classes: string[] } {
  assertObject(payload, "risk");
  const record = payload as Record<string, unknown>;
  if (typeof record.danger !== "boolean" || !Array.isArray(record.classes)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas risk is malformed.");
  }
  return {
    danger: record.danger,
    classes: record.classes.map((item) => {
      if (typeof item !== "string") throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas risk is malformed.");
      return item;
    }),
  };
}

function parseLayout(payload: unknown): { x: number; y: number } {
  assertObject(payload, "layout");
  const record = payload as Record<string, unknown>;
  if (typeof record.x !== "number" || typeof record.y !== "number") {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas layout is malformed.");
  }
  return { x: record.x, y: record.y };
}

function parseCapability(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z][A-Za-z0-9._-]{0,79}$/.test(value)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", "Atlas capability is malformed.");
  }
  if (isSecretLookingKey(value)) throw new SecretPayloadRejectedError();
  return value;
}

function placeFromId(placeId: PlaceId): Place {
  if (!PLACE_ID_RE.test(placeId)) {
    throw new AtlasClientError("INVALID_REQUEST", "Invalid placeId.");
  }
  if (placeId.startsWith("package:")) {
    const packageName = placeId.slice("package:".length);
    const label = packageName.split(".").pop() || packageName;
    return { placeId, kind: "package", label, packageName };
  }
  const origin = placeId.slice("chrome:".length);
  let label = origin.replace(/^https?:\/\//, "");
  try {
    label = new URL(origin).hostname || label;
  } catch {
    /* bounded fallback: origin without scheme */
  }
  return { placeId, kind: "chrome-origin", label, origin };
}

function assertPlacePersona(placeId: PlaceId, persona: Persona): void {
  if (!PLACE_ID_RE.test(placeId)) {
    throw new AtlasClientError("INVALID_REQUEST", "Invalid placeId.");
  }
  if (!PERSONAS.has(persona)) {
    throw new AtlasClientError("INVALID_REQUEST", "persona must be live or mapping.");
  }
}

function assertObject(payload: unknown, label: string): asserts payload is Record<string, unknown> {
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) {
    throw new AtlasClientError("PROTOCOL_MISMATCH", `Android ${label} result must be an object.`);
  }
}

function requireKeys(payload: Record<string, unknown>, keys: readonly string[], label: string): void {
  for (const key of keys) {
    if (!(key in payload)) {
      throw new AtlasClientError("PROTOCOL_MISMATCH", `${label} is missing required key ${key}.`);
    }
  }
}

function stripSlash(value: string): string {
  return value.replace(/\/$/, "");
}

function demoPlaceCatalog(): PlaceCatalog {
  const document = demoAtlasDocument("package:com.google.android.gm", "live");
  return {
    places: [
      {
        place: document.place,
        persona: document.persona,
        mapStatus: document.mapStatus,
        confidence: document.confidence,
        lastObservedAt: document.lastObservedAt,
        lastVerifiedAt: document.lastVerifiedAt,
      },
    ],
  };
}

function demoAtlasDocument(placeId: PlaceId, persona: Persona): AtlasDocument {
  if (placeId !== "package:com.google.android.gm") {
    const empty = unmappedAtlasDocument(placeId, persona);
    return { ...empty, place: { ...empty.place, label: `${empty.place.label} (demo)` } };
  }
  return {
    place: {
      placeId: "package:com.google.android.gm",
      kind: "package",
      label: "Gmail (demo)",
      packageName: "com.google.android.gm",
    },
    persona,
    mapStatus: "partial",
    screens: [
      {
        screenId: "demo.inbox",
        label: "Inbox",
        purpose: "List messages",
        factSlots: [],
        risk: { danger: false, classes: [] },
        confidence: 0.4,
        lastObservedAt: null,
        lastVerifiedAt: null,
        layout: { x: 0, y: 0 },
      },
      {
        screenId: "demo.compose",
        label: "Compose",
        purpose: "Write a message",
        factSlots: [{ name: "recipient", factType: "identifier", required: true }],
        risk: { danger: false, classes: [] },
        confidence: 0.4,
        lastObservedAt: null,
        lastVerifiedAt: null,
        layout: { x: 240, y: 0 },
      },
    ],
    edges: [
      {
        edgeId: "demo.inbox.compose",
        fromScreenId: "demo.inbox",
        toScreenId: "demo.compose",
        actionHint: "Compose",
        risk: { danger: false, classes: [] },
        confidence: 0.4,
        lastVerifiedAt: null,
      },
    ],
    capabilities: ["DemoGraph"],
    confidence: 0.4,
    lastObservedAt: null,
    lastVerifiedAt: null,
  };
}

async function errorFromResponse(response: Response): Promise<AtlasClientError> {
  const status = response.status;
  let code = status >= 400 && status < 500 ? `HTTP_${status}` : "HTTP_ERROR";
  let message = `Cyclone atlas request failed (${status})`;
  let retryable = status >= 500;
  try {
    const body = await response.json();
    try {
      assertNoSecretValues(body);
    } catch (error) {
      if (error instanceof SecretPayloadRejectedError) {
        return new AtlasClientError(SECRET_PAYLOAD_REJECTED, error.message, { status });
      }
      throw error;
    }
    const detail = (body as { detail?: unknown }).detail;
    if (typeof detail === "string" && detail.trim()) {
      message = detail;
    } else if (detail && typeof detail === "object") {
      const record = detail as { code?: unknown; message?: unknown; retryable?: unknown };
      if (typeof record.code === "string" && record.code.trim()) code = record.code.trim();
      if (typeof record.message === "string" && record.message.trim()) message = record.message;
      if (typeof record.retryable === "boolean") retryable = record.retryable;
    }
  } catch (error) {
    if (error instanceof AtlasClientError) return error;
  }
  return new AtlasClientError(code, message, { status, retryable });
}
