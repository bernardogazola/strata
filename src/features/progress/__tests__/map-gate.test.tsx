import { screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import {
  HANDLE,
  renderRoutes,
} from "@/features/repository/__tests__/route-harness";
import { t } from "@/i18n/messages";
import type { AnalysisStatusReport } from "@/protocol/repository";
import { type BackendState, useAppStore } from "@/state/app-store";
import {
  AnalysisScreen,
  type AnalysisWorkspaceProps,
} from "../analysis-screen";
import {
  ANALYSIS_ID,
  COMPLETED_STAGES,
  STARTED_AT,
  statusReport,
} from "./status-report";

const READY: BackendState = { phase: "READY", protocolVersion: 1 };
const initialStore = useAppStore.getState();
const WORKSPACE_REGION = "Espaço de trabalho (dublê)";

const serveStatus = (report: AnalysisStatusReport) =>
  vi
    .spyOn(transport, "request")
    .mockImplementation((method, payload) =>
      method === "analysis.status"
        ? Promise.resolve(report)
        : fakeRequest(method, payload)
    );

function StubWorkspace({ report }: AnalysisWorkspaceProps) {
  return (
    <main aria-label={WORKSPACE_REGION}>
      <p>{report.status}</p>
    </main>
  );
}

const renderAnalysis = () =>
  renderRoutes(`/repository/${HANDLE}?analysis=${ANALYSIS_ID}`, {
    index: () => <p>index</p>,
    repository: (handle) => (
      <AnalysisScreen
        analysisId={ANALYSIS_ID}
        arrivedByNavigation={false}
        handle={handle}
        workspace={StubWorkspace}
      />
    ),
  });

const GRAPH_DONE = {
  ...COMPLETED_STAGES,
  STRUCTURAL_GRAPH: {
    counters: { packageNodes: 4, typeNodes: 9 },
    durationMs: 3,
    startedAt: STARTED_AT,
    status: "COMPLETED",
  },
} as const;

const cancelledAfterGraph = (): AnalysisStatusReport =>
  statusReport({
    completeness: { totalFiles: 9 },
    stages: {
      ...GRAPH_DONE,
      PERSISTENCE: { startedAt: STARTED_AT, status: "CANCELLED" },
    },
    status: "CANCELLED",
  });

const failedAfterGraph = (): AnalysisStatusReport =>
  statusReport({
    completeness: { totalFiles: 9 },
    error: { code: "INTERNAL_UNEXPECTED", origin: "analyzer", params: {} },
    stages: {
      ...GRAPH_DONE,
      PERSISTENCE: { durationMs: 1, startedAt: STARTED_AT, status: "FAILED" },
    },
    status: "FAILED",
  });

const runningAfterGraph = (): AnalysisStatusReport =>
  statusReport({
    completeness: { totalFiles: 9 },
    stages: {
      ...GRAPH_DONE,
      PERSISTENCE: { startedAt: STARTED_AT, status: "RUNNING" },
    },
    status: "RUNNING",
  });

const completedWithGraph = (): AnalysisStatusReport =>
  statusReport({
    completeness: { totalFiles: 9 },
    stages: GRAPH_DONE,
    status: "COMPLETED",
  });

beforeEach(() => {
  useAppStore.setState({ ...initialStore, backend: READY }, true);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AnalysisScreen: o mapa só monta em uma execução legível", () => {
  test("CANCELLED com o grafo estrutural concluído: o resumo da cancelada, e nunca o mapa", async () => {
    serveStatus(cancelledAfterGraph());
    renderAnalysis();

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: t.analysis.cancelled.headline,
      })
    ).toBeDefined();
    expect(screen.queryByRole("main", { name: WORKSPACE_REGION })).toBeNull();
    expect(
      screen.getByRole("main", { name: t.shell.regions.analysis })
    ).toBeDefined();
  });

  test("FAILED com o grafo estrutural concluído: o resumo da que falhou, e nunca o mapa", async () => {
    serveStatus(failedAfterGraph());
    renderAnalysis();

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: t.analysis.failed.headline,
      })
    ).toBeDefined();
    expect(screen.queryByRole("main", { name: WORKSPACE_REGION })).toBeNull();
    expect(
      screen.getByRole("main", { name: t.shell.regions.analysis })
    ).toBeDefined();
  });

  test("RUNNING com o grafo estrutural concluído: o mapa monta no meio da execução", async () => {
    serveStatus(runningAfterGraph());
    renderAnalysis();

    const workspace = await screen.findByRole("main", {
      name: WORKSPACE_REGION,
    });
    expect(workspace.textContent).toBe("RUNNING");
  });

  test("COMPLETED com o grafo estrutural concluído: o mapa", async () => {
    serveStatus(completedWithGraph());
    renderAnalysis();

    const workspace = await screen.findByRole("main", {
      name: WORKSPACE_REGION,
    });
    expect(workspace.textContent).toBe("COMPLETED");
  });
});
