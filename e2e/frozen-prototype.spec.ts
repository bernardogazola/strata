import { expect, type Page, test } from "@playwright/test";
import { t } from "@/i18n/messages";
import type { DependencyDirection } from "@/protocol/enums";
import {
  DRILLED_PACKAGE_ID,
  dependencyPageOf,
  typeSliceOf,
} from "@/test/fixture-graph";
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

const inspectedType = (): string => {
  const typed = typeSliceOf(DRILLED_PACKAGE_ID).nodes.find(
    (node) =>
      node.kind === "TYPE" &&
      dependencyPageOf(node.id, "IN").items.length > 0 &&
      dependencyPageOf(node.id, "OUT").items.length > 0
  );
  if (typed === undefined) {
    throw new Error(
      `nenhum tipo de ${DRILLED_PACKAGE_ID} tem linhas nas duas direções`
    );
  }
  return typed.id;
};

const expectRowLabels = async (
  page: Page,
  elementId: string,
  direction: DependencyDirection
): Promise<void> => {
  const { items } = dependencyPageOf(elementId, direction);
  const firstExternal = items.findIndex((entry) => entry.isExternal);
  const headings = firstExternal === -1 ? 0 : 1;
  const rows = page
    .getByRole("complementary", { name: t.shell.regions.inspector })
    .getByRole("table")
    .locator("tbody")
    .getByRole("row");
  await expect(rows).toHaveCount(items.length + headings);
  await Promise.all(
    items.map((entry, index) =>
      expect(
        rows.nth(
          firstExternal !== -1 && index >= firstExternal ? index + 1 : index
        )
      ).toContainText(entry.label)
    )
  );
};

test("com o protótipo congelado, a tabela de dependências do inspetor desenha as linhas nas duas direções", async ({
  page,
}) => {
  const elementId = inspectedType();
  await freezeLikeTauri(page);
  await serveMap(page);
  const errors = collectPageErrors(page);

  await page.goto(
    `/repository/${HANDLE}?analysis=${ANALYSIS_ID}&level=TYPE&parent=${encodeURIComponent(DRILLED_PACKAGE_ID)}&focus=${encodeURIComponent(elementId)}&tab=dependencies`
  );
  await expectRowLabels(page, elementId, "OUT");

  const name = t.inspector.dependencies.direction;
  const inspector = page.getByRole("complementary", {
    name: t.shell.regions.inspector,
  });
  const group = inspector
    .getByRole("radiogroup", { name })
    .or(inspector.getByRole("group", { name }));
  await group
    .getByRole("radio", { exact: true, name: t.inspector.usedBy })
    .or(group.getByRole("button", { exact: true, name: t.inspector.usedBy }))
    .click();
  await expect
    .poll(() => new URL(page.url()).searchParams.get("direction"))
    .toBe("IN");
  await expectRowLabels(page, elementId, "IN");

  expect(errors).toStrictEqual([]);
});
