import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import {
  AnalysisSummary,
  MapUnavailableCard,
} from "@/features/progress/analysis-summary";
import { t } from "@/i18n/messages";
import type { AnalysisWarning } from "@/state/app-store";
import {
  ANALYSIS_ID,
  COMPLETED_STAGES,
  STARTED_AT,
  type StageOverrides,
  statusReport,
} from "./status-report";

const OTHER_ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1X";
const STAGES_TOGGLE = /^Etapas da análise/;
const SAMPLE_LINE = /^Mostrando/;

const PARTIAL = {
  shallowClone: true,
  skippedFiles: 3,
  totalFiles: 259,
} as const;

const parseFailed = (
  filePath: string,
  analysisId: string = ANALYSIS_ID
): AnalysisWarning => ({
  analysisId,
  error: { code: "SOURCE_PARSE_FAILED", params: { filePath } },
});

const renderSummary = ({
  children,
  onAnalyzeAgain = vi.fn(),
  report,
  warnings = [],
}: {
  children?: React.ReactNode;
  onAnalyzeAgain?: () => void;
  report: Parameters<typeof AnalysisSummary>[0]["report"];
  warnings?: readonly AnalysisWarning[];
}) =>
  render(
    <AnalysisSummary
      onAnalyzeAgain={onAnalyzeAgain}
      report={report}
      warnings={warnings}
    >
      {children}
    </AnalysisSummary>
  );

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AnalysisSummary, o título", () => {
  test("concluída e completa: o total, no plural, e quanto levou embaixo", () => {
    renderSummary({
      report: statusReport({
        completeness: { totalFiles: 9 },
        stages: COMPLETED_STAGES,
        status: "COMPLETED",
      }),
    });

    const heading = screen.getByRole("heading", { level: 1 });
    expect(heading).toHaveTextContent("9 arquivos Java analisados");
    expect(heading).toHaveAttribute("tabindex", "-1");
    expect(screen.getByText("Análise concluída em 335 ms")).toBeDefined();
    expect(screen.queryByText(t.analysis.partial.shallowClone)).toBeNull();
  });

  test("um arquivo só conjuga no singular", () => {
    renderSummary({
      report: statusReport({
        completeness: { totalFiles: 1 },
        stages: COMPLETED_STAGES,
        status: "COMPLETED",
      }),
    });

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: "1 arquivo Java analisado",
      })
    ).toBeDefined();
  });

  test("concluída e parcial: analisados de total, e a ressalva", () => {
    renderSummary({
      report: statusReport({
        completeness: PARTIAL,
        stages: COMPLETED_STAGES,
        status: "COMPLETED",
      }),
    });

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: "256 de 259 arquivos Java analisados",
      })
    ).toBeDefined();
    expect(
      screen.getByText("Análise parcial: 3 de 259 arquivos ficaram de fora.")
    ).toBeDefined();
  });

  test("cancelada: o título dela, há quanto tempo, e nenhuma ressalva", () => {
    renderSummary({
      report: statusReport({
        completeness: { shallowClone: true, totalFiles: 527 },
        stages: {
          ...COMPLETED_STAGES,
          SOURCE_ANALYSIS: {
            durationMs: 2073,
            startedAt: STARTED_AT,
            status: "CANCELLED",
          },
        },
        status: "CANCELLED",
      }),
    });

    expect(
      screen.getByRole("heading", { level: 1, name: "Análise cancelada" })
    ).toBeDefined();
    expect(screen.getByText("Cancelada depois de 2,1 s")).toBeDefined();
    expect(screen.queryByText(t.analysis.partial.shallowClone)).toBeNull();
  });

  test("falhou: o título dela e o motivo em um h2, com a frase e o código do erro", () => {
    renderSummary({
      report: statusReport({
        error: { code: "REPO_IS_BARE" },
        stages: {
          REPOSITORY_VALIDATION: {
            durationMs: 3,
            startedAt: STARTED_AT,
            status: "FAILED",
          },
        },
        status: "FAILED",
      }),
    });

    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    expect(
      screen.getByRole("heading", { level: 1, name: "A análise não terminou" })
    ).toBeDefined();
    expect(
      screen.getByRole("heading", {
        level: 2,
        name: "Repositório sem árvore de trabalho",
      })
    ).toBeDefined();
    expect(screen.getByText("Código: REPO_IS_BARE")).toBeDefined();
  });

  test("'Analisar de novo' chama quem volta à preparação", () => {
    const onAnalyzeAgain = vi.fn();
    renderSummary({
      onAnalyzeAgain,
      report: statusReport({ stages: COMPLETED_STAGES, status: "COMPLETED" }),
    });

    fireEvent.click(screen.getByRole("button", { name: t.analysis.again }));
    expect(onAnalyzeAgain).toHaveBeenCalledOnce();
  });

  test("o mapa, ou o cartão dele, entra entre a ressalva e os avisos", () => {
    renderSummary({
      children: <p>o mapa</p>,
      report: statusReport({
        completeness: PARTIAL,
        stages: COMPLETED_STAGES,
        status: "COMPLETED",
      }),
    });

    const text = document.body.textContent ?? "";
    const caveat = text.indexOf(t.analysis.partial.shallowClone);
    const map = text.indexOf("o mapa");
    const warnings = text.indexOf("3 arquivos ficaram de fora");
    expect(caveat).toBeGreaterThan(-1);
    expect(map).toBeGreaterThan(caveat);
    expect(warnings).toBeGreaterThan(map);
  });
});

describe("AnalysisSummary, os avisos", () => {
  const partial = statusReport({
    completeness: PARTIAL,
    stages: COMPLETED_STAGES,
    status: "COMPLETED",
  });

  test("o título conta o que o Java diz; a lista é só desta análise", () => {
    renderSummary({
      report: partial,
      warnings: [
        parseFailed("src/A.java"),
        parseFailed("src/Outra.java", OTHER_ANALYSIS_ID),
        parseFailed("src/B.java"),
        parseFailed("src/C.java"),
      ],
    });

    const section = screen.getByRole("region", {
      name: "3 arquivos ficaram de fora",
    });
    expect(within(section).getByText(t.analysis.warnings.label)).toBeDefined();
    const items = within(section).getAllByRole("listitem");
    expect(items).toHaveLength(3);
    expect(section).not.toHaveTextContent("Outra.java");
    expect(within(section).queryByRole("alert")).toBeNull();
    expect(within(section).queryByText(SAMPLE_LINE)).toBeNull();
  });

  test("a amostra menor que a contagem diz quanto mostra", () => {
    renderSummary({ report: partial, warnings: [parseFailed("src/A.java")] });

    expect(screen.getByText("Mostrando 1 de 3 avisos.")).toBeDefined();
  });

  test("depois de recarregar, sem amostra: o título e a frase, sem lista vazia", () => {
    renderSummary({ report: partial, warnings: [] });

    const section = screen.getByRole("region", {
      name: "3 arquivos ficaram de fora",
    });
    expect(within(section).queryByRole("list")).toBeNull();
    expect(within(section).getByText("Mostrando 0 de 3 avisos.")).toBeDefined();
  });

  test("um arquivo só: o título no singular", () => {
    renderSummary({
      report: statusReport({
        completeness: { skippedFiles: 1, totalFiles: 9 },
        status: "COMPLETED",
      }),
      warnings: [parseFailed("src/A.java")],
    });

    expect(
      screen.getByRole("region", { name: "1 arquivo ficou de fora" })
    ).toBeDefined();
  });

  test("sem aviso e sem arquivo de fora, nenhuma seção", () => {
    renderSummary({
      report: statusReport({
        completeness: { totalFiles: 9 },
        status: "COMPLETED",
      }),
    });

    expect(screen.queryByRole("region")).toBeNull();
  });

  test("em uma análise cancelada o título é o rótulo, sem contagem", () => {
    renderSummary({
      report: statusReport({
        completeness: { skippedFiles: 0, totalFiles: 527 },
        status: "CANCELLED",
      }),
      warnings: [parseFailed("src/A.java")],
    });

    expect(
      screen.getByRole("region", { name: t.analysis.warnings.label })
    ).toBeDefined();
    expect(screen.queryByText(SAMPLE_LINE)).toBeNull();
  });

  test("o mesmo aviso duas vezes renderiza as duas, sem chave duplicada", () => {
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    renderSummary({
      report: partial,
      warnings: [parseFailed("src/A.java"), parseFailed("src/A.java")],
    });

    expect(screen.getAllByText("src/A.java")).toHaveLength(2);
    expect(consoleError).not.toHaveBeenCalled();
  });
});

describe("AnalysisSummary, as etapas", () => {
  test("recolhidas, com a contagem; abertas, as dez linhas fechadas", () => {
    renderSummary({
      report: statusReport({ stages: COMPLETED_STAGES, status: "COMPLETED" }),
    });

    const toggle = screen.getByRole("button", { name: STAGES_TOGGLE });
    expect(toggle).toHaveTextContent("4 concluídas, 6 puladas");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(
      screen.queryByRole("list", { name: t.analysis.stages.toggle })
    ).toBeNull();

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute("aria-expanded", "true");
    const list = screen.getByRole("list", { name: t.analysis.stages.toggle });
    expect(within(list).getAllByRole("listitem")).toHaveLength(10);
  });

  test("uma falha na validação: nenhuma concluída, nenhuma pulada, o resto não executado", () => {
    const stages: StageOverrides = {
      REPOSITORY_VALIDATION: {
        durationMs: 3,
        startedAt: STARTED_AT,
        status: "FAILED",
      },
    };
    renderSummary({
      report: statusReport({
        error: { code: "REPO_IS_BARE" },
        stages,
        status: "FAILED",
      }),
    });

    const toggle = screen.getByRole("button", { name: STAGES_TOGGLE });
    expect(toggle).toHaveTextContent("nenhuma concluída, nenhuma pulada");
    fireEvent.click(toggle);
    const list = screen.getByRole("list", { name: t.analysis.stages.toggle });
    expect(within(list).getAllByText(t.analysis.stage.notRun)).toHaveLength(9);
  });
});

describe("MapUnavailableCard", () => {
  const withGraph = (stages: StageOverrides) =>
    statusReport({
      stages: { ...COMPLETED_STAGES, ...stages },
      status: "COMPLETED",
    });

  test("grafo pulado: o que já foi extraído, com as contagens da análise do código", () => {
    render(
      <MapUnavailableCard
        report={withGraph({
          SOURCE_ANALYSIS: {
            counters: { files: 256, types: 412 },
            durationMs: 4500,
            startedAt: STARTED_AT,
            status: "COMPLETED",
          },
        })}
      />
    );

    expect(
      screen.getByRole("heading", { level: 2, name: t.analysis.map.title })
    ).toBeDefined();
    expect(
      screen.getByText(
        "A estrutura do código já foi extraída e salva: 256 arquivos e 412 tipos. O mapa aparece aqui quando a etapa do grafo estrutural passar a ser executada."
      )
    ).toBeDefined();
  });

  test("as contagens concordam no singular e dizem 'nenhum' no zero", () => {
    render(
      <MapUnavailableCard
        report={withGraph({
          SOURCE_ANALYSIS: {
            counters: { files: 1, types: 0 },
            durationMs: 10,
            startedAt: STARTED_AT,
            status: "COMPLETED",
          },
        })}
      />
    );

    expect(screen.getByRole("region")).toHaveTextContent(
      "salva: 1 arquivo e nenhum tipo."
    );
  });

  test("sem as contagens, a frase sem números", () => {
    render(
      <MapUnavailableCard
        report={withGraph({
          SOURCE_ANALYSIS: {
            counters: {},
            durationMs: 10,
            startedAt: STARTED_AT,
            status: "COMPLETED",
          },
        })}
      />
    );

    expect(screen.getByText(t.analysis.map.bodyNoCounts)).toBeDefined();
  });

  test.each(["PENDING", "RUNNING"] as const)(
    "grafo %s: o mapa aparece quando a etapa terminar",
    (status) => {
      render(
        <MapUnavailableCard
          report={statusReport({
            stages: {
              SOURCE_ANALYSIS: COMPLETED_STAGES.SOURCE_ANALYSIS,
              STRUCTURAL_GRAPH: { startedAt: STARTED_AT, status },
            },
          })}
        />
      );

      expect(screen.getByText(t.analysis.map.pending)).toBeDefined();
    }
  );
});
