import { existsSync } from "node:fs";
import { defineConfig, type Project, webkit } from "@playwright/test";

const BASE_URL = "http://localhost:1420";

const webkitProject: Project[] = existsSync(webkit.executablePath())
  ? [{ grep: /@benchmark/, name: "webkit", use: { browserName: "webkit" } }]
  : [];

export default defineConfig({
  fullyParallel: false,
  projects: [
    {
      name: "chromium",
      // Sem channel: "chromium", o headless padrão usa o chrome-headless-shell com
      // renderização por software (SwiftShader), lenta demais para o benchmark.
      use: { browserName: "chromium", channel: "chromium" },
    },
    ...webkitProject,
  ],
  reporter: "list",
  retries: 0,
  testDir: "./e2e",
  use: {
    baseURL: BASE_URL,
    deviceScaleFactor: 1,
    viewport: { height: 800, width: 1280 },
  },
  webServer: {
    command: "pnpm dev",
    reuseExistingServer: true,
    timeout: 120_000,
    url: BASE_URL,
  },
  workers: 1,
});
