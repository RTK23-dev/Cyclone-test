/** Hash routes. Hash routing keeps the gateway's static mount trivial (one index.html under /glass/). */
export type AppTab = "map" | "scenarios" | "versions";
const APP_TABS: AppTab[] = ["map", "scenarios", "versions"];

export type Route =
  | { name: "apps" }
  | { name: "app"; placeId: string; tab: AppTab; route?: string[]; runId?: string }
  | { name: "runs" }
  | { name: "run"; runId: string }
  | { name: "phone" }
  | { name: "devices" }
  | { name: "settings" };

export const DEFAULT_ROUTE: Route = { name: "apps" };

const ROOM_ID = /^screen:[a-z_]{1,40}:[0-9a-f]{8,64}$/;

export function parseRoute(hash: string): Route {
  const raw = hash.replace(/^#/, "");
  const path = raw.split(/[?&]/, 1)[0] ?? "";
  const query = new URLSearchParams(raw.includes("?") ? raw.slice(raw.indexOf("?") + 1) : "");
  const parts = path.split("/").filter(Boolean);
  if (parts[0] === "apps" && parts.length >= 2) {
    const placeId = safeDecode(parts[1] ?? "");
    if (!placeId) return DEFAULT_ROUTE;
    const route = (query.get("route") ?? "").split(",").filter((id) => ROOM_ID.test(id)).slice(0, 60);
    const runId = query.get("run") ?? "";
    const tab = APP_TABS.includes(parts[2] as AppTab) ? (parts[2] as AppTab) : "map";
    return {
      name: "app",
      placeId,
      tab,
      ...(route.length ? { route } : {}),
      ...(/^[A-Za-z0-9_-]{4,120}$/.test(runId) ? { runId } : {}),
    };
  }
  if (parts[0] === "runs" && parts.length >= 2) {
    const runId = safeDecode(parts[1] ?? "");
    return /^[A-Za-z0-9_-]{4,120}$/.test(runId) ? { name: "run", runId } : { name: "runs" };
  }
  if (parts[0] === "runs") return { name: "runs" };
  if (parts[0] === "phone") return { name: "phone" };
  if (parts[0] === "settings") return { name: "settings" };
  if (parts[0] === "devices") return { name: "devices" };
  return DEFAULT_ROUTE;
}

export function routeHref(route: Route): string {
  switch (route.name) {
    case "apps":
      return "#/apps";
    case "app": {
      const base = `#/apps/${encodeURIComponent(route.placeId)}/${route.tab}`;
      const query = new URLSearchParams();
      if (route.route?.length) query.set("route", route.route.join(","));
      if (route.runId) query.set("run", route.runId);
      const text = query.toString();
      return text ? `${base}?${text}` : base;
    }
    case "runs":
      return "#/runs";
    case "run":
      return `#/runs/${encodeURIComponent(route.runId)}`;
    case "phone":
      return "#/phone";
    case "devices":
      return "#/devices";
    case "settings":
      return "#/settings";
  }
}

/** Sidebar section that owns a route. */
export function sectionOf(route: Route): "apps" | "runs" | "phone" | "devices" | "settings" {
  if (route.name === "app") return "apps";
  if (route.name === "run") return "runs";
  return route.name;
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return "";
  }
}
