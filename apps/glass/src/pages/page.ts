/** Every page is an element plus a teardown. The shell mounts one page at a time. */
export interface GlassPage {
  element: HTMLElement;
  destroy(): void;
}
