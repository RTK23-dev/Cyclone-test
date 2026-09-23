/** Apps (home). Checkpoint D fills this with the phone's app list; until then it shows readiness honestly. */
import type { GlassContext } from "../app.js";
import type { Route } from "../core/router.js";
import { el } from "../ui/dom.js";
import { emptyState, pageHeader } from "../ui/components.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

export function createAppsPage(ctx: GlassContext, _route: Route): GlassPage {
  const element = el("div", "page page-apps");
  element.append(pageHeader("Apps", "Every app on this phone, what Cyclone has mapped, and on which version."));
  element.append(
    deviceGate(ctx) ??
      emptyState({ icon: "apps", title: "App list not available yet", body: "This phone has not shared its app list with Glass." }),
  );
  return { element, destroy() {} };
}
