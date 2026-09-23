/** Glass component set. Every page composes these; pages do not invent their own chrome. */
import { el, button } from "./dom.js";
import { icon, type IconName } from "./icons.js";

export type Tone = "neutral" | "accent" | "success" | "warning" | "danger";

export function pageHeader(title: string, subtitle?: string, actions: Node[] = []): HTMLElement {
  const header = el("header", "page-header");
  const titles = el("div", "page-titles");
  titles.append(el("h1", "page-title", title));
  if (subtitle) titles.append(el("p", "page-subtitle", subtitle));
  header.append(titles);
  if (actions.length) {
    const bar = el("div", "page-actions");
    bar.append(...actions);
    header.append(bar);
  }
  return header;
}

export function chip(label: string, tone: Tone = "neutral"): HTMLSpanElement {
  const node = el("span", `chip chip-${tone}`, label);
  return node;
}

export function card(className = ""): HTMLElement {
  return el("section", className ? `card ${className}` : "card");
}

export function iconButton(name: IconName, label: string, className = "btn btn-ghost"): HTMLButtonElement {
  const node = button("", className);
  node.setAttribute("aria-label", label);
  node.title = label;
  node.append(icon(name));
  return node;
}

export function actionButton(label: string, options: { icon?: IconName; variant?: "primary" | "secondary" | "ghost" | "danger" } = {}): HTMLButtonElement {
  const node = button("", `btn btn-${options.variant ?? "secondary"}`);
  if (options.icon) node.append(icon(options.icon));
  node.append(el("span", "btn-label", label));
  return node;
}

export interface EmptyStateOptions {
  icon?: IconName;
  tone?: Tone;
  title: string;
  body?: string;
  action?: HTMLElement;
}

export function emptyState(options: EmptyStateOptions): HTMLElement {
  const node = el("div", `empty-state empty-${options.tone ?? "neutral"}`);
  if (options.icon) {
    const badge = el("div", "empty-icon");
    badge.append(icon(options.icon));
    node.append(badge);
  }
  node.append(el("h2", "empty-title", options.title));
  if (options.body) node.append(el("p", "empty-body", options.body));
  if (options.action) node.append(options.action);
  return node;
}

export function loadingState(label = "Loading…"): HTMLElement {
  const node = el("div", "loading-state");
  node.setAttribute("role", "status");
  node.append(el("span", "spinner"), el("span", "loading-label", label));
  return node;
}

export function keyValue(rows: Array<[string, string | Node]>): HTMLElement {
  const list = el("dl", "kv");
  for (const [key, value] of rows) {
    list.append(el("dt", "kv-key", key));
    const dd = el("dd", "kv-value");
    if (typeof value === "string") dd.textContent = value;
    else dd.append(value);
    list.append(dd);
  }
  return list;
}
