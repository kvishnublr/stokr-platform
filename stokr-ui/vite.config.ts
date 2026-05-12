import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

/** Dev-only backend origin shared by HTTP + WS proxies. */
const backendTarget =
  process.env.STOKR_BACKEND_ORIGIN ??
  process.env.STOKR_API_PROXY_TARGET ??
  "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  /** sockjs-client expects Node's `global`; browsers only have globalThis/window. */
  define: {
    global: "globalThis",
  },
  server: {
    port: 5173,
    /** Fail fast if 5173 is still taken after predev kill (no silent hop to 5174/5175). */
    strictPort: true,
    proxy: {
      "/api": { target: backendTarget, changeOrigin: true },
      "/v3": { target: backendTarget, changeOrigin: true },
      "/ws": {
        target: backendTarget,
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
