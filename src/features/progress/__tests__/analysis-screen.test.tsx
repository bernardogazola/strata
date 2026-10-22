import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import {
  HANDLE,
  renderRoutes,
} from "@/features/repository/__tests__/route-harness";
import { t } from "@/i18n/messages";
import type { ProtocolMethod } from "@/protocol/envelope";
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
const OTHER_ANALYSIS = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V2X";
const WORKSPACE_REGION = "Espaço de trabalho (dublê)";

type Answer = () => Promise<unknown>;

const answer = (answers: Partial<Record<ProtocolMethod, Answer>>) =>
  vi.spyOn(transport, "request").mockImplementation((method, payload) => {
    if (method === "repository.describe") {
      return fakeRequest(method, payload);
    }
    const reply = answers[method as ProtocolMethod];
    return reply === undefined
      ? Promise.reject(new Error(`sem resposta para ${method}`))
      : reply();
  });

const serveStatus = (report: AnalysisStatusReport) =>
  answer({ "analysis.status": () => Promise.resolve(report) });

function StubWorkspace({ header, report }: AnalysisWorkspaceProps) {
  return (
    <main aria-label={WORKSPACE_REGION}>
      <p>{header.repository?.displayName ?? "sem nome"}</p>
      {header.status}
      <p>{report.analysisId}</p>
    </main>
  );
}

const renderAnalysis = (arrivedByNavigation = false) =>
  renderRoutes(`/repository/${HANDLE}?analysis=${ANALYSIS_ID}`, {
    index: () => <p>index</p>,
    repository: (handle) => (
      <AnalysisScreen
        analysisId={ANALYSIS_ID}
        arrivedByNavigation={arrivedByNavigation}
        handle={handle}
        workspace={StubWorkspace}
      />
    ),
  });

const banner = () => within(screen.getByRole("banner"));

const running = (
  overrides: Parameters<typeof statusReport>[0] = {}
): AnalysisStatusReport =>
  statusReport({
    ...overrides,
    stages: {
      REPOSITORY_VALIDATION: { durationMs: 24, status: "COMPLETED" },
      SOURCE_ANALYSIS: { startedAt: STARTED_AT, status: "RUNNING" },
      ...overrides.stages,
    },
  });

const mapSkipped = (): AnalysisStatusReport =>
  statusReport({
    completeness: { totalFiles: 9 },
    stages: COMPLETED_STAGES,
    status: "COMPLETED",
  });

const graphCompleted = (): AnalysisStatusReport =>
  statusReport({
    completeness: { totalFiles: 9 },
    stages: {
      ...COMPLETED_STAGES,
      STRUCTURAL_GRAPH: {
        durationMs: 3,
        startedAt: STARTED_AT,
        status: "COMPLETED",
      },
    },
    status: "COMPLETED",
  });

const SA_TICK = {
  completed: 2,
  stage: "SOURCE_ANALYSIS",
  total: 40,
  unit: "JAVA_FILES",
} as const;

beforeEach(() => {
  useAppStore.setState({ ...initialStore, backend: READY }, true);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AnalysisScreen: a tela pelo relatório", () => {
  test("o grafo estrutural concluído entrega ao espaço de trabalho o cabeçalho e o relatório", async () => {
    serveStatus(graphCompleted());
    renderAnalysis();

    const workspace = await screen.findByRole("main", {
      name: WORKSPACE_REGION,
    });
    expect(await within(workspace).findByText("multi-module")).toBeDefined();
    expect(within(workspace).getByText(ANALYSIS_ID)).toBeDefined();
    expect(
      screen.queryByRole("main", { name: t.shell.regions.analysis })
    ).toBeNull();
  });

  test("o grafo pulado nunca monta o espaço de trabalho", async () => {
    serveStatus(mapSkipped());
    renderAnalysis();

    expect(
      await screen.findByRole("heading", { name: t.analysis.map.title })
    ).toBeDefined();
    expect(screen.queryByRole("main", { name: WORKSPACE_REGION })).toBeNull();
  });

  test("FAILED: o motivo e o selo, e nenhum cartão do mapa", async () => {
    serveStatus(
      statusReport({
        error: { code: "REPO_IS_BARE", origin: "analyzer", params: {} },
        stages: {
          REPOSITORY_VALIDATION: {
            durationMs: 2,
            startedAt: STARTED_AT,
            status: "FAILED",
          },
        },
        status: "FAILED",
      })
    );
    renderAnalysis();

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: t.analysis.failed.headline,
      })
    ).toBeDefined();
    expect(banner().getByText(t.header.analysis.FAILED)).toBeDefined();
    expect(
      screen.queryByRole("heading", { name: t.analysis.map.title })
    ).toBeNull();
  });
});

describe("AnalysisScreen: o título da execução", () => {
  test("sem as raízes da descoberta do layout, o título neutro", async () => {
    serveStatus(running({ completeness: { totalFiles: 9 } }));
    renderAnalysis();

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: t.header.analysis.RUNNING,
      })
    ).toBeDefined();
  });

  test("com a completude ainda zero, os arquivos vêm do total do tick da análise do código", async () => {
    useAppStore.setState({
      progress: { SOURCE_ANALYSIS: SA_TICK },
      runningAnalysisId: ANALYSIS_ID,
    });
    serveStatus(
      running({
        stages: {
          LAYOUT_DISCOVERY: {
            counters: { selectedRoots: 2 },
            durationMs: 2,
            status: "COMPLETED",
          },
        },
      })
    );
    renderAnalysis();

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Analisando 40 arquivos Java em 2 raízes",
      })
    ).toBeDefined();
  });

  test("os ticks de outra execução não entram: o título neutro", async () => {
    useAppStore.setState({
      progress: { SOURCE_ANALYSIS: SA_TICK },
      runningAnalysisId: OTHER_ANALYSIS,
    });
    serveStatus(
      running({
        stages: {
          LAYOUT_DISCOVERY: {
            counters: { selectedRoots: 2 },
            durationMs: 2,
            status: "COMPLETED",
          },
        },
      })
    );
    renderAnalysis();

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: t.header.analysis.RUNNING,
      })
    ).toBeDefined();
  });
});

describe("AnalysisScreen: o foco quando a tela troca", () => {
  test("na carga da página, o relatório que chega não move o foco", async () => {
    serveStatus(mapSkipped());
    renderAnalysis(false);

    await screen.findByRole("heading", { name: t.analysis.map.title });

    expect(document.activeElement).toBe(document.body);
  });

  test("chegando por navegação, o título recebe o foco que caiu no <body>", async () => {
    serveStatus(mapSkipped());
    renderAnalysis(true);

    const title = await screen.findByRole("heading", {
      level: 1,
      name: "9 arquivos Java analisados",
    });

    await waitFor(() => {
      expect(document.activeElement).toBe(title);
    });
  });

  test("o foco em um controle do cabeçalho fica nele quando o relatório chega", async () => {
    let deliver: (report: AnalysisStatusReport) => void = () => undefined;
    answer({
      "analysis.status": () =>
        new Promise((resolve) => {
          deliver = resolve;
        }),
    });
    renderAnalysis(true);
    const close = await screen.findByRole("button", {
      name: t.header.close,
    });
    close.focus();

    deliver(mapSkipped());
    await screen.findByRole("heading", { name: t.analysis.map.title });

    expect(document.activeElement).toBe(close);
  });

  test("Analisar de novo, da análise que sumiu, volta à preparação", async () => {
    answer({
      "analysis.status": () =>
        Promise.reject({
          code: "ANALYSIS_NOT_FOUND",
          origin: "analyzer",
          params: {},
        }),
    });
    const { router } = renderAnalysis();

    fireEvent.click(
      await screen.findByRole("button", { name: t.analysis.again })
    );

    await waitFor(() => {
      expect(router.state.location.search).not.toHaveProperty("analysis");
    });
  });
});
