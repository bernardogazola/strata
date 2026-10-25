import { expect, type Locator, type Page, test } from "@playwright/test";
import { GRAPH_PERF } from "@/features/graph/perf";
import { countPhrase } from "@/features/repository/count-phrase";
import { interpolate } from "@/i18n/format";
import {
  dependencyTypeLabels,
  dependencyTypePassiveLabels,
  resolutionQualityLabels,
  typeKindLabels,
} from "@/i18n/labels";
import { t } from "@/i18n/messages";
import {
  type DependencyDirection,
  dependencyTypeSchema,
} from "@/protocol/enums";
import type { ProtocolEventName } from "@/protocol/envelope";
import {
  type DependencyEntry,
  type GraphSlice,
  graphSliceSchema,
} from "@/protocol/graph";
import { METHODS } from "@/protocol/registry";
import { repositoryDescriptionSchema } from "@/protocol/repository";
import {
  DRILLED_PACKAGE_ID,
  dependencyPageOf,
  detailOf,
  labelOf,
  PACKAGE_IDS,
  SEARCH_HITS,
  SEARCH_QUERY,
  typeSliceOf,
} from "@/test/fixture-graph";
import {
  AFTER_GRAPH,
  analysisCompleted,
  analysisStarted,
  BEFORE_GRAPH,
  COMPLETED,
  countDelta,
  emit,
  type LoggedRequest,
  openFakeRepository,
  requests,
  requestsTo,
  runningThrough,
  serveRun,
  setTranscripts,
  stageCompleted,
  statusOverride,
} from "./support/fake-run";
import {
  countMarks,
  expectGraph,
  fixtureResponse,
  historyLength,
  searchOf,
  settle,
  waitForFreshReady,
} from "./support/graph-page";

const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";

const ANALYSIS_ID = METHODS["analysis.start"].result.parse(
  fixtureResponse("analysis.start.ok.jsonl")
).analysisId;

const LEVEL_ONE_URL = `/repository/${HANDLE}?analysis=${ANALYSIS_ID}`;

const REPOSITORY = repositoryDescriptionSchema.parse(
  fixtureResponse("repository.describe.ok.jsonl")
);

const CORE = DRILLED_PACKAGE_ID;

const LEVEL_ONE_IDS = [...PACKAGE_IDS].sort();
const CORE_SLICE = typeSliceOf(CORE);
const CORE_TYPE_IDS = CORE_SLICE.nodes.map((node) => node.id).sort();

const STAGE_COUNT = COMPLETED.stages.length;

const JOURNEY_TIMEOUT_MS = 180_000;

const QUIET_MS = 300;

const entryByRole = (): string => {
  const MIN_USED_BY = 2;
  const matches = CORE_SLICE.nodes
    .filter((node) => node.kind === "TYPE")
    .map((node) => node.id)
    .filter((id) => {
      const usedBy = dependencyPageOf(id, "IN").items;
      const dependsOn = dependencyPageOf(id, "OUT").items;
      return (
        usedBy.length >= MIN_USED_BY &&
        dependsOn.some((row) => !row.isExternal) &&
        dependsOn.some((row) => row.isExternal)
      );
    });
  if (matches.length !== 1) {
    throw new Error(
      `o mundo tem ${matches.length} tipos em ${CORE} usados por dois tipos e que dependem de um tipo interno e de um externo`
    );
  }
  return matches[0] ?? "";
};

const ENTRY = entryByRole();

const truncatedCoreSlice = (): GraphSlice =>
  graphSliceSchema.parse({
    ...CORE_SLICE,
    edges: CORE_SLICE.edges.filter(
      (edge) => edge.source !== ENTRY && edge.target !== ENTRY
    ),
    nodes: CORE_SLICE.nodes.filter((node) => node.id !== ENTRY),
    truncated: true,
  });

const mainNamed = (page: Page, name: string) =>
  page.getByRole("main", { name });

const inspectorOf = (page: Page) =>
  page.getByRole("complementary", { name: t.shell.regions.inspector });

const sectionsOf = (inspector: Locator) =>
  inspector.getByRole("tablist", { name: t.inspector.tabs.label });

const directionOption = (inspector: Locator, label: string): Locator => {
  const name = t.inspector.dependencies.direction;
  const group = inspector
    .getByRole("radiogroup", { name })
    .or(inspector.getByRole("group", { name }));
  return group
    .getByRole("radio", { exact: true, name: label })
    .or(group.getByRole("button", { exact: true, name: label }));
};

const PACKAGE_ID_PREFIX = "pkg:";

const expectedVerbs = (
  entry: DependencyEntry,
  direction: DependencyDirection
): string => {
  const labels =
    direction === "OUT" ? dependencyTypePassiveLabels : dependencyTypeLabels;
  return dependencyTypeSchema.options
    .filter((type) => entry.types[type] !== undefined)
    .map((type) => labels[type])
    .join(", ");
};

type ExpectedRow = readonly string[];

const expectedRows = (
  elementId: string,
  direction: DependencyDirection
): ExpectedRow[] => {
  const { items, totals } = dependencyPageOf(elementId, direction);
  const inspectedPackage = detailOf(elementId).packageId;
  const rows = items.map((entry): ExpectedRow => {
    const crossPackage =
      !entry.isExternal &&
      entry.kind === "TYPE" &&
      entry.packageId !== null &&
      entry.packageId !== inspectedPackage;
    return [
      entry.label,
      expectedVerbs(entry, direction),
      resolutionQualityLabels[entry.resolution],
      ...(crossPackage && entry.packageId !== null
        ? [entry.packageId.slice(PACKAGE_ID_PREFIX.length)]
        : []),
    ];
  });
  const firstExternal = items.findIndex((entry) => entry.isExternal);
  if (firstExternal === -1) {
    return rows;
  }
  const heading: ExpectedRow = [
    t.inspector.dependencies.externalHeading,
    countPhrase(
      totals.outExternal ?? 0,
      t.inspector.dependencies.externalCount
    ),
  ];
  return [
    ...rows.slice(0, firstExternal),
    heading,
    ...rows.slice(firstExternal),
  ];
};

const reachableOf = (
  elementId: string,
  direction: DependencyDirection
): number => {
  const { totals } = dependencyPageOf(elementId, direction);
  if (direction === "IN") {
    return totals.in;
  }
  const externals = totals.outExternal ?? 0;
  return totals.out + externals + (externals > 0 ? 1 : 0);
};

const expectTable = async (
  inspector: Locator,
  elementId: string,
  direction: DependencyDirection
): Promise<void> => {
  const table = inspector.getByRole("table");
  const expected = expectedRows(elementId, direction);
  const rows = table.locator("tbody").getByRole("row");
  await expect(table).toHaveAttribute(
    "aria-rowcount",
    String(reachableOf(elementId, direction) + 1)
  );
  await expect(rows).toHaveCount(expected.length);
  await Promise.all(
    expected.flatMap((fragments, index) =>
      fragments.map((fragment) =>
        expect(rows.nth(index)).toContainText(fragment)
      )
    )
  );
};

const requestsWith = (
  log: readonly LoggedRequest[],
  method: string,
  fields: Readonly<Record<string, unknown>>
): LoggedRequest[] =>
  requestsTo(log, method).filter((entry) =>
    Object.entries(fields).every(
      ([key, value]) =>
        (entry.payload as Record<string, unknown> | null)?.[key] === value
    )
  );

const expectRequestCount = async (
  page: Page,
  method: string,
  count: number
): Promise<void> => {
  await expect
    .poll(async () => requestsTo(await requests(page), method).length)
    .toBe(count);
};

const ELEMENT_METHOD = /^element\./;
const DIGIT = /\d/;

const DEPENDENCY_PAGE_LIMIT = 500;

const expectMethods = async (
  inspector: Locator,
  elementId: string
): Promise<void> => {
  const { methods } = detailOf(elementId);
  expect(
    methods.every(
      (method) =>
        method.cc === null &&
        method.lines === null &&
        !DIGIT.test(method.signature)
    )
  ).toBe(true);
  const { ccNotApplicable, ccNote } = t.inspector.methods;
  await expect(inspector).toContainText(ccNote);
  const text = (await inspector.textContent()) ?? "";
  const starts = methods.map((method) => text.indexOf(method.signature));
  const end = text.indexOf(ccNote, starts.at(-1) ?? 0);
  const bounds = [...starts, end];
  const segments = starts.map((start, index) =>
    text.slice(start, bounds[index + 1])
  );
  expect(starts.every((start) => start !== -1)).toBe(true);
  expect(bounds).toStrictEqual([...bounds].sort((a, b) => a - b));
  for (const segment of segments) {
    expect(segment).toContain(ccNotApplicable);
    expect(segment).toContain(t.inspector.header.noLines);
    expect(segment).not.toMatch(DIGIT);
  }
};

type ScriptedEvent = readonly [ProtocolEventName, unknown];

const UNTIL_GRAPH: readonly ScriptedEvent[] = [
  ["analysis.started", analysisStarted()],
  ...BEFORE_GRAPH.stages
    .filter((row) => row.status !== "PENDING")
    .map(
      (row): ScriptedEvent => [
        "analysis.stage.completed",
        stageCompleted(row.stage),
      ]
    ),
];

test("do início ao inspetor: o mapa monta no STRUCTURAL_GRAPH com a execução em andamento, e voltar e avançar não pedem nada", async ({
  page,
}) => {
  test.setTimeout(JOURNEY_TIMEOUT_MS);
  expect(ANALYSIS_ID).toBe(COMPLETED.analysisId);
  await serveRun(page, [statusOverride(BEFORE_GRAPH)]);

  await openFakeRepository(page);
  const prepare = mainNamed(page, t.shell.regions.prepare);
  await expect(
    prepare.getByRole("heading", { level: 1, name: REPOSITORY.displayName })
  ).toBeVisible();

  await prepare.getByRole("button", { name: t.config.analyze }).click();
  await expect
    .poll(() => searchOf(page))
    .toStrictEqual({ analysis: ANALYSIS_ID, level: "PACKAGE" });
  const progress = mainNamed(page, t.shell.regions.analysis);
  await expect(progress).toBeVisible();
  await expect(
    progress
      .getByRole("list", { name: t.analysis.stages.toggle })
      .locator("li[data-stage]")
  ).toHaveCount(STAGE_COUNT);
  const banner = page.getByRole("banner");
  await expect(banner).toContainText(t.header.analysis.RUNNING);
  const atStart = await requests(page);
  expect(requestsTo(atStart, "graph.slice")).toHaveLength(0);
  expect(requestsTo(atStart, "outline.children")).toHaveLength(0);
  expect(requestsTo(atStart, "graph.search")).toHaveLength(0);
  const describes = requestsTo(atStart, "repository.describe").length;
  let statuses = requestsTo(atStart, "analysis.status").length;

  for (const [event, payload] of UNTIL_GRAPH) {
    // biome-ignore lint/performance/noAwaitInLoops: cada evento espera o pedido de status que ele dispara
    await emit(page, event, payload);
    statuses += 1;
    await expectRequestCount(page, "analysis.status", statuses);
  }
  await expect(progress).toBeVisible();
  const beforeGraph = await requests(page);
  expect(requestsTo(beforeGraph, "graph.slice")).toHaveLength(0);
  expect(requestsTo(beforeGraph, "repository.describe")).toHaveLength(
    describes
  );

  await setTranscripts(page, [statusOverride(AFTER_GRAPH)]);
  await emit(
    page,
    "analysis.stage.completed",
    stageCompleted("STRUCTURAL_GRAPH")
  );
  await expect(mainNamed(page, t.shell.regions.canvas)).toBeVisible();
  await expectGraph(page, LEVEL_ONE_IDS);
  await expect(banner).toContainText(t.header.analysis.RUNNING);
  await expect(
    banner.getByRole("button", { name: t.analysis.again })
  ).toHaveCount(0);
  const mapMounted = await requests(page);
  expect(requestsTo(mapMounted, "graph.slice")).toHaveLength(1);
  expect(
    requestsWith(mapMounted, "graph.slice", { level: "PACKAGE" })
  ).toHaveLength(1);
  expect(requestsTo(mapMounted, "repository.describe")).toHaveLength(describes);
  statuses = requestsTo(mapMounted, "analysis.status").length;

  await setTranscripts(page, [
    statusOverride(runningThrough("GIT_HISTORY_DETAILED")),
  ]);
  await emit(
    page,
    "analysis.stage.completed",
    stageCompleted("GIT_HISTORY_DETAILED")
  );
  await expectRequestCount(page, "analysis.status", statuses + 1);
  await settle(page, QUIET_MS);
  expect(countDelta(mapMounted, await requests(page))).toStrictEqual({
    "analysis.status": 1,
  });

  const beforeTerminal = await requests(page);
  await setTranscripts(page, [statusOverride(COMPLETED)]);
  await emit(page, "analysis.completed", analysisCompleted());
  await expect(
    banner.getByRole("button", { name: t.analysis.again })
  ).toBeVisible();
  await expect(banner).not.toContainText(t.header.analysis.RUNNING);
  await expect
    .poll(async () => countDelta(beforeTerminal, await requests(page)))
    .toStrictEqual({
      "analysis.status": 1,
      "graph.slice": 1,
      "outline.children": 1,
      "repository.describe": 1,
    });

  const inspector = inspectorOf(page);
  const tree = page.getByRole("tree", { name: t.outline.title });
  const coreDetail = detailOf(CORE);
  let entries = await historyLength(page);
  await tree.locator(`[role="treeitem"][data-element-id="${CORE}"]`).click();
  await expect
    .poll(() => searchOf(page))
    .toStrictEqual({ analysis: ANALYSIS_ID, focus: CORE, level: "PACKAGE" });
  expect(await historyLength(page)).toBe(entries);
  await expect(
    inspector.getByRole("heading", { exact: true, name: labelOf(CORE) })
  ).toBeVisible();
  await expect(
    sectionsOf(inspector).getByRole("tab", { name: t.inspector.tabs.overview })
  ).toHaveAttribute("aria-selected", "true");
  expect(coreDetail.graph.cycle).not.toBeNull();
  await expect(inspector).toContainText(
    countPhrase(
      coreDetail.graph.cycle?.size ?? 0,
      t.inspector.graphFacts.cycleSize.PACKAGE
    )
  );
  await expect(inspector).toContainText(t.inspector.metrics.loc);
  await expectRequestCount(page, "element.describe", 1);
  expect(
    requestsWith(await requests(page), "element.describe", { elementId: CORE })
  ).toHaveLength(1);

  await inspector.getByRole("button", { name: t.inspector.expand }).click();
  await expect
    .poll(() => searchOf(page))
    .toStrictEqual({ analysis: ANALYSIS_ID, level: "TYPE", parent: CORE });
  await expectGraph(page, CORE_TYPE_IDS);
  expect(await historyLength(page)).toBe(entries + 1);
  entries += 1;

  const entryDetail = detailOf(ENTRY);
  await tree.locator(`[role="treeitem"][data-element-id="${ENTRY}"]`).click();
  await expect
    .poll(() => searchOf(page))
    .toStrictEqual({
      analysis: ANALYSIS_ID,
      focus: ENTRY,
      level: "TYPE",
      parent: CORE,
    });
  expect(await historyLength(page)).toBe(entries);
  await expect(
    inspector.getByRole("heading", { exact: true, name: labelOf(ENTRY) })
  ).toBeVisible();
  if (entryDetail.typeKind === null) {
    throw new Error(`${ENTRY} é um tipo e precisa ter typeKind`);
  }
  await expect(inspector).toContainText(typeKindLabels[entryDetail.typeKind], {
    ignoreCase: true,
  });
  await expectMethods(inspector, ENTRY);
  await expectRequestCount(page, "element.describe", 2);
  expect(
    requestsWith(await requests(page), "element.describe", { elementId: ENTRY })
  ).toHaveLength(1);

  await sectionsOf(inspector)
    .getByRole("tab", { name: t.inspector.tabs.dependencies })
    .click();
  await expect
    .poll(() => searchOf(page))
    .toStrictEqual({
      analysis: ANALYSIS_ID,
      focus: ENTRY,
      level: "TYPE",
      parent: CORE,
      tab: "dependencies",
    });
  expect(await historyLength(page)).toBe(entries);
  await expectTable(inspector, ENTRY, "OUT");
  const outPage = dependencyPageOf(ENTRY, "OUT");
  await expect(inspector).toContainText(
    interpolate(t.inspector.dependencies.summary.withExternal, {
      external: countPhrase(
        outPage.totals.outExternal ?? 0,
        t.inspector.dependencies.externalCount
      ),
      internal: countPhrase(
        entryDetail.graph.fanOut,
        t.inspector.dependencies.summary.internal.TYPE
      ),
    })
  );
  await expectRequestCount(page, "element.dependencies", 1);
  const outRequests = requestsTo(await requests(page), "element.dependencies");
  expect(outRequests.map((entry) => entry.payload)).toMatchObject([
    { direction: "OUT", elementId: ENTRY, limit: DEPENDENCY_PAGE_LIMIT },
  ]);

  await directionOption(inspector, t.inspector.usedBy).click();
  await expect
    .poll(() => searchOf(page))
    .toStrictEqual({
      analysis: ANALYSIS_ID,
      direction: "IN",
      focus: ENTRY,
      level: "TYPE",
      parent: CORE,
      tab: "dependencies",
    });
  expect(await historyLength(page)).toBe(entries);
  await expectTable(inspector, ENTRY, "IN");
  await expectRequestCount(page, "element.dependencies", 2);
  expect(
    requestsWith(await requests(page), "element.dependencies", {
      direction: "IN",
      elementId: ENTRY,
    })
  ).toHaveLength(1);

  await settle(page, QUIET_MS);
  const settled = (await requests(page)).length;
  const sliceMarks = await countMarks(page, GRAPH_PERF.request);
  await page.goBack();
  await expect
    .poll(() => searchOf(page))
    .toStrictEqual({ analysis: ANALYSIS_ID, focus: CORE, level: "PACKAGE" });
  await expectGraph(page, LEVEL_ONE_IDS);
  await expect(
    inspector.getByRole("heading", { exact: true, name: labelOf(CORE) })
  ).toBeVisible();
  await expect(inspector).toContainText(t.inspector.metrics.loc);
  await settle(page, QUIET_MS);
  expect(await requests(page)).toHaveLength(settled);
  expect(await countMarks(page, GRAPH_PERF.request)).toBe(sliceMarks);

  await page.goForward();
  await expect
    .poll(() => searchOf(page))
    .toStrictEqual({
      analysis: ANALYSIS_ID,
      direction: "IN",
      focus: ENTRY,
      level: "TYPE",
      parent: CORE,
      tab: "dependencies",
    });
  await expectGraph(page, CORE_TYPE_IDS);
  await expectTable(inspector, ENTRY, "IN");
  await settle(page, QUIET_MS);
  expect(await requests(page)).toHaveLength(settled);
  expect(await countMarks(page, GRAPH_PERF.request)).toBe(sliceMarks);
});

test("um tipo que a fatia truncada deixou fora do mapa: o inspetor diz que está fora e mostra o describe dele", async ({
  page,
}) => {
  const truncated = truncatedCoreSlice();
  expect(SEARCH_HITS.map((hit) => hit.elementId)).toContain(ENTRY);
  await serveRun(page, [
    {
      method: "graph.slice",
      request: { level: "TYPE", parentId: CORE },
      result: truncated,
    },
    statusOverride(COMPLETED),
  ]);
  await page.goto(LEVEL_ONE_URL);
  await expectGraph(page, LEVEL_ONE_IDS);

  await page
    .getByRole("searchbox", { name: t.search.label })
    .pressSequentially(SEARCH_QUERY);
  await page
    .getByRole("listbox", { name: t.search.results })
    .locator(`[role="option"][data-element-id="${ENTRY}"]`)
    .click();

  await expect
    .poll(() => searchOf(page))
    .toStrictEqual({
      analysis: ANALYSIS_ID,
      focus: ENTRY,
      level: "TYPE",
      parent: CORE,
    });
  await expectGraph(page, truncated.nodes.map((node) => node.id).sort());
  const inspector = inspectorOf(page);
  await expect(inspector).toContainText(t.inspector.notOnMap.truncated);
  await expect(
    inspector.getByRole("heading", { exact: true, name: labelOf(ENTRY) })
  ).toBeVisible();
  await expect(inspector).toContainText(t.inspector.metrics.loc);
  await expectRequestCount(page, "element.describe", 1);
  expect(
    requestsWith(await requests(page), "element.describe", { elementId: ENTRY })
  ).toHaveLength(1);
});

test("um grupo de pacotes nunca pede element.describe", async ({ page }) => {
  const aggregated = graphSliceSchema.parse(
    fixtureResponse("graph.slice.package-aggregated.jsonl")
  );
  const group = aggregated.nodes.find((node) => node.kind === "PACKAGE_PREFIX");
  if (group === undefined) {
    throw new Error("graph.slice.package-aggregated não tem grupo de pacotes");
  }
  await serveRun(page, [
    {
      method: "graph.slice",
      request: { level: "PACKAGE" },
      result: aggregated,
    },
    statusOverride(COMPLETED),
  ]);

  await page.goto(`${LEVEL_ONE_URL}&focus=${encodeURIComponent(group.id)}`);
  await waitForFreshReady(page, aggregated.nodes.length);

  await expect(inspectorOf(page)).toContainText(t.inspector.group.typeCount);
  await settle(page, QUIET_MS);
  const log = await requests(page);
  expect(requestsWith(log, "graph.slice", { level: "PACKAGE" })).toHaveLength(
    1
  );
  expect(
    log.filter((entry) => ELEMENT_METHOD.test(entry.method))
  ).toStrictEqual([]);
});
