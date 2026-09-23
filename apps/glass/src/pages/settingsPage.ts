/** Settings: connection facts. Read-only in alpha.1; pairing stays in Cyclone One for now. */
import type { GlassContext } from "../app.js";
import { deviceReadiness } from "../services/devices.js";
import { el } from "../ui/dom.js";
import { actionButton, card, chip, keyValue, pageHeader } from "../ui/components.js";
import type { GlassPage } from "./page.js";

export function createSettingsPage(ctx: GlassContext): GlassPage {
  const element = el("div", "page page-settings");
  const refresh = actionButton("Refresh", { icon: "refresh" });
  refresh.addEventListener("click", () => void ctx.refreshDevices());
  element.append(pageHeader("Settings", "How Glass is connected to Cyclone on this PC.", [refresh]));

  const connection = card();
  connection.append(el("h2", "card-title", "Connection"));
  connection.append(
    keyValue([
      ["Glass", ctx.version],
      ["Gateway", ctx.devicesError ? chip(ctx.devicesError.code === "SESSION_EXPIRED" ? "Session ended" : "Not answering", "danger") : chip("Connected", "success")],
      ["Served from", ctx.client.baseUrl || "this PC (127.0.0.1)"],
      ["Intelligence", "None in Glass. Cyclone Mobile runs every decision."],
    ]),
  );

  const phones = card();
  phones.append(el("h2", "card-title", "Phones"));
  if (!ctx.devices.length) {
    phones.append(el("p", "muted", ctx.devicesError ? "Unavailable while the gateway is not answering." : "No phone connected."));
  } else {
    const list = el("ul", "device-list");
    for (const device of ctx.devices) {
      const readiness = deviceReadiness(device);
      const row = el("li", "device-row");
      const names = el("div", "device-names");
      names.append(el("span", "device-name", device.name), el("span", "muted", device.connectionLabel || device.model));
      row.append(
        names,
        chip(device.mobileVersion ? `Cyclone ${device.mobileVersion}` : "Version unknown", "neutral"),
        chip(readiness.ready ? "Ready" : readiness.reason.replace("-", " "), readiness.ready ? "success" : "warning"),
      );
      list.append(row);
    }
    phones.append(list);
  }

  const pairing = card();
  pairing.append(el("h2", "card-title", "Pairing"));
  pairing.append(el("p", "muted", "Pair and unpair phones in Cyclone One for now. Glass picks up paired phones automatically."));

  element.append(connection, phones, pairing);
  return { element, destroy() {} };
}
