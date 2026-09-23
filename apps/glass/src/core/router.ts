/** Hash routes. Hash routing keeps the gateway's static mount trivial (one index.html under /glass/). */
export type AppTab = "map";

export type Route =
  | { name: "apps" }
  | { name: "app"; placeId: string; tab: AppTab }
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
    case "phone":
      return "#/phone";
    case "settings":
      return "#/settings";
  }
}

/** Sidebar section that owns a route. */
export function sectionOf(route: Route): "apps" | "phone" | "settings" {
  return route.name === "app" ? "apps" : route.name;
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return "";
  }
}
