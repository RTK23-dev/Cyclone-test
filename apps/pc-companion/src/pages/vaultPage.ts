import "../vault.css";
import {
  GLASS_UPDATE_PHONE_COPY,
  GLASS_UPDATE_PHONE_TITLE,
  GLASS_VAULT_FIXTURE_SLOTS,
  phoneSupportsGlassAtlas,
  vaultSlotPresenceLabel,
  type GlassVaultSlot,
} from "../core/fleet.js";
import type { DesktopDevice } from "../services/types.js";
import { el } from "../ui/dom.js";

export interface VaultPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export interface VaultPageOptions {
  devices?: DesktopDevice[];
  mobileVersion?: string;
  slots?: readonly GlassVaultSlot[];
}

export function createVaultPage(options: VaultPageOptions = {}): VaultPageHandle {
  const atlasReady = phoneSupportsGlassAtlas(options.mobileVersion ?? "");
  const slots = options.slots ?? GLASS_VAULT_FIXTURE_SLOTS;
  const live = Boolean(options.slots) && atlasReady;

  const page = el("section", "page content-page vault-page");
  const header = el("header", "page-header");
  header.append(
    el("div", "vault-kicker", "CYCLONE GLASS · VAULT"),
    el("h1", "page-title", "Vault"),
    el("p", "page-subtitle", "Slot presence only. Glass never sees, stores, or displays secret values."),
  );

  const banner = el("aside", "glass-compat-banner");
  banner.hidden = atlasReady;
  banner.append(
    el("div", "glass-compat-title", GLASS_UPDATE_PHONE_TITLE),
    el("p", "glass-compat-copy", GLASS_UPDATE_PHONE_COPY),
  );

  const note = el("p", "vault-source-note", live
    ? "Presence from the connected phone. Values stay in Android Keystore."
    : "Sample inventory — not live. secrets.slots is not on this shell.");

  const list = el("div", "vault-slot-list");
  if (slots.length === 0) {
    list.append(el("div", "vault-empty", "No vault slots to show."));
  } else {
    for (const slot of slots) list.append(renderSlotRow(slot));
  }

  const privacy = el("p", "vault-privacy", "No password, OTP, cookie, or token fields exist on this page. Cyclone will not keep secrets in chat or logs.");

  page.append(header, banner, note, list, privacy);
  return {
    element: page,
    destroy: () => undefined,
  };
}

function renderSlotRow(slot: GlassVaultSlot): HTMLElement {
  const row = el("article", "vault-slot-row");
  row.dataset.slotId = slot.id;
  row.dataset.slotSet = slot.set ? "true" : "false";
  const label = el("div", "vault-slot-label", vaultSlotPresenceLabel(slot));
  const pill = el("span", `vault-slot-pill vault-slot-${slot.set ? "set" : "missing"}`, slot.set ? "set" : "missing");
  row.append(label, pill);
  return row;
}
