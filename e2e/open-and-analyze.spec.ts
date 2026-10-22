import { expect, type Page, test } from "@playwright/test";
import { formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";

const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";
const ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const REPOSITORY_NAME = "multi-module";
const COMPLETED_HEADLINE = "9 arquivos Java analisados";

const mainNamed = (page: Page, name: string) =>
  page.getByRole("main", { name });

const factValue = (page: Page, label: string) =>
  page
    .getByRole("term")
    .filter({ hasText: label })
    .locator("xpath=following-sibling::dd");

const openFakeRepository = async (page: Page): Promise<void> => {
  await page.goto("/");
  const open = mainNamed(page, t.shell.regions.start).getByRole("button", {
    name: t.repository.open,
  });
  await expect(open).not.toHaveAttribute("aria-disabled", "true");
  await open.click();
  await expect(mainNamed(page, t.shell.regions.prepare)).toBeVisible();
};

test("do início à análise: a preparação com core e api, Analisar, e o resumo com o cartão do mapa", async ({
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

  const analysis = mainNamed(page, t.shell.regions.analysis);
  const headline = analysis.getByRole("heading", {
    level: 1,
    name: COMPLETED_HEADLINE,
  });
  await expect(headline).toBeVisible();
  expect(Object.fromEntries(new URL(page.url()).searchParams)).toStrictEqual({
    analysis: ANALYSIS_ID,
    level: "PACKAGE",
  });
  await expect(headline).toBeFocused();
  await expect(
    analysis.getByRole("heading", { name: t.analysis.map.title })
  ).toBeVisible();
  await expect(mainNamed(page, t.shell.regions.canvas)).toHaveCount(0);
  await expect(page.locator("[data-graph-state]")).toHaveCount(0);
  await expect(page.getByRole("main")).toHaveCount(1);
});

test("o voltar do navegador desfaz a entrada na análise, e Analisar de novo empilha a preparação", async ({
  page,
}) => {
  await openFakeRepository(page);
  await mainNamed(page, t.shell.regions.prepare)
    .getByRole("button", { name: t.config.analyze })
    .click();
  const analysis = mainNamed(page, t.shell.regions.analysis);
  await expect(
    analysis.getByRole("heading", { level: 1, name: COMPLETED_HEADLINE })
  ).toBeVisible();

  await page.goBack();

  await expect(
    mainNamed(page, t.shell.regions.prepare).getByRole("heading", {
      level: 1,
      name: REPOSITORY_NAME,
    })
  ).toBeVisible();

  await page.goForward();
  await analysis.getByRole("button", { name: t.analysis.again }).click();

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

test("um handle malformado na URL é a página não encontrada, com Voltar ao início", async ({
  page,
}) => {
  await page.goto("/repository/nao-e-um-handle");

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
