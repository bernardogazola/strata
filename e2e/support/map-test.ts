import { test as base } from "@playwright/test";
import { serveMap } from "./graph-page";

export const test = base.extend({
  page: async ({ page }, provide) => {
    await serveMap(page);
    await provide(page);
  },
});
