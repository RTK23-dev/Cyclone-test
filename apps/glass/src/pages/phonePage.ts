/** Phone: live view and control. Checkpoint F adds the live view, take control and Ask. */
import type { GlassContext } from "../app.js";
import { el } from "../ui/dom.js";
import { emptyState, pageHeader } from "../ui/components.js";
import { deviceGate } from "./deviceGate.js";
import type { GlassPage } from "./page.js";

export function createPhonePage(ctx: GlassContext): GlassPage {
  const element = el("div", "page page-phone");
  element.append(pageHeader("Phone", "Watch the phone, take control, or give Cyclone a goal."));
  element.append(deviceGate(ctx) ?? emptyState({ icon: "phone", title: "Live view is not available yet" }));
  return { element, destroy() {} };
}
