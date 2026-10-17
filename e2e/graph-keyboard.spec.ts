import { expect, type Page, test } from "@playwright/test";
import { GRAPH_PERF } from "@/features/graph/perf";
import { GRAPH_STYLE } from "@/features/graph/stylesheet";
import { ROW_HEIGHT } from "@/features/outline/outline-row";
import { t } from "@/i18n/messages";
import {
  type OutlinePage,
  outlinePageSchema,
  searchResultSchema,
} from "@/protocol/graph";
import {
  canvasNodeIds,
  countMarks,
  elementIdsOf,
  expectGraph,
  fixtureNodeIds,
  fixtureResponse,
  historyLength,
  outlineIds,
  renderedPosition,
  searchOf,
  serveTranscripts,
  styleOf,
  type TabStop,
  tabUntil,
} from "./support/graph-page";

const ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const LEVEL_ONE_URL = `/repository/repo_AAAAAAAAAAAAAAAAAAAAAQ?analysis=${ANALYSIS_ID}`;
const ORDERS_ID = "pkg:br.pucpr.orders";
const BILLING_ID = "pkg:br.pucpr.billing";
const SHARED_ID = "pkg:br.pucpr.shared";
const ORDER_ID = "type:br.pucpr.orders.Order";
const TYPE_URL = `${LEVEL_ONE_URL}&level=TYPE&parent=${ORDERS_ID}`;

const PACKAGE_IDS = fixtureNodeIds("graph.slice.package.jsonl");
const TYPE_IDS = fixtureNodeIds("graph.slice.type.jsonl");
const SEARCH_HITS = searchResultSchema.parse(
  fixtureResponse("graph.search.hit.jsonl")
).results;

const ACTIVE_BORDER_WIDTH = `${GRAPH_STYLE.node.activeBorderWidth}px`;
const SELECTED_BORDER_WIDTH = `${GRAPH_STYLE.node.selectedBorderWidth}px`;

const MAX_TAB_STOPS = 10;
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

const expandOrdersFromOutline = async (page: Page): Promise<void> => {
  await rowOf(page, ORDERS_ID).locator("[data-chevron]").click();
  await expect
    .poll(() => searchOf(page))
    .toMatchObject({ level: "TYPE", parent: ORDERS_ID });
  await expectGraph(page, TYPE_IDS);
  await expect.poll(() => outlineIds(page, 2)).toStrictEqual(TYPE_IDS);
};

test.describe("A estrutura da arquitetura pelo teclado, par do canvas", () => {
  test("nível 1: as linhas de aria-level 1 são os nós do canvas, os três pacotes", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);

    const canvasIds = await canvasNodeIds(page);

    expect(canvasIds).toStrictEqual(PACKAGE_IDS);
    expect(canvasIds).toHaveLength(3);
    await expect.poll(() => outlineIds(page, 1)).toStrictEqual(canvasIds);
    expect(await outlineIds(page, 2)).toStrictEqual([]);
  });

  test("nível TYPE: aberto orders, as linhas de aria-level 2 são os nós do canvas", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);

    await expandOrdersFromOutline(page);

    const canvasIds = await canvasNodeIds(page);
    expect(canvasIds).toStrictEqual(TYPE_IDS);
    expect(await outlineIds(page, 2)).toStrictEqual(canvasIds);
    expect(await outlineIds(page, 1)).toStrictEqual(PACKAGE_IDS);
  });

  test("o Tab do topo chega à árvore depois do tema e da busca, e a seta move o foco e o destaque", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);
    expect(await styleOf(page, BILLING_ID, "border-width")).not.toBe(
      ACTIVE_BORDER_WIDTH
    );

    expect(
      await tabUntil(page, tabbableRow(page), MAX_TAB_STOPS)
    ).toStrictEqual([THEME_STOP, SEARCH_STOP]);
    await expect(rowOf(page, BILLING_ID)).toBeFocused();
    await expect
      .poll(() => styleOf(page, BILLING_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);

    await page.keyboard.press("ArrowDown");

    const orders = rowOf(page, ORDERS_ID);
    await expect(orders).toBeFocused();
    await expect(orders).toHaveAttribute("tabindex", "0");
    await expect(orders).toHaveAttribute("data-active", "");
    await expect(rowOf(page, BILLING_ID)).toHaveAttribute("tabindex", "-1");
    expect(searchOf(page)).not.toHaveProperty("focus");
    await expect
      .poll(() => styleOf(page, ORDERS_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);
    await expect
      .poll(() => styleOf(page, BILLING_ID, "border-width"))
      .not.toBe(ACTIVE_BORDER_WIDTH);

    await page.goto(TYPE_URL);
    await expectGraph(page, TYPE_IDS);

    expect(
      await tabUntil(page, tabbableRow(page), MAX_TAB_STOPS)
    ).toStrictEqual([ROOT_CRUMB_STOP, THEME_STOP, SEARCH_STOP]);
    await expect(orders).toBeFocused();
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
    await expect(rowOf(page, BILLING_ID)).toBeFocused();
    expect(page.url()).toBe(urlBefore);
    expect(await historyLength(page)).toBe(historyBefore);
  });

  test("só teclado, sem focus na URL: Enter seleciona orders, → abre os tipos com o foco na linha, ← volta ao nível 1", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);
    expect(searchOf(page)).not.toHaveProperty("focus");
    await tabUntil(page, tabbableRow(page), MAX_TAB_STOPS);
    await page.keyboard.press("ArrowDown");
    const orders = rowOf(page, ORDERS_ID);
    await expect(orders).toBeFocused();
    const historyAtStart = await historyLength(page);

    await page.keyboard.press("Enter");

    await expect.poll(() => searchOf(page)).toMatchObject({ focus: ORDERS_ID });
    expect(await historyLength(page)).toBe(historyAtStart);
    await expect
      .poll(() => styleOf(page, ORDERS_ID, "border-width"))
      .toBe(SELECTED_BORDER_WIDTH);
    await expect(orders).toHaveAttribute("aria-selected", "true");
    await expect(orders).toBeFocused();

    await page.keyboard.press("ArrowRight");

    await expect
      .poll(() => searchOf(page))
      .toMatchObject({ level: "TYPE", parent: ORDERS_ID });
    await expectGraph(page, TYPE_IDS);
    expect(await historyLength(page)).toBe(historyAtStart + 1);
    await expect(orders).toBeFocused();

    await expect.poll(() => outlineIds(page, 2)).toStrictEqual(TYPE_IDS);
    await page.keyboard.press("ArrowRight");

    await expect(
      treeOf(page).locator(
        '[role="treeitem"][aria-level="2"][aria-posinset="1"]'
      )
    ).toBeFocused();

    await page.keyboard.press("ArrowLeft");

    await expect(orders).toBeFocused();
    expect(searchOf(page)).toMatchObject({ level: "TYPE", parent: ORDERS_ID });

    await page.keyboard.press("ArrowLeft");

    await expect
      .poll(() => searchOf(page))
      .toMatchObject({ focus: ORDERS_ID, level: "PACKAGE" });
    expect(searchOf(page)).not.toHaveProperty("parent");
    await expectGraph(page, PACKAGE_IDS);
    expect(await historyLength(page)).toBe(historyAtStart + 2);
    await expect(orders).toBeFocused();
    await expect
      .poll(() => styleOf(page, ORDERS_ID, "border-width"))
      .toBe(SELECTED_BORDER_WIDTH);
  });

  test("a busca: 'Ord' lista os resultados na ordem do Java, a seta foca e destaca, Enter em Order abre orders e o seleciona", async ({
    page,
  }) => {
    const [firstHit] = SEARCH_HITS;
    const packageHit = SEARCH_HITS.at(-1);
    expect([firstHit?.elementId, packageHit?.kind]).toStrictEqual([
      ORDER_ID,
      "PACKAGE",
    ]);
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);

    await page
      .getByRole("searchbox", { name: t.search.label })
      .pressSequentially("Ord");

    await expect
      .poll(() => elementIdsOf(resultsOf(page).getByRole("option")))
      .toStrictEqual(SEARCH_HITS.map((hit) => hit.elementId));

    await page.keyboard.press("ArrowDown");

    await expect(optionOf(page, ORDER_ID)).toBeFocused();
    expect(await styleOf(page, ORDER_ID, "border-width")).toBeNull();

    await page.keyboard.press("End");

    await expect(optionOf(page, ORDERS_ID)).toBeFocused();
    await expect
      .poll(() => styleOf(page, ORDERS_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);

    await page.keyboard.press("Home");

    await expect(optionOf(page, ORDER_ID)).toBeFocused();
    await expect
      .poll(() => styleOf(page, ORDERS_ID, "border-width"))
      .not.toBe(ACTIVE_BORDER_WIDTH);
    const historyBefore = await historyLength(page);

    await page.keyboard.press("Enter");

    await expect
      .poll(() => searchOf(page))
      .toMatchObject({ focus: ORDER_ID, level: "TYPE", parent: ORDERS_ID });
    await expectGraph(page, TYPE_IDS);
    expect(await historyLength(page)).toBe(historyBefore + 1);
    await expect(rowOf(page, ORDER_ID)).toHaveAttribute(
      "aria-selected",
      "true"
    );
    await expect
      .poll(() => styleOf(page, ORDER_ID, "border-width"))
      .toBe(SELECTED_BORDER_WIDTH);
    await expect(optionOf(page, ORDER_ID)).toBeFocused();
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

  test("voltar depois de abrir orders pela árvore restaura o nível 1 do cache, com orders fechado", async ({
    page,
  }) => {
    await page.goto(LEVEL_ONE_URL);
    await expectGraph(page, PACKAGE_IDS);
    await expandOrdersFromOutline(page);
    const requestsBeforeBack = await countMarks(page, GRAPH_PERF.request);

    await page.goBack();

    await expect.poll(() => searchOf(page)).toMatchObject({ level: "PACKAGE" });
    await expectGraph(page, PACKAGE_IDS);
    expect(requestsBeforeBack).toBe(2);
    expect(await countMarks(page, GRAPH_PERF.request)).toBe(requestsBeforeBack);
    await expect(rowOf(page, ORDERS_ID)).toHaveAttribute(
      "aria-expanded",
      "false"
    );
    await expect.poll(() => outlineIds(page, 2)).toStrictEqual([]);

    await page.goForward();

    await expect
      .poll(() => searchOf(page))
      .toMatchObject({ level: "TYPE", parent: ORDERS_ID });
    await expectGraph(page, TYPE_IDS);
    await expect(rowOf(page, ORDERS_ID)).toHaveAttribute(
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
    await rowOf(page, BILLING_ID).focus();
    await expect
      .poll(() => styleOf(page, BILLING_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);

    const shared = await renderedPosition(page, SHARED_ID);
    await page.mouse.move(shared.x, shared.y);
    await expect
      .poll(() => styleOf(page, SHARED_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);
    await page.mouse.move(2, 790);

    await expect(rowOf(page, BILLING_ID)).toBeFocused();
    await expect
      .poll(() => styleOf(page, BILLING_ID, "border-width"))
      .toBe(ACTIVE_BORDER_WIDTH);
    await expect(rowOf(page, BILLING_ID)).toHaveAttribute("data-active", "");
    await expect
      .poll(() => styleOf(page, SHARED_ID, "border-width"))
      .not.toBe(ACTIVE_BORDER_WIDTH);
  });
});
