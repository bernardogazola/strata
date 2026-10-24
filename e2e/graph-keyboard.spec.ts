import { expect, type Page } from "@playwright/test";
import { GRAPH_PERF } from "@/features/graph/perf";
import { GRAPH_STYLE } from "@/features/graph/stylesheet";
import { ROW_HEIGHT } from "@/features/outline/outline-row";
import { t } from "@/i18n/messages";
import { type OutlinePage, outlinePageSchema } from "@/protocol/graph";
import {
  DRILLED_PACKAGE_ID,
  SEARCH_HITS,
  SEARCH_QUERY,
} from "@/test/fixture-graph";
import {
  canvasNodeIds,
  countMarks,
  elementIdsOf,
  expectGraph,
  fixtureNodeIds,
  historyLength,
  outlineIds,
  renderedPosition,
  searchOf,
  serveTranscripts,
  styleOf,
  type TabStop,
  tabUntil,
} from "./support/graph-page";
import { test } from "./support/map-test";

const ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const LEVEL_ONE_URL = `/repository/repo_AAAAAAAAAAAAAAAAAAAAAQ?analysis=${ANALYSIS_ID}`;
const PACKAGE_IDS = fixtureNodeIds("graph.slice.package.jsonl");
const TYPE_IDS = fixtureNodeIds("graph.slice.type.jsonl");

const DRILLED_ID = DRILLED_PACKAGE_ID;
const FIRST_ID = PACKAGE_IDS[0] ?? "";
const LAST_ID = PACKAGE_IDS.at(-1) ?? "";
const FIRST_HIT_ID = SEARCH_HITS[0]?.elementId ?? "";
const TYPE_URL = `${LEVEL_ONE_URL}&level=TYPE&parent=${DRILLED_ID}`;

if (PACKAGE_IDS[1] !== DRILLED_ID || LAST_ID === DRILLED_ID) {
  throw new Error(
    "as sequências de teclado supõem que o pacote aberto é a segunda linha do nível 1, e não a última"
  );
}

const ACTIVE_BORDER_WIDTH = `${GRAPH_STYLE.node.activeBorderWidth}px`;
const SELECTED_BORDER_WIDTH = `${GRAPH_STYLE.node.selectedBorderWidth}px`;

const MAX_TAB_STOPS = 11;
const CLOSE_STOP: TabStop = { name: t.header.close, role: "button" };
const THEME_STOP: TabStop = { name: t.theme.label, role: "button" };
const SEARCH_STOP: TabStop = { name: t.search.label, role: "searchbox" };
const ROOT_CRUMB_STOP: TabStop = {
  name: t.shell.breadcrumb.root,
  role: "link",
};

const OUTLINE_OVERSCAN = 8;
const PINNED_ROWS = 2;
const SYNTHETIC_PACKAGES = 1000;

const treeOf = (page: Page) =>
  page.getByRole("tree", { name: t.outline.title });

const rowOf = (page: Page, elementId: string) =>
  treeOf(page).locator(`[role="treeitem"][data-element-id="${elementId}"]`);

const tabbableRow = (page: Page) =>
  treeOf(page).locator('[role="treeitem"][tabindex="0"]');

const resultsOf = (page: Page) =>
  page.getByRole("listbox", { name: t.search.results });

const optionOf = (page: Page, elementId: string) =>
  resultsOf(page).locator(`[role="option"][data-element-id="${elementId}"]`);

const syntheticRoot = (count: number): OutlinePage =>
  outlinePageSchema.parse({
    items: Array.from({ length: count }, (_, index) => {
      const label = `p${String(index).padStart(4, "0")}`;
      return {
        childCount: 0,
        id: `pkg:synthetic.${label}`,
        kind: "PACKAGE",
        label,
      };
    }),
    nextCursor: null,
  });

const expandDrilledFromOutline = async (page: Page): Promise<void> => {
  await rowOf(page, DRILLED_ID).locator("[data-chevron]").click();
  await expect
    .poll(() => searchOf(page))
    .toMatchObject({ level: "TYPE", parent: DRILLED_ID });
  await expectGraph(page, TYPE_IDS);
  await expect.poll(() => outlineIds(page, 2)).toStrictEqual(TYPE_IDS);
};

test.describe("A estrutura da arquitetura pelo teclado, par do canvas", () => {
  test("nível 1: as linhas de aria-level 1 são os nós do canvas, os quatro pacotes", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);

    const canvasIds = await canvasNodeIds(page);

    expect(canvasIds).toStrictEqual(PACKAGE_IDS);
    expect(canvasIds).toHaveLength(4);
    await expect.poll(() => outlineIds(page, 1)).toStrictEqual(canvasIds);
    expect(await outlineIds(page, 2)).toStrictEqual([]);
  });

  test("nível TYPE: aberto o pacote, as linhas de aria-level 2 são os nós do canvas", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);

    await expandDrilledFromOutline(page);

    const canvasIds = await canvasNodeIds(page);
    expect(canvasIds).toStrictEqual(TYPE_IDS);
    expect(await outlineIds(page, 2)).toStrictEqual(canvasIds);
    expect(await outlineIds(page, 1)).toStrictEqual(PACKAGE_IDS);
  });

  test("o Tab do topo chega à árvore depois de fechar, do tema e da busca, e a seta move o foco e o destaque", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);
    expect(await styleOf(page, FIRST_ID, "border-width")).not.toBe(
      ACTIVE_BORDER_WIDTH
    );

    expect(
      await tabUntil(page, tabbableRow(page), MAX_TAB_STOPS)
    ).toStrictEqual([CLOSE_STOP, THEME_STOP, SEARCH_STOP]);
    await expect(rowOf(page, FIRST_ID)).toBeFocused();
    await expect
      .poll(() => styleOf(page, FIRST_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);

    await page.keyboard.press("ArrowDown");

    const drilled = rowOf(page, DRILLED_ID);
    await expect(drilled).toBeFocused();
    await expect(drilled).toHaveAttribute("tabindex", "0");
    await expect(drilled).toHaveAttribute("data-active", "");
    await expect(rowOf(page, FIRST_ID)).toHaveAttribute("tabindex", "-1");
    expect(searchOf(page)).not.toHaveProperty("focus");
    await expect
      .poll(() => styleOf(page, DRILLED_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);
    await expect
      .poll(() => styleOf(page, FIRST_ID, "border-width"))
      .not.toBe(ACTIVE_BORDER_WIDTH);

    await page.goto(TYPE_URL);
    await expectGraph(page, TYPE_IDS);

    expect(
      await tabUntil(page, tabbableRow(page), MAX_TAB_STOPS)
    ).toStrictEqual([ROOT_CRUMB_STOP, CLOSE_STOP, THEME_STOP, SEARCH_STOP]);
    await expect(drilled).toBeFocused();
  });

  test("o botão de pular, primeira parada do <main>, devolve o foco à linha tabulável sem entrada no histórico", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);
    const skip = page
      .getByRole("main")
      .getByRole("button", { name: t.shell.skipToOutline });
    await expect(page.locator("main > :first-child")).toHaveAccessibleName(
      t.shell.skipToOutline
    );
    await tabUntil(page, tabbableRow(page), MAX_TAB_STOPS);

    expect(await tabUntil(page, skip, MAX_TAB_STOPS)).toStrictEqual([]);
    const urlBefore = page.url();
    const historyBefore = await historyLength(page);

    await page.keyboard.press("Enter");

    await expect(tabbableRow(page)).toBeFocused();
    await expect(rowOf(page, FIRST_ID)).toBeFocused();
    expect(page.url()).toBe(urlBefore);
    expect(await historyLength(page)).toBe(historyBefore);
  });

  test("só teclado, sem focus na URL: Enter seleciona o pacote, → abre os tipos com o foco na linha, ← volta ao nível 1", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);
    expect(searchOf(page)).not.toHaveProperty("focus");
    await tabUntil(page, tabbableRow(page), MAX_TAB_STOPS);
    await page.keyboard.press("ArrowDown");
    const drilled = rowOf(page, DRILLED_ID);
    await expect(drilled).toBeFocused();
    const historyAtStart = await historyLength(page);

    await page.keyboard.press("Enter");

    await expect
      .poll(() => searchOf(page))
      .toMatchObject({ focus: DRILLED_ID });
    expect(await historyLength(page)).toBe(historyAtStart);
    await expect
      .poll(() => styleOf(page, DRILLED_ID, "border-width"))
      .toBe(SELECTED_BORDER_WIDTH);
    await expect(drilled).toHaveAttribute("aria-selected", "true");
    await expect(drilled).toBeFocused();

    await page.keyboard.press("ArrowRight");

    await expect
      .poll(() => searchOf(page))
      .toMatchObject({ level: "TYPE", parent: DRILLED_ID });
    await expectGraph(page, TYPE_IDS);
    expect(await historyLength(page)).toBe(historyAtStart + 1);
    await expect(drilled).toBeFocused();

    await expect.poll(() => outlineIds(page, 2)).toStrictEqual(TYPE_IDS);
    await page.keyboard.press("ArrowRight");

    await expect(
      treeOf(page).locator(
        '[role="treeitem"][aria-level="2"][aria-posinset="1"]'
      )
    ).toBeFocused();

    await page.keyboard.press("ArrowLeft");

    await expect(drilled).toBeFocused();
    expect(searchOf(page)).toMatchObject({ level: "TYPE", parent: DRILLED_ID });

    await page.keyboard.press("ArrowLeft");

    await expect
      .poll(() => searchOf(page))
      .toMatchObject({ focus: DRILLED_ID, level: "PACKAGE" });
    expect(searchOf(page)).not.toHaveProperty("parent");
    await expectGraph(page, PACKAGE_IDS);
    expect(await historyLength(page)).toBe(historyAtStart + 2);
    await expect(drilled).toBeFocused();
    await expect
      .poll(() => styleOf(page, DRILLED_ID, "border-width"))
      .toBe(SELECTED_BORDER_WIDTH);
  });

  test("a busca: a consulta da fixture lista os resultados na ordem do Java, a seta foca e destaca, Enter no primeiro tipo abre o pacote dele e o seleciona", async ({
    page,
  }) => {
    const [firstHit] = SEARCH_HITS;
    const packageHit = SEARCH_HITS.at(-1);
    expect([
      firstHit?.kind,
      firstHit?.packageId,
      packageHit?.kind,
      packageHit?.elementId,
    ]).toStrictEqual(["TYPE", DRILLED_ID, "PACKAGE", DRILLED_ID]);
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);

    await page
      .getByRole("searchbox", { name: t.search.label })
      .pressSequentially(SEARCH_QUERY);

    await expect
      .poll(() => elementIdsOf(resultsOf(page).getByRole("option")))
      .toStrictEqual(SEARCH_HITS.map((hit) => hit.elementId));

    await page.keyboard.press("ArrowDown");

    await expect(optionOf(page, FIRST_HIT_ID)).toBeFocused();
    expect(await styleOf(page, FIRST_HIT_ID, "border-width")).toBeNull();

    await page.keyboard.press("End");

    await expect(optionOf(page, DRILLED_ID)).toBeFocused();
    await expect
      .poll(() => styleOf(page, DRILLED_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);

    await page.keyboard.press("Home");

    await expect(optionOf(page, FIRST_HIT_ID)).toBeFocused();
    await expect
      .poll(() => styleOf(page, DRILLED_ID, "border-width"))
      .not.toBe(ACTIVE_BORDER_WIDTH);
    const historyBefore = await historyLength(page);

    await page.keyboard.press("Enter");

    await expect
      .poll(() => searchOf(page))
      .toMatchObject({
        focus: FIRST_HIT_ID,
        level: "TYPE",
        parent: DRILLED_ID,
      });
    await expectGraph(page, TYPE_IDS);
    expect(await historyLength(page)).toBe(historyBefore + 1);
    await expect(rowOf(page, FIRST_HIT_ID)).toHaveAttribute(
      "aria-selected",
      "true"
    );
    await expect
      .poll(() => styleOf(page, FIRST_HIT_ID, "border-width"))
      .toBe(SELECTED_BORDER_WIDTH);
    await expect(optionOf(page, FIRST_HIT_ID)).toBeFocused();
  });

  test("altura limitada: 1 000 pacotes na raiz montam poucas dezenas de linhas, e End foca a última, visível", async ({
    page,
  }) => {
    const root = syntheticRoot(SYNTHETIC_PACKAGES);
    const lastId = root.items.at(-1)?.id ?? "";
    await serveTranscripts(page, [
      {
        method: "outline.children",
        request: { parentId: null },
        result: root,
      },
    ]);
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);
    const rows = treeOf(page).locator('[role="treeitem"]');
    await expect(rows.first()).toBeVisible();
    const windowRows = await treeOf(page).evaluate(
      (tree, rowHeight) =>
        Math.ceil(tree.getBoundingClientRect().height / rowHeight),
      ROW_HEIGHT
    );
    const maxMounted = windowRows + 1 + 2 * OUTLINE_OVERSCAN + PINNED_ROWS;
    expect(maxMounted).toBeLessThan(SYNTHETIC_PACKAGES / 10);

    await expect(rows).toHaveCount(windowRows + OUTLINE_OVERSCAN);

    await tabUntil(page, tabbableRow(page), MAX_TAB_STOPS);
    await page.keyboard.press("End");

    const last = rowOf(page, lastId);
    await expect(last).toBeFocused();
    await expect(last).toBeInViewport();
    await expect(last).toHaveAttribute(
      "aria-posinset",
      String(SYNTHETIC_PACKAGES)
    );
    expect(await rows.count()).toBeLessThanOrEqual(maxMounted);
  });

  test("voltar depois de abrir o pacote pela árvore restaura o nível 1 do cache, com ele fechado", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);
    await expandDrilledFromOutline(page);
    const requestsBeforeBack = await countMarks(page, GRAPH_PERF.request);

    await page.goBack();

    await expect.poll(() => searchOf(page)).toMatchObject({ level: "PACKAGE" });
    await expectGraph(page, PACKAGE_IDS);
    expect(requestsBeforeBack).toBe(2);
    expect(await countMarks(page, GRAPH_PERF.request)).toBe(requestsBeforeBack);
    await expect(rowOf(page, DRILLED_ID)).toHaveAttribute(
      "aria-expanded",
      "false"
    );
    await expect.poll(() => outlineIds(page, 2)).toStrictEqual([]);

    await page.goForward();

    await expect
      .poll(() => searchOf(page))
      .toMatchObject({ level: "TYPE", parent: DRILLED_ID });
    await expectGraph(page, TYPE_IDS);
    await expect(rowOf(page, DRILLED_ID)).toHaveAttribute(
      "aria-expanded",
      "true"
    );
    await expect.poll(() => outlineIds(page, 2)).toStrictEqual(TYPE_IDS);
    expect(await countMarks(page, GRAPH_PERF.request)).toBe(requestsBeforeBack);
  });

  test("o ponteiro que salta de um nó para fora do canvas devolve o destaque ao foco de teclado", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);
    await rowOf(page, FIRST_ID).focus();
    await expect
      .poll(() => styleOf(page, FIRST_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);

    const last = await renderedPosition(page, LAST_ID);
    await page.mouse.move(last.x, last.y);
    await expect
      .poll(() => styleOf(page, LAST_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);
    await page.mouse.move(2, 790);

    await expect(rowOf(page, FIRST_ID)).toBeFocused();
    await expect
      .poll(() => styleOf(page, FIRST_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);
    await expect(rowOf(page, FIRST_ID)).toHaveAttribute("data-active", "");
    await expect
      .poll(() => styleOf(page, LAST_ID, "border-width"))
      .not.toBe(ACTIVE_BORDER_WIDTH);
  });
});
