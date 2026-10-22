import { expect, type Page, test } from "@playwright/test";
import { t } from "@/i18n/messages";
import {
  GRAPH_WRAPPER,
  READY_TIMEOUT_MS,
  serveMap,
} from "./support/graph-page";

const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";
const ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const freezeLikeTauri = async (page: Page): Promise<void> => {
  await page.addInitScript(() => {
    Object.freeze(Object.prototype);
  });
};

const collectPageErrors = (page: Page): string[] => {
  const errors: string[] = [];
  page.on("pageerror", (error) => {
    errors.push(error.message);
  });
  return errors;
};

test("com o protótipo congelado, a tela inicial e a preparação carregam sem erro", async ({
  page,
}) => {
  await freezeLikeTauri(page);
  const errors = collectPageErrors(page);

  await page.goto("/");
  await expect(
    page.getByRole("main", { name: t.shell.regions.start })
  ).toBeVisible();
  await page.goto(`/repository/${HANDLE}`);
  await expect(
    page.getByRole("main", { name: t.shell.regions.prepare })
  ).toBeVisible();

  expect(errors).toStrictEqual([]);
});

test("com o protótipo congelado, o mapa monta: o Cytoscape avalia e desenha", async ({
  page,
}) => {
  await freezeLikeTauri(page);
  await serveMap(page);
  const errors = collectPageErrors(page);

  await page.goto(`/repository/${HANDLE}?analysis=${ANALYSIS_ID}`);

  await expect(page.locator(GRAPH_WRAPPER)).toHaveAttribute(
    "data-graph-state",
    "ready",
    { timeout: READY_TIMEOUT_MS }
  );
  expect(errors).toStrictEqual([]);
});
