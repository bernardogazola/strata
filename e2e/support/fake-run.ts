import { expect, type Page } from "@playwright/test";
import { t } from "@/i18n/messages";
import type { AnalysisStage } from "@/protocol/analysis";
import type { ProtocolEventName } from "@/protocol/envelope";
import { EVENTS } from "@/protocol/registry";
import {
  type AnalysisStatusReport,
  analysisStatusReportSchema,
} from "@/protocol/repository";
import {
  MAP_READY_STATUS,
  type StatusOverride,
  type TranscriptOverride,
} from "./graph-page";

export const COMPLETED: AnalysisStatusReport = MAP_READY_STATUS.result;

const GRAPH_STAGE: AnalysisStage = "STRUCTURAL_GRAPH";

export const runningThrough = (last: AnalysisStage): AnalysisStatusReport => {
  const reached = COMPLETED.stages.findIndex((row) => row.stage === last);
  if (reached === -1) {
    throw new Error(`analysis.status.completed não tem a etapa ${last}`);
  }
  return analysisStatusReportSchema.parse({
    ...COMPLETED,
    finishedAt: null,
    stages: COMPLETED.stages.map((row, index) =>
      index <= reached
        ? row
        : {
            ...row,
            counters: {},
            durationMs: null,
            startedAt: null,
            status: "PENDING",
          }
    ),
    status: "RUNNING",
  });
};

const isGraphCompleted = (report: AnalysisStatusReport): boolean =>
  report.stages.some(
    (row) => row.stage === GRAPH_STAGE && row.status === "COMPLETED"
  );

const stageBeforeGraph = (): AnalysisStage => {
  const graphIndex = COMPLETED.stages.findIndex(
    (row) => row.stage === GRAPH_STAGE
  );
  const previous = COMPLETED.stages[graphIndex - 1];
  if (previous === undefined) {
    throw new Error(
      `analysis.status.completed não tem etapa antes de ${GRAPH_STAGE}`
    );
  }
  return previous.stage;
};

export const BEFORE_GRAPH: AnalysisStatusReport = runningThrough(
  stageBeforeGraph()
);

export const AFTER_GRAPH: AnalysisStatusReport = runningThrough(GRAPH_STAGE);

if (
  BEFORE_GRAPH.status !== "RUNNING" ||
  isGraphCompleted(BEFORE_GRAPH) ||
  !isGraphCompleted(AFTER_GRAPH)
) {
  throw new Error(
    "analysis.status.completed não consegue roteirizar uma execução que monta o mapa em STRUCTURAL_GRAPH"
  );
}

export const statusOverride = (
  report: AnalysisStatusReport
): StatusOverride => ({
  method: "analysis.status",
  request: {},
  result: report,
});

export const stageCompleted = (stage: AnalysisStage): unknown => {
  const row = COMPLETED.stages.find((candidate) => candidate.stage === stage);
  if (row === undefined) {
    throw new Error(`analysis.status.completed não tem a etapa ${stage}`);
  }
  return EVENTS["analysis.stage.completed"].parse({
    analysisId: COMPLETED.analysisId,
    counters: row.counters,
    durationMs: row.durationMs ?? 0,
    stage,
    status: row.status,
  });
};

export const analysisStarted = (): unknown =>
  EVENTS["analysis.started"].parse({ analysisId: COMPLETED.analysisId });

export const analysisCompleted = (): unknown =>
  EVENTS["analysis.completed"].parse({
    analysisId: COMPLETED.analysisId,
    completeness: COMPLETED.completeness,
  });

export const serveRun = async (
  page: Page,
  overrides: readonly TranscriptOverride[]
): Promise<void> => {
  await page.addInitScript((served) => {
    window.__STRATA_FAKE_TRANSCRIPTS__ = served;
    window.__STRATA_FAKE_LOG__ = [];
  }, overrides);
};

export const setTranscripts = async (
  page: Page,
  overrides: readonly TranscriptOverride[]
): Promise<void> => {
  await page.evaluate((served) => {
    window.__STRATA_FAKE_TRANSCRIPTS__ = served;
  }, overrides);
};

export const emit = async (
  page: Page,
  event: ProtocolEventName,
  payload: unknown
): Promise<void> => {
  const delivered = await page.evaluate(
    ([name, body]) => window.__STRATA_FAKE_EMIT__?.(name, body) ?? null,
    [event, payload] as const
  );
  expect(
    delivered,
    `window.__STRATA_FAKE_EMIT__("${event}") precisa chegar a exatamente um listener (null: falta o gancho de DEV)`
  ).toBe(1);
};

export interface LoggedRequest {
  readonly method: string;
  readonly payload: unknown;
}

export const requests = (page: Page): Promise<readonly LoggedRequest[]> =>
  page.evaluate(() => window.__STRATA_FAKE_LOG__ ?? []);

export const requestsTo = (
  log: readonly LoggedRequest[],
  method: string
): LoggedRequest[] => log.filter((entry) => entry.method === method);

export const countByMethod = (
  log: readonly LoggedRequest[]
): Record<string, number> => {
  const counts: Record<string, number> = {};
  for (const { method } of log) {
    counts[method] = (counts[method] ?? 0) + 1;
  }
  return counts;
};

export const countDelta = (
  before: readonly LoggedRequest[],
  after: readonly LoggedRequest[]
): Record<string, number> => {
  const counts = countByMethod(before);
  return Object.fromEntries(
    Object.entries(countByMethod(after))
      .map(
        ([method, count]) => [method, count - (counts[method] ?? 0)] as const
      )
      .filter(([, delta]) => delta !== 0)
  );
};

const mainNamed = (page: Page, name: string) =>
  page.getByRole("main", { name });

export const openFakeRepository = async (page: Page): Promise<void> => {
  await page.goto("/");
  const open = mainNamed(page, t.shell.regions.start).getByRole("button", {
    name: t.repository.open,
  });
  await expect(open).not.toHaveAttribute("aria-disabled", "true");
  await open.click();
  await expect(mainNamed(page, t.shell.regions.prepare)).toBeVisible();
};
