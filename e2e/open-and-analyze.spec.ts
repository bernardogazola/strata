import { expect, type Page, test } from "@playwright/test";
import { formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";
import { openFakeRepository } from "./support/fake-run";
import { expectGraph, fixtureNodeIds } from "./support/graph-page";

const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";
const ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const REPOSITORY_NAME = "multi-module";
const PACKAGE_IDS = fixtureNodeIds("graph.slice.package.jsonl");

const mainNamed = (page: Page, name: string) =>
  page.getByRole("main", { name });

const factValue = (page: Page, label: string) =>
  page
    .getByRole("term")
    .filter({ hasText: label })
    .locator("xpath=following-sibling::dd");

test("do início ao mapa: a preparação com core e api, Analisar, e o mapa com os pacotes", async ({
  page,
}) => {
  await openFakeRepository(page);

  expect(new URL(page.url()).pathname).toBe(`/repository/${HANDLE}`);
  const prepare = mainNamed(page, t.shell.regions.prepare);
  await expect(
    prepare.getByRole("heading", { level: 1, name: REPOSITORY_NAME })
  ).toBeVisible();
  await expect(factValue(page, t.describe.facts.modules)).toHaveText(
    "core, api"
  );
  await expect(
    page.getByRole("banner").getByText(REPOSITORY_NAME)
  ).toBeVisible();

  const analyze = prepare.getByRole("button", { name: t.config.analyze });
  await expect(analyze).not.toHaveAttribute("aria-disabled", "true");
  await analyze.click();

  const map = mainNamed(page, t.shell.regions.canvas);
  const headline = map.getByRole("heading", {
    level: 1,
    name: t.shell.regions.canvas,
  });
  await expect(map).toBeVisible();
  expect(Object.fromEntries(new URL(page.url()).searchParams)).toStrictEqual({
    analysis: ANALYSIS_ID,
    level: "PACKAGE",
  });
  await expect(headline).toBeFocused();
  await expectGraph(page, PACKAGE_IDS);
  expect(PACKAGE_IDS).toHaveLength(4);
  await expect(mainNamed(page, t.shell.regions.analysis)).toHaveCount(0);
  await expect(page.getByRole("main")).toHaveCount(1);
});

test("o voltar do navegador desfaz a entrada na análise, e Analisar de novo, no cabeçalho do mapa, empilha a preparação", async ({
  page,
}) => {
  await openFakeRepository(page);
  await mainNamed(page, t.shell.regions.prepare)
    .getByRole("button", { name: t.config.analyze })
    .click();
  await expectGraph(page, PACKAGE_IDS);

  await page.goBack();

  await expect(
    mainNamed(page, t.shell.regions.prepare).getByRole("heading", {
      level: 1,
      name: REPOSITORY_NAME,
    })
  ).toBeVisible();

  await page.goForward();
  await expectGraph(page, PACKAGE_IDS);
  await page
    .getByRole("banner")
    .getByRole("button", { name: t.analysis.again })
    .click();

  await expect(
    mainNamed(page, t.shell.regions.prepare).getByRole("heading", {
      level: 1,
      name: REPOSITORY_NAME,
    })
  ).toBeVisible();
  expect(new URL(page.url()).searchParams.has("analysis")).toBe(false);
});

test("Fechar repositório, no cabeçalho, volta ao início", async ({ page }) => {
  await openFakeRepository(page);

  await page
    .getByRole("banner")
    .getByRole("button", { name: t.header.close })
    .click();

  await expect(
    mainNamed(page, t.shell.regions.start).getByRole("heading", {
      level: 1,
      name: t.start.title,
    })
  ).toBeVisible();
  expect(new URL(page.url()).pathname).toBe("/");
});

test("pelo teclado, abrir e fechar levam o foco ao título da tela nova", async ({
  page,
}) => {
  await page.goto("/");
  const open = mainNamed(page, t.shell.regions.start).getByRole("button", {
    name: t.repository.open,
  });
  await expect(open).not.toHaveAttribute("aria-disabled", "true");

  await open.focus();
  await page.keyboard.press("Enter");

  await expect(
    mainNamed(page, t.shell.regions.prepare).getByRole("heading", {
      level: 1,
      name: REPOSITORY_NAME,
    })
  ).toBeFocused();

  await page
    .getByRole("banner")
    .getByRole("button", { name: t.header.close })
    .focus();
  await page.keyboard.press("Enter");

  await expect(
    mainNamed(page, t.shell.regions.start).getByRole("heading", {
      level: 1,
      name: t.start.title,
    })
  ).toBeFocused();
});

test("recarregar a preparação alcançada pelo teclado não move o foco", async ({
  page,
}) => {
  await page.goto("/");
  const open = mainNamed(page, t.shell.regions.start).getByRole("button", {
    name: t.repository.open,
  });
  await expect(open).not.toHaveAttribute("aria-disabled", "true");
  await open.focus();
  await page.keyboard.press("Enter");
  const title = mainNamed(page, t.shell.regions.prepare).getByRole("heading", {
    level: 1,
    name: REPOSITORY_NAME,
  });
  await expect(title).toBeFocused();

  await page.reload();

  await expect(title).toBeVisible();
  expect(await page.evaluate(() => document.activeElement?.tagName)).toBe(
    "BODY"
  );
});

test("um link direto para a preparação não move o foco", async ({ page }) => {
  await page.goto(`/repository/${HANDLE}`);

  await expect(
    mainNamed(page, t.shell.regions.prepare).getByRole("heading", {
      level: 1,
      name: REPOSITORY_NAME,
    })
  ).toBeVisible();
  expect(await page.evaluate(() => document.activeElement?.tagName)).toBe(
    "BODY"
  );
});

test("um handle malformado na URL é a página não encontrada, com Voltar ao início", async ({
  page,
}) => {
  await page.goto("/repository/nao-e-um-handle");

  await expect(mainNamed(page, t.shell.regions.repository)).toBeVisible();
  const { title } = formatProtocolError({
    code: "REPO_HANDLE_UNKNOWN",
    origin: "frontend",
  });
  await expect(
    page.getByRole("heading", { level: 1, name: title })
  ).toBeVisible();

  await page.getByRole("link", { name: t.repository.backToStart }).click();

  await expect(
    mainNamed(page, t.shell.regions.start).getByRole("heading", {
      level: 1,
      name: t.start.title,
    })
  ).toBeVisible();
});
