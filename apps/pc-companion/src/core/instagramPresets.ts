import type { DesktopService, Layer2Status } from "../services/types.js";

export const INSTAGRAM_ANDROID_PACKAGE = "com.instagram.android";
export const INSTAGRAM_PRESET_SOURCE_REPOSITORY = "kevinbadi/Kevs-IOS-Agents";
export const INSTAGRAM_PRESET_SOURCE_REF = "b909752df7af7a595714ed660af7cc971ec408d5";

export type InstagramPresetId = "warmup" | "engage-following" | "cold-dms" | "post";
export type InstagramPersonality = "skimmer" | "casual" | "engaged" | "dialed";
export type InstagramPostDestination = "draft" | "publish";

export interface InstagramPresetDefinition {
  id: InstagramPresetId;
  sourceTaskType: "doomscroll" | "doomscroll-following" | "cold-dms" | "post";
  title: string;
  description: string;
  workspaceId: string;
  requiresSendGate: boolean;
}

export interface InstagramPresetParams {
  account?: string;
  durationMinutes?: number;
  personality?: InstagramPersonality;
  likeEnabled?: boolean;
  commentEnabled?: boolean;
  commentText?: string;
  handles?: string[];
  message?: string;
  cycles?: number;
  destination?: InstagramPostDestination;
  caption?: string;
  mediaInstructions?: string;
  musicUrl?: string;
  publishConfirmed?: boolean;
}

export interface InstagramPresetInvocation {
  preset: InstagramPresetDefinition;
  appPackage: typeof INSTAGRAM_ANDROID_PACKAGE;
  goal: string;
  sourceRepository: typeof INSTAGRAM_PRESET_SOURCE_REPOSITORY;
  sourceRef: typeof INSTAGRAM_PRESET_SOURCE_REF;
}

export const INSTAGRAM_PRESETS: readonly InstagramPresetDefinition[] = [
  {
    id: "warmup",
    sourceTaskType: "doomscroll",
    title: "Instagram warmup",
    description: "Browse the Instagram home feed with personality-based pacing and optional likes/comments.",
    workspaceId: "instagram-warmup",
    requiresSendGate: false,
  },
  {
    id: "engage-following",
    sourceTaskType: "doomscroll-following",
    title: "Engage following",
    description: "Browse the Following feed with the same bounded personality and engagement controls.",
    workspaceId: "instagram-following",
    requiresSendGate: false,
  },
  {
    id: "cold-dms",
    sourceTaskType: "cold-dms",
    title: "Cold DMs",
    description: "Send one verified message to an explicit bounded list of Instagram handles.",
    workspaceId: "instagram-cold-dms",
    requiresSendGate: true,
  },
  {
    id: "post",
    sourceTaskType: "post",
    title: "Instagram post",
    description: "Create a verified Instagram draft or publish after explicit confirmation.",
    workspaceId: "instagram-post",
    requiresSendGate: true,
  },
] as const;

const PRESET_BY_ID = new Map(INSTAGRAM_PRESETS.map((preset) => [preset.id, preset]));
const PERSONAS = new Set<InstagramPersonality>(["skimmer", "casual", "engaged", "dialed"]);

export function defaultInstagramPresetParams(id: InstagramPresetId): InstagramPresetParams {
  switch (id) {
    case "warmup":
    case "engage-following":
      return { durationMinutes: 15, personality: "casual", likeEnabled: true, commentEnabled: false };
    case "cold-dms":
      return { handles: [], message: "", cycles: 1 };
    case "post":
      return { destination: "draft", caption: "", mediaInstructions: "", publishConfirmed: false };
  }
}

export function buildInstagramPresetInvocation(
  id: InstagramPresetId,
  raw: InstagramPresetParams,
): InstagramPresetInvocation {
  const preset = PRESET_BY_ID.get(id);
  if (!preset) throw new Error("Unknown Instagram preset");
  const account = cleanAccount(raw.account);
  const goal = id === "warmup" || id === "engage-following"
    ? engagementGoal(id, raw, account)
    : id === "cold-dms"
      ? coldDmGoal(raw, account)
      : postGoal(raw, account);
  return {
    preset,
    appPackage: INSTAGRAM_ANDROID_PACKAGE,
    goal,
    sourceRepository: INSTAGRAM_PRESET_SOURCE_REPOSITORY,
    sourceRef: INSTAGRAM_PRESET_SOURCE_REF,
  };
}

export async function queueInstagramPreset(
  service: DesktopService,
  deviceId: string,
  invocation: InstagramPresetInvocation,
): Promise<Layer2Status> {
  if (!deviceId.trim()) throw new Error("Choose a trusted phone first");
  if (!service.listLayer2Workspaces || !service.layer2Workspace) {
    throw new Error("Instagram presets require Cyclone One Layer 2 support");
  }
  const current = await service.listLayer2Workspaces(deviceId);
  const existing = current.workspaces.find((workspace) => workspace.id === invocation.preset.workspaceId);
  if (existing && existing.appPackage !== invocation.appPackage) {
    throw new Error("Preset workspace exists with a different Android package");
  }
  if (!existing) {
    await service.layer2Workspace(deviceId, "register", {
      id: invocation.preset.workspaceId,
      label: invocation.preset.title,
      appPackage: invocation.appPackage,
      androidUserId: 0,
      displayId: 0,
    });
  }
  return service.layer2Workspace(deviceId, "arm", {
    id: invocation.preset.workspaceId,
    goal: invocation.goal,
  });
}

function engagementGoal(id: "warmup" | "engage-following", raw: InstagramPresetParams, account?: string): string {
  const duration = boundedInteger(raw.durationMinutes, 15, 1, 180, "Duration");
  const personality = raw.personality ?? "casual";
  if (!PERSONAS.has(personality)) throw new Error("Unknown Instagram personality");
  const likes = raw.likeEnabled !== false;
  const comments = raw.commentEnabled === true;
  const commentText = String(raw.commentText ?? "").trim();
  if (comments && !commentText) throw new Error("Comment text is required when comments are enabled");
  if (commentText.length > 150) throw new Error("Comment text must be 150 characters or fewer");
  const feed = id === "engage-following" ? "Following feed" : "home feed";
  return boundedGoal([
    `Open Instagram (${INSTAGRAM_ANDROID_PACKAGE})${account ? ` and use account @${account}` : ""}.`,
    `For ${duration} minutes, browse the ${feed} with the ${personality} pacing style: vary dwell and scroll timing naturally instead of repeating a fixed cadence.`,
    likes ? "Like only when the currently observed post is a reasonable engagement candidate; verify the like state after acting." : "Do not like posts.",
    comments
      ? `Comments are allowed only with this exact operator-provided text: ${quote(commentText)}. Treat posting a comment as SEND: respect Cyclone GATE/human confirmation and verify the comment after submission.`
      : "Do not post comments.",
    "After every interaction, re-observe and verify semantic progress. Recover from transient overlays by re-observing; do not repeat a click merely because the screen was slow to change.",
    "Stop rather than bypass login, challenge, permission, safety, or human-confirmation boundaries.",
  ]);
}

function coldDmGoal(raw: InstagramPresetParams, account?: string): string {
  const handles = normalizeHandles(raw.handles ?? []);
  if (handles.length === 0) throw new Error("Add at least one Instagram handle");
  if (handles.length > 25) throw new Error("Cold DMs are limited to 25 explicit handles per preset run");
  const message = String(raw.message ?? "").trim();
  if (!message) throw new Error("DM message is required");
  if (message.length > 2000) throw new Error("DM message is too long");
  const cycles = boundedInteger(raw.cycles, 1, 1, 10, "Cycles");
  return boundedGoal([
    `Open Instagram (${INSTAGRAM_ANDROID_PACKAGE})${account ? ` and use account @${account}` : ""}.`,
    `Process only these explicit recipients: ${handles.map((handle) => `@${handle}`).join(", ")}. Run at most ${cycles} cycle${cycles === 1 ? "" : "s"}.`,
    `For each recipient, verify the profile/thread identity before typing. Send this exact operator-provided message: ${quote(message)}.`,
    "Every DM is an external SEND action: never bypass Cyclone GATE or human confirmation. If confirmation is unavailable, pause that recipient instead of sending.",
    "After Send, re-observe and require recipient/thread evidence plus a sent-state witness (for example composer reset or the new message appearing) before marking that recipient complete.",
    "If recipient identity is ambiguous, the account is private/unavailable, a challenge appears, or verification fails, do not guess and do not retry a blind second Send.",
  ]);
}

function postGoal(raw: InstagramPresetParams, account?: string): string {
  const destination = raw.destination ?? "draft";
  if (destination !== "draft" && destination !== "publish") throw new Error("Post destination must be draft or publish");
  if (destination === "publish" && raw.publishConfirmed !== true) {
    throw new Error("Publishing requires explicit confirmation; choose draft or confirm publish");
  }
  const caption = String(raw.caption ?? "").trim();
  if (caption.length > 2200) throw new Error("Caption must be 2200 characters or fewer");
  const media = String(raw.mediaInstructions ?? "").trim();
  if (!media) throw new Error("Describe which 1–3 phone media items should be selected");
  const musicUrl = String(raw.musicUrl ?? "").trim();
  if (musicUrl && !/^https:\/\/(?:www\.)?instagram\.com\//i.test(musicUrl)) {
    throw new Error("Music URL must be an HTTPS Instagram URL");
  }
  return boundedGoal([
    `Open Instagram (${INSTAGRAM_ANDROID_PACKAGE})${account ? ` and use account @${account}` : ""}.`,
    `Create a post using 1–3 phone media items matching this operator description: ${quote(media)}. Never substitute uncertain media; if the requested items cannot be identified, pause for human selection.`,
    caption ? `Use this exact caption: ${quote(caption)}.` : "Leave the caption empty.",
    musicUrl ? `Use the Instagram music reference ${musicUrl} only if the app exposes a verifiable matching music flow.` : "Do not add music unless already selected by the operator.",
    destination === "draft"
      ? "Save the post as a draft. Verify that Instagram shows a draft/saved-state witness; do not publish."
      : "Publish only after Cyclone's SEND/human confirmation is satisfied. Re-observe after the publish action and require a posted-state witness before declaring success.",
    "Treat picker selection, Next transitions, caption entry, draft/publish, and any account switch as separately verified steps. Never retry the final draft/publish control blindly.",
  ]);
}

function cleanAccount(value?: string): string | undefined {
  const clean = String(value ?? "").trim().replace(/^@/, "");
  if (!clean) return undefined;
  if (!/^[A-Za-z0-9._]{1,30}$/.test(clean)) throw new Error("Instagram account must be a valid handle");
  return clean;
}

function normalizeHandles(values: string[]): string[] {
  const seen = new Set<string>();
  for (const value of values) {
    for (const token of String(value).split(/[\s,]+/)) {
      const handle = token.trim().replace(/^@/, "").toLowerCase();
      if (!handle) continue;
      if (!/^[a-z0-9._]{1,30}$/.test(handle)) throw new Error(`Invalid Instagram handle: ${token}`);
      seen.add(handle);
    }
  }
  return [...seen];
}

function boundedInteger(value: number | undefined, fallback: number, min: number, max: number, label: string): number {
  const selected = value == null ? fallback : Number(value);
  if (!Number.isInteger(selected) || selected < min || selected > max) {
    throw new Error(`${label} must be an integer from ${min} to ${max}`);
  }
  return selected;
}

function quote(value: string): string {
  return `“${value.replace(/[\r\n]+/g, " ").trim()}”`;
}

function boundedGoal(parts: string[]): string {
  const goal = parts.filter(Boolean).join(" ").replace(/\s+/g, " ").trim();
  if (goal.length > 500) {
    // Layer 2 deliberately caps stored goals at 500 characters. Keep the highest-value safety and
    // task semantics rather than silently relying on the Gateway to truncate arbitrary text.
    return `${goal.slice(0, 496).trimEnd()} …`;
  }
  return goal;
}
