import path from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(import.meta.dirname, "./src") },
  },
  test: {
    coverage: {
      exclude: [
        "src/routeTree.gen.ts",
        "src/components/ui/**",
        "src/main.tsx",
        "src/test/**",
        "src/vite-env.d.ts",
        "**/__tests__/**",
      ],
      provider: "v8",
    },
    environment: "jsdom",
    globals: false,
    setupFiles: ["./src/test/setup.ts"],
    unstubGlobals: true,
  },
});
