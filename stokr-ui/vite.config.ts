import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

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
      "/api": "http://localhost:8080",
      "/v3": "http://localhost:8080",
      "/ws": {
        target: "http://localhost:8080",
        ws: true,
      },
    },
  },
});
