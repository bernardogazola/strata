import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: { "@": path.resolve(import.meta.dirname, "./src") },
  },
  test: {
    coverage: {
      exclude: [
        "src/routeTree.gen.ts",
        "src/components/ui/**",
        "src/main.tsx",
        "src/vite-env.d.ts",
        "**/__tests__/**",
      ],
      provider: "v8",
    },
    environment: "node",
  },
});
