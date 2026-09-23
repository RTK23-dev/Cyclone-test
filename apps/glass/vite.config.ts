import { defineConfig } from "vite";
import { readFileSync } from "node:fs";

const glassVersion = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")).version as string;
// Dev only: proxy API calls to the local gateway so `npm run dev` behaves like the served bundle.
const gateway = process.env.CYCLONE_GATEWAY_URL ?? "http://127.0.0.1:8765";

export default defineConfig({
  base: "/glass/",
  define: {
    __CYCLONE_GLASS_VERSION__: JSON.stringify(glassVersion),
  },
  clearScreen: false,
  server: {
    host: "127.0.0.1",
    port: 5178,
    strictPort: true,
    proxy: {
      "/v1": { target: gateway, ws: true, changeOrigin: false },
    },
  },
  build: {
    target: "es2022",
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: true,
  },
});
