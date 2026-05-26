import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "path";

/** Dev-only backend origin shared by HTTP + WS proxies (never used in production builds — nginx serves UI + proxies /api). */
const backendTarget =
  process.env.STOKR_BACKEND_ORIGIN ??
  process.env.STOKR_API_PROXY_TARGET ??
  "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  /** sockjs-client expects Node's `global`; browsers only have globalThis/window. */
  define: {
    global: "globalThis",
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("node_modules")) {
            if (id.includes("framer-motion")) return "motion";
            if (id.includes("@tanstack")) return "query";
            if (id.includes("lucide-react")) return "icons";
            if (id.includes("react-dom") || id.includes("react-router")) return "react-vendor";
            return "vendor";
          }
        },
      },
    },
    chunkSizeWarningLimit: 900,
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
