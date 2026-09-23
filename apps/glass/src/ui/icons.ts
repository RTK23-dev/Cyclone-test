/** Stroke icons (24px grid, currentColor). Paths only, no external assets, so the strict CSP stays `self`. */
const SVG_NS = "http://www.w3.org/2000/svg";

const PATHS = {
  apps: ["M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z"],
  phone: ["M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2"],
  settings: ["M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M4 12h2M18 12h2M12 4v2M12 18v2M6.3 6.3l1.4 1.4M16.3 16.3l1.4 1.4M6.3 17.7l1.4-1.4M16.3 7.7l1.4-1.4"],
  map: ["M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2z", "M9 4v14M15 6v14"],
  search: ["M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M20 20l-4-4"],
  refresh: ["M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7"],
  play: ["M7 4l13 8-13 8z"],
  pause: ["M7 4h3v16H7zM14 4h3v16h-3z"],
  stop: ["M6 6h12v12H6z"],
  back: ["M15 18l-6-6 6-6"],
  chevron: ["M9 6l6 6-6 6"],
  alert: ["M12 3l10 18H2z", "M12 10v4M12 17v.5"],
  plug: ["M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0z", "M12 17v4"],
  hand: ["M8 13V5a1.5 1.5 0 0 1 3 0v6", "M11 11V4a1.5 1.5 0 0 1 3 0v7", "M14 11V6a1.5 1.5 0 0 1 3 0v8a7 7 0 0 1-7 7h-1a6 6 0 0 1-5-3l-2-4a1.5 1.5 0 0 1 2.5-1.5L8 15"],
  send: ["M4 12l16-8-6 16-3-7z"],
} as const;

export type IconName = keyof typeof PATHS;

export function icon(name: IconName, className = "icon"): SVGSVGElement {
  const svg = document.createElementNS(SVG_NS, "svg") as SVGSVGElement;
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("class", className);
  for (const d of PATHS[name]) {
    const path = document.createElementNS(SVG_NS, "path");
    path.setAttribute("d", d);
    svg.append(path);
  }
  return svg;
}
