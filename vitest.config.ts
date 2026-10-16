import path from "node:path";
import react from "@vitejs/plugin-react";
import { configDefaults, defineConfig } from "vitest/config";

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
      include: ["src/**/*.{ts,tsx}"],
      provider: "v8",
      thresholds: {
        branches: 71,
        functions: 56,
        lines: 79,
        statements: 78,
      },
    },
    environment: "jsdom",
    exclude: [...configDefaults.exclude, "corpus/**"],
    globals: false,
    setupFiles: ["./src/test/setup.ts"],
    unstubGlobals: true,
  },
});
