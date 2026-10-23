import { createHash } from "node:crypto";
import os from "node:os";
import {
  type BrowserContextOptions,
  expect,
  type Page,
  type TestInfo,
} from "@playwright/test";
import { GRAPH_PERF } from "@/features/graph/perf";
import { GRAPH_STYLE } from "@/features/graph/stylesheet";
import { t } from "@/i18n/messages";
import {
  GRAPH_SLICE_BUDGETS,
  type GraphSlice,
  graphSliceSchema,
} from "@/protocol/graph";
import {
  BENCHMARK_OUT_ENV,
  benchmarkTypeBudget,
  captureEnvironment,
  createBenchmarkRecord,
  GATING_PROJECT,
  TYPE_NODES_ENV,
  writeBenchmarkRecord,
} from "./support/benchmark";
import {
  type FrameSummary,
  measureIdleBaseline,
  median,
  summarizeFrames,
} from "./support/frames";
import {
  armSelectionProbe,
  centreOf,
  containerBox,
  countMarks,
  dragPan,
  drawSeeded,
  expectGraph,
  findEmptyPoint,
  fitGraph,
  fixtureNodeIds,
  GESTURE_INPUT,
  GRAPH_CONTAINER,
  listClickableNodes,
  type NodeHitArea,
  type Point,
  type RecordedGesture,
  readNodeHitAreas,
  readPositions,
  readSelectionLatency,
  readZoom,
  renderedPosition,
  searchOf,
  serveMap,
  serveSlices,
  settle,
  setZoom,
  styleOf,
  tokenAsCytoscapeRgb,
  trackpadWheel,
  waitForFreshReady,
  withFreshPage,
} from "./support/graph-page";
import { test } from "./support/map-test";
import {
  SYNTHETIC_SLICE_GENERATOR,
  SYNTHETIC_TYPE_PARENT_ID,
  type SyntheticSliceOptions,
  syntheticSlice,
} from "./support/synthetic-slice";

const ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const LEVEL_ONE_URL = `/repository/repo_AAAAAAAAAAAAAAAAAAAAAQ?analysis=${ANALYSIS_ID}`;
const ORDERS_ID = "pkg:br.pucpr.orders";
const BILLING_ID = "pkg:br.pucpr.billing";

const CYTOSCAPE_STYLESHEET_ID = "__________cytoscape_stylesheet";
const SELECTED_BORDER_WIDTH = `${GRAPH_STYLE.node.selectedBorderWidth}px`;
const CANVAS_LAYERS = 3;
const LABEL_TOKEN = "--graph-node-label";
const PAGERANK_TOLERANCE = 1e-9;

const DARK_CLASS = /\bdark\b/;
const SOFTWARE_RENDERER = /swiftshader/i;

const PACKAGE_IDS = fixtureNodeIds("graph.slice.package.jsonl");
const TYPE_IDS = fixtureNodeIds("graph.slice.type.jsonl");

const sha256 = (text: string): string =>
  createHash("sha256").update(text).digest("hex");

const contextOptionsOf = (testInfo: TestInfo): BrowserContextOptions => {
  const { baseURL, deviceScaleFactor, viewport } = testInfo.project.use;
  return { baseURL, deviceScaleFactor, viewport };
};

const expandOrders = async (page: Page): Promise<void> => {
  const orders = await renderedPosition(page, ORDERS_ID);
  await page.mouse.dblclick(orders.x, orders.y);
  await expect
    .poll(() => searchOf(page))
    .toMatchObject({ level: "TYPE", parent: ORDERS_ID });
  await expectGraph(page, TYPE_IDS);
};

test("o nível 1 da fixture desenha os três pacotes", async ({ page }) => {
  await page.goto(LEVEL_ONE_URL);

  await expectGraph(page, PACKAGE_IDS);
  expect(PACKAGE_IDS).toHaveLength(3);
});

test("o duplo clique em um pacote navega para os tipos dele e os desenha", async ({
  page,
}) => {
  await page.goto(LEVEL_ONE_URL);
  await expectGraph(page, PACKAGE_IDS);

  await expandOrders(page);

  expect(searchOf(page)).toMatchObject({
    analysis: ANALYSIS_ID,
    level: "TYPE",
    parent: ORDERS_ID,
  });
});

test("voltar restaura o nível 1 do cache, sem pedir a fatia de novo", async ({
  page,
}) => {
  await page.goto(LEVEL_ONE_URL);
  await expectGraph(page, PACKAGE_IDS);
  await expandOrders(page);
  const requestsBeforeBack = await countMarks(page, GRAPH_PERF.request);

  await page.goBack();

  await expect.poll(() => searchOf(page)).toMatchObject({ level: "PACKAGE" });
  await expectGraph(page, PACKAGE_IDS);
  expect(requestsBeforeBack).toBe(2);
  expect(await countMarks(page, GRAPH_PERF.request)).toBe(requestsBeforeBack);
});

test("um clique seleciona o nó: focus na URL e a borda de 4 px", async ({
  page,
}) => {
  await page.goto(LEVEL_ONE_URL);
  await expectGraph(page, PACKAGE_IDS);
  expect(await styleOf(page, BILLING_ID, "border-width")).not.toBe(
    SELECTED_BORDER_WIDTH
  );
  const billing = await renderedPosition(page, BILLING_ID);

  await page.mouse.click(billing.x, billing.y);

  await expect.poll(() => searchOf(page)).toMatchObject({ focus: BILLING_ID });
  await expect
    .poll(() => styleOf(page, BILLING_ID, "border-width"))
    .toBe(SELECTED_BORDER_WIDTH);
});

test("Enter no botão do inspetor expande, e o foco vai para a trilha e não para o <body>", async ({
  page,
}) => {
  await page.goto(`${LEVEL_ONE_URL}&focus=${ORDERS_ID}`);
  await expectGraph(page, PACKAGE_IDS);
  await page.getByRole("button", { name: t.inspector.expand }).focus();

  await page.keyboard.press("Enter");

  await expect.poll(() => searchOf(page)).toMatchObject({ level: "TYPE" });
  await expectGraph(page, TYPE_IDS);
  const trail = page.getByRole("navigation", {
    name: t.shell.breadcrumb.label,
  });
  await expect(trail.locator('[aria-current="page"]')).toBeFocused();
});

test("o canvas é uma instância só e o Cytoscape não injeta <style>", async ({
  page,
}) => {
  await page.goto(LEVEL_ONE_URL);
  await expectGraph(page, PACKAGE_IDS);

  await expect(page.locator(GRAPH_CONTAINER).locator("canvas")).toHaveCount(
    CANVAS_LAYERS
  );
  await expect(
    page.locator(`style[id="${CYTOSCAPE_STYLESHEET_ID}"]`)
  ).toHaveCount(0);
  await expect(
    page.locator(`meta[id="${CYTOSCAPE_STYLESHEET_ID}"]`)
  ).toHaveCount(1);
});

test("o seletor de tema reestiliza o canvas com o token escuro", async ({
  page,
}) => {
  await page.goto(LEVEL_ONE_URL);
  await expectGraph(page, PACKAGE_IDS);
  const lightLabel = await tokenAsCytoscapeRgb(page, LABEL_TOKEN);
  expect(await styleOf(page, ORDERS_ID, "color")).toBe(lightLabel);

  await page.getByRole("button", { name: "Alternar tema" }).click();
  await page.getByRole("menuitemradio", { name: "Escuro" }).click();

  await expect(page.locator("html")).toHaveClass(DARK_CLASS);
  const darkLabel = await tokenAsCytoscapeRgb(page, LABEL_TOKEN);
  expect(darkLabel).not.toBe(lightLabel);
  await expect.poll(() => styleOf(page, ORDERS_ID, "color")).toBe(darkLabel);
});

test("duas páginas novas da mesma vista dão as mesmas posições, byte a byte", async ({
  browser,
}, testInfo) => {
  const positionsOfFreshPage = () =>
    withFreshPage(browser, contextOptionsOf(testInfo), async (page) => {
      await serveMap(page);
      await page.goto(LEVEL_ONE_URL);
      await expectGraph(page, PACKAGE_IDS);
      return JSON.stringify(await readPositions(page));
    });

  const first = await positionsOfFreshPage();
  const second = await positionsOfFreshPage();

  expect(Object.keys(JSON.parse(first))).toStrictEqual(PACKAGE_IDS);
  expect(second).toBe(first);
});

const AT_BUDGET_SLICES: readonly SyntheticSliceOptions[] = (
  ["PACKAGE", "TYPE"] as const
).map((level) => ({ ...GRAPH_SLICE_BUDGETS[level], level, seed: 20 }));

for (const options of AT_BUDGET_SLICES) {
  test(`syntheticSlice ${options.level} ${options.nodes}/${options.edges}: válida no schema, determinística e com Σ pagerank = 1`, () => {
    const slice = syntheticSlice(options);
    const parsed = graphSliceSchema.parse(slice);
    const nodeIds = new Set(slice.nodes.map((node) => node.id));
    const allIds = new Set([...nodeIds, ...slice.edges.map((edge) => edge.id)]);
    const pairs = new Set(
      slice.edges.map((edge) => `${edge.source}\0${edge.target}`)
    );
    const pagerankSum = slice.nodes.reduce(
      (sum, node) => sum + node.pagerank,
      0
    );

    expect(parsed).toStrictEqual(slice);
    expect(JSON.stringify(syntheticSlice(options))).toBe(JSON.stringify(slice));
    expect(JSON.stringify(syntheticSlice({ ...options, seed: 21 }))).not.toBe(
      JSON.stringify(slice)
    );
    expect(Math.abs(pagerankSum - 1)).toBeLessThanOrEqual(PAGERANK_TOLERANCE);
    expect(
      slice.nodes.every((node) => node.pagerank >= 0 && node.pagerank <= 1)
    ).toBe(true);
    expect(allIds.size).toBe(slice.nodes.length + slice.edges.length);
    expect(
      slice.edges.every(
        (edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target)
      )
    ).toBe(true);
    expect(slice.edges.some((edge) => edge.source === edge.target)).toBe(false);
    expect(pairs.size).toBe(slice.edges.length);
    expect(slice.budget).toStrictEqual(GRAPH_SLICE_BUDGETS[options.level]);
    expect({
      edges: slice.edges.length,
      nodes: slice.nodes.length,
    }).toStrictEqual(slice.budget);
    expect(slice.totals).toStrictEqual(slice.budget);
    expect([slice.truncated, slice.aggregated]).toStrictEqual([false, false]);
  });
}

test("syntheticSlice recusa contagens acima do orçamento do nível", () => {
  for (const level of ["PACKAGE", "TYPE"] as const) {
    const { edges, nodes } = GRAPH_SLICE_BUDGETS[level];
    expect(() =>
      syntheticSlice({ edges, level, nodes: nodes + 1, seed: 20 })
    ).toThrow(RangeError);
    expect(() =>
      syntheticSlice({ edges: edges + 1, level, nodes, seed: 20 })
    ).toThrow(RangeError);
  }
});

const SEED = 20;
const LEVEL_ONE_SLICE: SyntheticSliceOptions = {
  ...GRAPH_SLICE_BUDGETS.PACKAGE,
  level: "PACKAGE",
  seed: SEED,
};
const TYPE_BUDGET = benchmarkTypeBudget();
const TYPE_SLICE: SyntheticSliceOptions = {
  edges: TYPE_BUDGET.edges,
  level: "TYPE",
  nodes: TYPE_BUDGET.nodes,
  seed: SEED,
};
const TYPE_URL = `${LEVEL_ONE_URL}&level=TYPE&parent=${SYNTHETIC_TYPE_PARENT_ID}`;

const LEVEL_ONE_RUNS = 7;
const MAX_RENDER_MS = 1000;
const PAN_ZOOM_PAGES = 3;
const MIN_FRAME_RATIO = 0.5;
const GESTURE_MS = 3000;
const DEEP_ZOOM_FACTOR = 4;
const CLICKS = 10;
const RNF05_BUDGET_MS = 100;

const SETTLE_MS = 500;
const EMPTY_POINT_SEARCH = {
  angles: 16,
  marginPx: 4,
  maxRadiusPx: 200,
  stepPx: 4,
} as const;
const CLICK_ZOOM = 1;
const CLICKABLE_SEARCH = { clearancePx: 4, edgeMarginPx: 20 } as const;
const CLICK_TIMEOUT_MS = 5000;
const BENCHMARK_TIMEOUT_MS = 300_000;

const GESTURES = ["G1", "G2", "G3"] as const;
type GestureName = (typeof GESTURES)[number];

const MEASURE_COMMAND = [
  process.env[TYPE_NODES_ENV] === undefined ? null : TYPE_BUDGET.source,
  `${BENCHMARK_OUT_ENV}=${process.env[BENCHMARK_OUT_ENV] ?? "benchmarks/graph-render.json"}`,
  "pnpm exec playwright test graph --grep @benchmark",
]
  .filter((part) => part !== null)
  .join(" ");

interface LevelOneRun {
  readonly loadAverage: readonly number[];

  readonly positionsSha256: string;

  readonly renderMs: number;
  readonly run: number;
  readonly slice: SyntheticSliceOptions;
}

interface GestureSession extends FrameSummary {
  readonly inputEvents: number;
  readonly start: Point;
  readonly zoomAfter: number;
  readonly zoomBefore: number;
}

interface PanZoomPage {
  readonly baselineMs: number;

  readonly fitZoom: number;
  readonly loadAverage: readonly number[];
  readonly page: number;
  readonly sessions: Readonly<Record<GestureName, GestureSession>>;

  readonly slice: SyntheticSliceOptions;
}

interface ClickSample {
  readonly click: number;
  readonly elementId: string;

  readonly latencyMs: number;
  readonly slice: SyntheticSliceOptions;
}

const measured: {
  clicks: readonly ClickSample[] | null;
  levelOne: readonly LevelOneRun[] | null;
  panZoom: readonly PanZoomPage[] | null;
} = { clicks: null, levelOne: null, panZoom: null };

const serveTypeSlice = (page: Page): Promise<void> =>
  serveSlices(page, [
    {
      method: "graph.slice",
      request: { level: "TYPE", parentId: SYNTHETIC_TYPE_PARENT_ID },
      result: syntheticSlice(TYPE_SLICE),
    },
  ]);

const measureLevelOneRun = async (
  page: Page,
  run: number,
  slice: GraphSlice
): Promise<LevelOneRun> => {
  const loadAverage = os.loadavg();
  await serveSlices(page, [
    { method: "graph.slice", request: { level: "PACKAGE" }, result: slice },
  ]);
  await page.goto(LEVEL_ONE_URL);
  await waitForFreshReady(page, LEVEL_ONE_SLICE.nodes);
  const renders = await page.evaluate(
    (name) =>
      performance
        .getEntriesByName(name, "measure")
        .map((entry) => entry.duration),
    GRAPH_PERF.render
  );
  expect(renders).toHaveLength(1);
  return {
    loadAverage,
    positionsSha256: sha256(JSON.stringify(await readPositions(page))),
    renderMs: renders[0],
    run,
    slice: LEVEL_ONE_SLICE,
  };
};

const runSession = async (
  page: Page,
  baselineMs: number,
  gesture: (start: Point) => Promise<RecordedGesture>,
  start: Point
): Promise<GestureSession> => {
  const zoomBefore = await readZoom(page);
  const recorded = await gesture(start);
  return {
    ...summarizeFrames(recorded.stamps, baselineMs),
    inputEvents: recorded.inputEvents,
    start,
    zoomAfter: await readZoom(page),
    zoomBefore,
  };
};

const emptyPointNearCentre = async (page: Page) =>
  findEmptyPoint(
    await readNodeHitAreas(page),
    centreOf(await containerBox(page)),
    EMPTY_POINT_SEARCH
  );

const measurePanZoomPage = async (
  page: Page,
  pageNumber: number
): Promise<PanZoomPage> => {
  const loadAverage = os.loadavg();
  await serveTypeSlice(page);
  await page.goto(TYPE_URL);
  await waitForFreshReady(page, TYPE_SLICE.nodes);
  const baselineMs = await measureIdleBaseline(page);
  const drag = (start: Point) => dragPan(page, start, GESTURE_MS);
  const wheel = (at: Point) => trackpadWheel(page, at, GESTURE_MS);

  const fitZoom = await fitGraph(page);
  await settle(page, SETTLE_MS);
  const g1 = await runSession(
    page,
    baselineMs,
    drag,
    await emptyPointNearCentre(page)
  );

  await fitGraph(page);
  await settle(page, SETTLE_MS);
  const g2 = await runSession(
    page,
    baselineMs,
    wheel,
    centreOf(await containerBox(page))
  );

  await fitGraph(page);
  await setZoom(page, fitZoom * DEEP_ZOOM_FACTOR);
  await settle(page, SETTLE_MS);
  const g3 = await runSession(
    page,
    baselineMs,
    drag,
    await emptyPointNearCentre(page)
  );

  return {
    baselineMs,
    fitZoom,
    loadAverage,
    page: pageNumber,
    sessions: { G1: g1, G2: g2, G3: g3 },
    slice: TYPE_SLICE,
  };
};

const measureClick = async (
  page: Page,
  target: NodeHitArea,
  click: number
): Promise<ClickSample> => {
  await armSelectionProbe(
    page,
    target.id,
    SELECTED_BORDER_WIDTH,
    CLICK_TIMEOUT_MS
  );
  const position = await renderedPosition(page, target.id);
  await page.mouse.click(position.x, position.y);
  const latencyMs = await readSelectionLatency(page);
  await expect.poll(() => searchOf(page)).toMatchObject({ focus: target.id });
  return { click, elementId: target.id, latencyMs, slice: TYPE_SLICE };
};

const buildRule = () => {
  const { dragAngleStepRad, dragRadiusPx, pauseMs, trackpadDeltasPx } =
    GESTURE_INPUT;
  const drag = `real mouse drag in a ${dragRadiusPx} px circle advancing ${dragAngleStepRad} rad per move, one move then a ${pauseMs} ms pause, from the empty point nearest the canvas centre`;
  return {
    levelOneRender: {
      criterion:
        "PASS iff every run < maxRenderMs; render = the strata:graph:render measure, from the strata:graph:request mark (graph.slice queryFn) to strata:graph:ready (the 2nd animation frame after layoutstop)",
      freshPages: LEVEL_ONE_RUNS,
      maxRenderMs: MAX_RENDER_MS,
      slice: LEVEL_ONE_SLICE,
    },
    recordedNotGating: {
      clickSelectionLatency: `RNF05, < ${RNF05_BUDGET_MS} ms, gated at P50: ${CLICKS} clicks at zoom ${CLICK_ZOOM} on mulberry32(${SEED})-drawn nodes, pointerdown timeStamp to the first rAF with border-width ${SELECTED_BORDER_WIDTH}`,
      positionsHash:
        "SHA-256 of the sorted { id: renderedPosition } JSON, one per level-1 page",
      webkit: "Playwright WebKit, informative, in graph-render.webkit.json",
    },
    typeBudgetPanZoom: {
      baseline: "b = median idle rAF interval over 3 s on the page",
      criterion:
        "frameRatio = frames / (span / b); PASS iff frameRatio >= minFrameRatio in all 9 sessions",
      freshPages: PAN_ZOOM_PAGES,
      gestureMs: GESTURE_MS,
      gestures: {
        G1: `at the fit zoom: ${drag}`,
        G2: `at the fit zoom: page.mouse.wheel at the canvas centre, deltas cycling ${Math.min(...trackpadDeltasPx)}-${Math.max(...trackpadDeltasPx)} px (trackpad-like), direction flipping every ${GESTURE_INPUT.wheelDirectionRun} events, one event then a ${pauseMs} ms pause`,
        G3: `__STRATA_GRAPH__.setZoom(${DEEP_ZOOM_FACTOR} x the fit zoom), then: ${drag}`,
      },
      minFrameRatio: MIN_FRAME_RATIO,
      settleBeforeEachGestureMs: SETTLE_MS,
      slice: TYPE_SLICE,
      sliceSource: TYPE_BUDGET.source,
    },
  };
};

const buildVerdict = (
  levelOne: readonly LevelOneRun[],
  panZoom: readonly PanZoomPage[],
  clicks: readonly ClickSample[],
  projectName: string
) => {
  const renderMs = levelOne.map((run) => run.renderMs);
  const levelOnePass =
    renderMs.length === LEVEL_ONE_RUNS &&
    renderMs.every((ms) => ms < MAX_RENDER_MS);
  const sessions = panZoom.flatMap((page) =>
    GESTURES.map((gesture) => ({
      frameRatio: page.sessions[gesture].frameRatio,
      gesture,
      page: page.page,
    }))
  );
  const frameRatios = sessions.map((session) => session.frameRatio);
  const panZoomPass =
    sessions.length === PAN_ZOOM_PAGES * GESTURES.length &&
    frameRatios.every((ratio) => ratio >= MIN_FRAME_RATIO);
  const latencies = clicks.map((sample) => sample.latencyMs);
  const hashes = [...new Set(levelOne.map((run) => run.positionsSha256))];
  return {
    clauses: {
      levelOneRender: {
        maxRenderMs: MAX_RENDER_MS,
        observedMaxMs: Math.max(...renderMs),
        observedMedianMs: median(renderMs),
        pass: levelOnePass,
        runsMs: renderMs,
      },
      typeBudgetPanZoom: {
        minFrameRatio: MIN_FRAME_RATIO,
        observedMinFrameRatio: Math.min(...frameRatios),
        pass: panZoomPass,
        sessions,
      },
    },
    decides: projectName === GATING_PROJECT,
    outcome: levelOnePass && panZoomPass ? "PASS" : "FAIL",
    project: projectName,
    recorded: {
      clickSelectionLatency: {
        budgetMs: RNF05_BUDGET_MS,
        clicks: latencies.length,
        gatedAt: "P50",
        maxMs: Math.max(...latencies),
        medianMs: median(latencies),
        minMs: Math.min(...latencies),
      },
      positionsHash: {
        freshPages: levelOne.length,
        identical: hashes.length === 1,
        sha256: hashes,
      },
    },
  } as const;
};

test.describe("Benchmark do grafo sobre fatias sintéticas", () => {
  test.describe.configure({ mode: "serial", timeout: BENCHMARK_TIMEOUT_MS });

  test("@benchmark level-1 render: PACKAGE 300/1 200, 7 páginas novas", async ({
    browser,
  }, testInfo) => {
    const slice = syntheticSlice(LEVEL_ONE_SLICE);
    const runs: LevelOneRun[] = [];

    for (let run = 1; run <= LEVEL_ONE_RUNS; run += 1) {
      runs.push(
        // biome-ignore lint/performance/noAwaitInLoops: uma página por vez, para não dividir a máquina
        await withFreshPage(browser, contextOptionsOf(testInfo), (page) =>
          measureLevelOneRun(page, run, slice)
        )
      );
    }

    measured.levelOne = runs;
    expect(runs).toHaveLength(LEVEL_ONE_RUNS);
  });

  test(`@benchmark type-budget pan-zoom: TYPE ${TYPE_SLICE.nodes}/${TYPE_SLICE.edges}, G1 G2 G3 em 3 páginas novas`, async ({
    browser,
  }, testInfo) => {
    const pages: PanZoomPage[] = [];

    for (let pageNumber = 1; pageNumber <= PAN_ZOOM_PAGES; pageNumber += 1) {
      pages.push(
        // biome-ignore lint/performance/noAwaitInLoops: uma medição por vez, para não dividir a GPU
        await withFreshPage(browser, contextOptionsOf(testInfo), (page) =>
          measurePanZoomPage(page, pageNumber)
        )
      );
    }

    measured.panZoom = pages;
    const inputs = pages.flatMap((page) =>
      GESTURES.map((gesture) => page.sessions[gesture].inputEvents)
    );
    expect(inputs).toHaveLength(PAN_ZOOM_PAGES * GESTURES.length);
    expect(Math.min(...inputs)).toBeGreaterThan(0);
  });

  test(`@benchmark click selection latency: 10 cliques na fatia TYPE ${TYPE_SLICE.nodes}/${TYPE_SLICE.edges}`, async ({
    browser,
  }, testInfo) => {
    const clicks = await withFreshPage(
      browser,
      contextOptionsOf(testInfo),
      async (page) => {
        await serveTypeSlice(page);
        await page.goto(TYPE_URL);
        await waitForFreshReady(page, TYPE_SLICE.nodes);
        await setZoom(page, CLICK_ZOOM);
        await settle(page, SETTLE_MS);
        const clickable = listClickableNodes(
          await readNodeHitAreas(page),
          await containerBox(page),
          CLICKABLE_SEARCH
        );
        const samples: ClickSample[] = [];
        for (const [index, target] of drawSeeded(
          clickable,
          CLICKS,
          SEED
        ).entries()) {
          // biome-ignore lint/performance/noAwaitInLoops: cada clique espera a seleção do anterior
          samples.push(await measureClick(page, target, index + 1));
        }
        return samples;
      }
    );

    measured.clicks = clicks;
    expect(clicks).toHaveLength(CLICKS);
  });

  test("@benchmark veredito: aplica o limiar fixado e grava o registro", async ({
    browser,
  }, testInfo) => {
    const { clicks, levelOne, panZoom } = measured;
    if (clicks === null || levelOne === null || panZoom === null) {
      throw new Error("uma medição @benchmark não rodou neste worker");
    }
    const environment = await withFreshPage(
      browser,
      contextOptionsOf(testInfo),
      async (page) => {
        await serveMap(page);
        await page.goto(LEVEL_ONE_URL);
        await expectGraph(page, PACKAGE_IDS);
        return captureEnvironment({
          baselineMs: await measureIdleBaseline(page),
          browser,
          page,
          project: testInfo.project,
        });
      }
    );
    const verdict = buildVerdict(
      levelOne,
      panZoom,
      clicks,
      testInfo.project.name
    );
    const written = writeBenchmarkRecord(
      createBenchmarkRecord({
        environment,
        generator: {
          ...SYNTHETIC_SLICE_GENERATOR,
          clickTargets: `at zoom ${CLICK_ZOOM} around the canvas centre, mulberry32(${SEED}) over the nodes a click at their rendered position hits unambiguously (no other node's hit area within ${CLICKABLE_SEARCH.clearancePx} px of it, ${CLICKABLE_SEARCH.edgeMarginPx} px from the canvas edge), sorted by id`,
          seeds: { clickTargets: SEED, levelOneRender: SEED, typeBudget: SEED },
          slices: { levelOneRender: LEVEL_ONE_SLICE, typeBudget: TYPE_SLICE },
        },
        measure: MEASURE_COMMAND,
        results: {
          clickSelectionLatency: clicks,
          levelOneRender: levelOne,
          typeBudgetPanZoom: panZoom,
        },
        rule: buildRule(),
        verdict,
      }),
      testInfo.project.name
    );
    testInfo.annotations.push({
      description: JSON.stringify({ verdict, written }),
      type: "verdict",
    });

    if (testInfo.project.name !== GATING_PROJECT) {
      return;
    }
    expect(environment.browser.channel).toBe("chromium");
    expect(environment.browser.executablePath).not.toBeNull();
    expect(environment.browser.webglRenderer).not.toMatch(SOFTWARE_RENDERER);
    expect(environment.viewport).toStrictEqual({ height: 800, width: 1280 });
    expect(environment.devicePixelRatio).toBe(1);
    expect(verdict.outcome).toBe("PASS");
  });
});
