/** Hash routes. Hash routing keeps the gateway's static mount trivial (one index.html under /glass/). */
export type AppTab = "map";

export type Route =
  | { name: "apps" }
  | { name: "app"; placeId: string; tab: AppTab }
  | { name: "runs" }
  | { name: "run"; runId: string }
  | { name: "phone" }
  | { name: "settings" };

export const DEFAULT_ROUTE: Route = { name: "apps" };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, "").split(/[?&]/, 1)[0] ?? "";
  const parts = path.split("/").filter(Boolean);
  if (parts[0] === "apps" && parts.length >= 2) {
    const placeId = safeDecode(parts[1] ?? "");
    if (placeId) return { name: "app", placeId, tab: "map" };
    return DEFAULT_ROUTE;
  }
  if (parts[0] === "runs" && parts.length >= 2) {
    const runId = safeDecode(parts[1] ?? "");
    return /^[A-Za-z0-9_-]{4,120}$/.test(runId) ? { name: "run", runId } : { name: "runs" };
  }
  if (parts[0] === "runs") return { name: "runs" };
  if (parts[0] === "phone") return { name: "phone" };
  if (parts[0] === "settings") return { name: "settings" };
  return DEFAULT_ROUTE;
}

export function routeHref(route: Route): string {
  switch (route.name) {
    case "apps":
      return "#/apps";
    case "app":
      return `#/apps/${encodeURIComponent(route.placeId)}/${route.tab}`;
    case "runs":
      return "#/runs";
    case "run":
      return `#/runs/${encodeURIComponent(route.runId)}`;
    case "phone":
      return "#/phone";
    case "settings":
      return "#/settings";
  }
}

/** Sidebar section that owns a route. */
export function sectionOf(route: Route): "apps" | "runs" | "phone" | "settings" {
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
