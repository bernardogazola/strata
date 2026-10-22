import { act, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { StageProgress } from "@/features/progress/stage-progress";
import { analysisStageLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { analysisStageSchema, type ProgressTick } from "@/protocol/analysis";
import type { AnalysisStatusReport } from "@/protocol/repository";
import { COMPLETED_STAGES, STARTED_AT, statusReport } from "./status-report";

const STARTED_MS = Date.parse(STARTED_AT);
const PERCENT = /%/;

const tick = (
  stage: ProgressTick["stage"],
  completed: number,
  total: number | null,
  unit: ProgressTick["unit"] = "JAVA_FILES"
): ProgressTick => ({ completed, stage, total, unit });

const rowOf = (container: HTMLElement, stage: string): HTMLElement => {
  const row = container.querySelector<HTMLElement>(`li[data-stage="${stage}"]`);
  if (row === null) {
    throw new Error(`nenhuma linha para ${stage}`);
  }
  return row;
};

const liveRegion = (container: HTMLElement): HTMLElement => {
  const region = container.querySelector<HTMLElement>('[aria-live="polite"]');
  if (region === null) {
    throw new Error("sem região viva");
  }
  return region;
};

const watch = (element: HTMLElement) => {
  const records: MutationRecord[] = [];
  const observer = new MutationObserver((batch) => records.push(...batch));
  observer.observe(element, {
    characterData: true,
    childList: true,
    subtree: true,
  });
  return {
    count: () => {
      records.push(...observer.takeRecords());
      return records.length;
    },
  };
};

const RUNNING_PAIR = statusReport({
  stages: {
    GIT_HISTORY_CHEAP: { startedAt: STARTED_AT, status: "RUNNING" },
    LAYOUT_DISCOVERY: COMPLETED_STAGES.LAYOUT_DISCOVERY,
    REPOSITORY_VALIDATION: COMPLETED_STAGES.REPOSITORY_VALIDATION,
    SOURCE_ANALYSIS: { startedAt: STARTED_AT, status: "RUNNING" },
  },
});

afterEach(() => {
  vi.useRealTimers();
});

describe("StageProgress, as linhas", () => {
  test("dez linhas, na ordem do enum, com a chave na etapa", () => {
    const { container } = render(
      <StageProgress now={STARTED_MS} report={statusReport()} ticks={{}} />
    );

    const list = screen.getByRole("list", { name: t.analysis.stages.toggle });
    const stages = within(list)
      .getAllByRole("listitem")
      .map((row) => row.dataset.stage);
    expect(stages).toEqual(analysisStageSchema.options);
    expect(container.querySelectorAll("li")).toHaveLength(10);
  });

  test("duas etapas concorrentes: duas linhas que não piscam quando os ticks alternam", () => {
    const now = STARTED_MS + 2000;
    const { container, rerender } = render(
      <StageProgress
        now={now}
        report={RUNNING_PAIR}
        ticks={{
          GIT_HISTORY_CHEAP: tick("GIT_HISTORY_CHEAP", 0, null, "COMMITS"),
          SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 0, 9),
        }}
      />
    );
    const sourceRow = rowOf(container, "SOURCE_ANALYSIS");
    const historyRow = rowOf(container, "GIT_HISTORY_CHEAP");
    const order = () =>
      [...container.querySelectorAll("li")].map((row) => row.dataset.stage);
    const initialOrder = order();

    const sequence = [
      { GIT_HISTORY_CHEAP: 120, SOURCE_ANALYSIS: 2 },
      { GIT_HISTORY_CHEAP: 120, SOURCE_ANALYSIS: 5 },
      { GIT_HISTORY_CHEAP: 480, SOURCE_ANALYSIS: 5 },
      { GIT_HISTORY_CHEAP: 480, SOURCE_ANALYSIS: 9 },
    ];
    for (const step of sequence) {
      rerender(
        <StageProgress
          now={now}
          report={RUNNING_PAIR}
          ticks={{
            GIT_HISTORY_CHEAP: tick(
              "GIT_HISTORY_CHEAP",
              step.GIT_HISTORY_CHEAP,
              null,
              "COMMITS"
            ),
            SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", step.SOURCE_ANALYSIS, 9),
          }}
        />
      );
      expect(rowOf(container, "SOURCE_ANALYSIS")).toBe(sourceRow);
      expect(rowOf(container, "GIT_HISTORY_CHEAP")).toBe(historyRow);
      expect(order()).toEqual(initialOrder);
      expect(sourceRow).toHaveTextContent(
        `${step.SOURCE_ANALYSIS} de 9 arquivos Java`
      );
      expect(historyRow).toHaveTextContent(
        `${step.GIT_HISTORY_CHEAP} commits encontrados`
      );
    }
  });

  test("só o rótulo de cada etapa que corre brilha, e o brilho fica no texto, não na linha", () => {
    const { container } = render(
      <StageProgress now={STARTED_MS} report={RUNNING_PAIR} ticks={{}} />
    );

    const shimmering = [...container.querySelectorAll(".text-shimmer")];
    const running = analysisStageSchema.options.filter(
      (stage) => stage === "GIT_HISTORY_CHEAP" || stage === "SOURCE_ANALYSIS"
    );
    expect(shimmering.map((label) => label.textContent)).toEqual(
      running.map((stage) => analysisStageLabels[stage])
    );
    for (const label of shimmering) {
      expect(label.tagName).toBe("SPAN");
      expect(label.classList.contains("grow")).toBe(false);
    }
    expect(
      rowOf(container, "LAYOUT_DISCOVERY").querySelector(".text-shimmer")
    ).toBeNull();
  });

  test("as unidades saem com ponto de milhar: 1.240 de 3.902 arquivos Java", () => {
    const { container } = render(
      <StageProgress
        now={STARTED_MS}
        report={RUNNING_PAIR}
        ticks={{ SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 1240, 3902) }}
      />
    );

    expect(
      within(rowOf(container, "SOURCE_ANALYSIS")).getByText(
        "1.240 de 3.902 arquivos Java"
      )
    ).toBeDefined();
  });

  test("nenhuma porcentagem, nem no texto nem na acessibilidade da barra", () => {
    const { container } = render(
      <StageProgress
        now={STARTED_MS}
        report={RUNNING_PAIR}
        ticks={{ SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 1240, 3902) }}
      />
    );

    expect(container.textContent).not.toMatch(PERCENT);
    expect(screen.queryByRole("progressbar")).toBeNull();
    expect(container.querySelector("[aria-valuenow]")).toBeNull();
    const bar = rowOf(container, "SOURCE_ANALYSIS").querySelector<HTMLElement>(
      '[style*="scaleX"]'
    );
    expect(bar?.style.transform).toBe(`scaleX(${1240 / 3902})`);
  });

  test("total nulo vira contagem encontrada, sem barra", () => {
    const report = statusReport({
      stages: {
        REPOSITORY_VALIDATION: { startedAt: STARTED_AT, status: "RUNNING" },
      },
    });
    const { container } = render(
      <StageProgress
        now={STARTED_MS}
        report={report}
        ticks={{
          REPOSITORY_VALIDATION: tick("REPOSITORY_VALIDATION", 1024, null),
        }}
      />
    );

    const row = rowOf(container, "REPOSITORY_VALIDATION");
    expect(row).toHaveTextContent("1.024 arquivos Java encontrados");
    expect(row.querySelector('[style*="scaleX"]')).toBeNull();
  });

  test("a validação nunca diz 'de M', nem com o total no tick terminal", () => {
    const report = statusReport({
      stages: {
        REPOSITORY_VALIDATION: { startedAt: STARTED_AT, status: "RUNNING" },
      },
    });
    const { container } = render(
      <StageProgress
        now={STARTED_MS}
        report={report}
        ticks={{
          REPOSITORY_VALIDATION: tick("REPOSITORY_VALIDATION", 527, 527),
        }}
      />
    );

    const row = rowOf(container, "REPOSITORY_VALIDATION");
    expect(row).toHaveTextContent("527 arquivos Java encontrados");
    expect(row).not.toHaveTextContent("de 527");
  });

  test("uma etapa terminal vence o tick que ficou na store", () => {
    const report = statusReport({
      stages: { REPOSITORY_VALIDATION: COMPLETED_STAGES.REPOSITORY_VALIDATION },
    });
    const { container } = render(
      <StageProgress
        now={STARTED_MS}
        report={report}
        ticks={{ REPOSITORY_VALIDATION: tick("REPOSITORY_VALIDATION", 10, 10) }}
      />
    );

    const row = rowOf(container, "REPOSITORY_VALIDATION");
    expect(row).toHaveTextContent("10 arquivos Java encontrados em 24 ms");
    expect(row.querySelector('[style*="scaleX"]')).toBeNull();
    expect(
      row.querySelector(".animate-spin, .motion-safe\\:animate-spin")
    ).toBeNull();
  });

  test("PENDING com tick é a etapa que já corre e o status ainda não viu", () => {
    const report = statusReport({
      stages: { REPOSITORY_VALIDATION: COMPLETED_STAGES.REPOSITORY_VALIDATION },
    });
    const { container } = render(
      <StageProgress
        now={STARTED_MS}
        report={report}
        ticks={{ SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 0, 9) }}
      />
    );

    expect(rowOf(container, "SOURCE_ANALYSIS")).toHaveTextContent(
      "0 de 9 arquivos Java"
    );
  });

  test("sem relatório ainda, todas pendentes, e o tick já ativa a sua linha", () => {
    const { container } = render(
      <StageProgress
        now={STARTED_MS}
        report={undefined}
        ticks={{
          REPOSITORY_VALIDATION: tick("REPOSITORY_VALIDATION", 0, null),
        }}
      />
    );

    expect(rowOf(container, "REPOSITORY_VALIDATION")).toHaveTextContent(
      "0 arquivos Java encontrados"
    );
    expect(rowOf(container, "PERSISTENCE")).toHaveTextContent("pendente");
  });
});

describe("StageProgress, com o analisador parado no meio da execução", () => {
  test("a etapa que corria falhou, as que não começaram não vão começar, e nada gira", () => {
    const { container } = render(
      <StageProgress
        interrupted
        now={STARTED_MS}
        report={RUNNING_PAIR}
        ticks={{
          SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 4, 9),
          STRUCTURAL_GRAPH: tick("STRUCTURAL_GRAPH", 1, 9, "TYPES"),
        }}
      />
    );

    for (const stage of [
      "SOURCE_ANALYSIS",
      "GIT_HISTORY_CHEAP",
      "STRUCTURAL_GRAPH",
    ]) {
      expect(rowOf(container, stage)).toHaveTextContent("falhou");
    }
    expect(rowOf(container, "HOTSPOT_PROFILES")).toHaveTextContent(
      t.analysis.stage.notRun
    );
    expect(rowOf(container, "LAYOUT_DISCOVERY")).not.toHaveTextContent(
      "falhou"
    );
    expect(container.querySelector('[class*="animate-spin"]')).toBeNull();
    expect(container.querySelector(".text-shimmer")).toBeNull();
  });
});

describe("StageProgress, depois do terminal", () => {
  test("PENDING depois de CANCELLED lê 'não executada', mesmo com tick na store", () => {
    const report = statusReport({
      stages: {
        GIT_HISTORY_CHEAP: COMPLETED_STAGES.GIT_HISTORY_CHEAP,
        LAYOUT_DISCOVERY: COMPLETED_STAGES.LAYOUT_DISCOVERY,
        REPOSITORY_VALIDATION: COMPLETED_STAGES.REPOSITORY_VALIDATION,
        SOURCE_ANALYSIS: {
          durationMs: 2073,
          startedAt: STARTED_AT,
          status: "CANCELLED",
        },
      },
      status: "CANCELLED",
    });
    const { container } = render(
      <StageProgress
        now={STARTED_MS}
        report={report}
        ticks={{ STRUCTURAL_GRAPH: tick("STRUCTURAL_GRAPH", 3, 9, "TYPES") }}
      />
    );

    expect(rowOf(container, "SOURCE_ANALYSIS")).toHaveTextContent("cancelada");
    const neverRan = [
      "STRUCTURAL_GRAPH",
      "GIT_HISTORY_DETAILED",
      "EVOLUTIONARY_COUPLING",
      "HOTSPOT_PROFILES",
      "GUIDED_TOUR",
      "PERSISTENCE",
    ];
    for (const stage of neverRan) {
      expect(rowOf(container, stage)).toHaveTextContent(
        t.analysis.stage.notRun
      );
    }
    expect(container.textContent).not.toContain("pendente");
    expect(container.textContent).not.toContain("3 de 9");
  });

  test("PENDING depois de COMPLETED também lê 'não executada'", () => {
    const report = statusReport({
      stages: {
        ...COMPLETED_STAGES,
        GUIDED_TOUR: { status: "PENDING" },
      },
      status: "COMPLETED",
    });
    const { container } = render(
      <StageProgress now={STARTED_MS} report={report} ticks={{}} />
    );

    expect(rowOf(container, "GUIDED_TOUR")).toHaveTextContent("não executada");
  });

  test("cada etapa concluída diz o seu resumo e a duração em um elemento à parte", () => {
    const report = statusReport({
      stages: COMPLETED_STAGES,
      status: "COMPLETED",
    });
    const { container } = render(
      <StageProgress now={STARTED_MS} report={report} ticks={{}} />
    );

    const validation = rowOf(container, "REPOSITORY_VALIDATION");
    expect(
      within(validation).getByText("10 arquivos Java encontrados")
    ).toBeDefined();
    expect(within(validation).getByText("em 24 ms")).toBeDefined();
    const layout = rowOf(container, "LAYOUT_DISCOVERY");
    expect(within(layout).getByText("3 raízes escolhidas")).toBeDefined();
    expect(within(layout).getByText("em 2 ms")).toBeDefined();
    const source = rowOf(container, "SOURCE_ANALYSIS");
    expect(
      within(source).getByText("12 tipos extraídos de 9 arquivos Java")
    ).toBeDefined();
    expect(within(source).getByText("em 305 ms")).toBeDefined();
    const persistence = rowOf(container, "PERSISTENCE");
    expect(within(persistence).getByText("concluída")).toBeDefined();
    expect(within(persistence).getByText("em 4 ms")).toBeDefined();
    expect(rowOf(container, "STRUCTURAL_GRAPH")).toHaveTextContent("pulada");
  });

  test.each([
    { expected: "nenhum tipo extraído de 1 arquivo Java", files: 1, types: 0 },
    { expected: "1 tipo extraído de 1 arquivo Java", files: 1, types: 1 },
    {
      expected: "1.240 tipos extraídos de 3.902 arquivos Java",
      files: 3902,
      types: 1240,
    },
  ])(
    "a análise do código concluída diz '$expected'",
    ({ expected, files, types }) => {
      const report = statusReport({
        stages: {
          SOURCE_ANALYSIS: {
            counters: { files, types },
            durationMs: 305,
            startedAt: STARTED_AT,
            status: "COMPLETED",
          },
        },
        status: "COMPLETED",
      });
      const { container } = render(
        <StageProgress now={STARTED_MS} report={report} ticks={{}} />
      );

      expect(
        within(rowOf(container, "SOURCE_ANALYSIS")).getByText(expected)
      ).toBeDefined();
    }
  );

  test("a análise do código sem os contadores diz só que concluiu", () => {
    const report = statusReport({
      stages: {
        SOURCE_ANALYSIS: {
          counters: { files: 9 },
          durationMs: 305,
          startedAt: STARTED_AT,
          status: "COMPLETED",
        },
      },
      status: "COMPLETED",
    });
    const { container } = render(
      <StageProgress now={STARTED_MS} report={report} ticks={{}} />
    );

    expect(
      within(rowOf(container, "SOURCE_ANALYSIS")).getByText("concluída")
    ).toBeDefined();
  });

  test("a nota das puladas aparece só quando há etapa pulada", () => {
    const skipped = render(
      <StageProgress
        now={STARTED_MS}
        report={statusReport({ stages: COMPLETED_STAGES, status: "COMPLETED" })}
        ticks={{}}
      />
    );
    expect(skipped.getByText(t.analysis.stage.skippedNote)).toBeDefined();
    skipped.unmount();

    render(
      <StageProgress now={STARTED_MS} report={statusReport()} ticks={{}} />
    );
    expect(screen.queryByText(t.analysis.stage.skippedNote)).toBeNull();
  });

  test("uma etapa que falhou diz 'falhou'", () => {
    const report = statusReport({
      error: { code: "REPO_IS_BARE" },
      stages: {
        REPOSITORY_VALIDATION: {
          durationMs: 3,
          startedAt: STARTED_AT,
          status: "FAILED",
        },
      },
      status: "FAILED",
    });
    const { container } = render(
      <StageProgress now={STARTED_MS} report={report} ticks={{}} />
    );

    expect(rowOf(container, "REPOSITORY_VALIDATION")).toHaveTextContent(
      "falhou"
    );
  });
});

describe("StageProgress, o tempo decorrido", () => {
  test("a coluna mostra há quanto tempo a etapa corre, desde o startedAt dela", () => {
    const { container } = render(
      <StageProgress
        now={STARTED_MS + 12_400}
        report={RUNNING_PAIR}
        ticks={{ SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 0, 527) }}
      />
    );

    expect(
      within(rowOf(container, "SOURCE_ANALYSIS")).getByText("12 s")
    ).toBeDefined();
  });

  test("sem `now`, um intervalo de 1 s só enquanto há etapa ativa", () => {
    vi.useFakeTimers();
    vi.setSystemTime(STARTED_MS + 1000);
    const { container, rerender } = render(
      <StageProgress
        report={RUNNING_PAIR}
        ticks={{ SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 0, 527) }}
      />
    );
    const row = rowOf(container, "SOURCE_ANALYSIS");
    expect(row).toHaveTextContent("1 s");

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(row).toHaveTextContent("4 s");
    expect(vi.getTimerCount()).toBe(1);

    rerender(
      <StageProgress
        report={statusReport({ stages: COMPLETED_STAGES, status: "COMPLETED" })}
        ticks={{}}
      />
    );
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe("StageProgress, o anúncio", () => {
  const pendingSource: AnalysisStatusReport = statusReport({
    stages: {
      LAYOUT_DISCOVERY: COMPLETED_STAGES.LAYOUT_DISCOVERY,
      REPOSITORY_VALIDATION: COMPLETED_STAGES.REPOSITORY_VALIDATION,
    },
  });

  test("a região viva existe vazia desde a montagem", () => {
    const { container } = render(
      <StageProgress now={STARTED_MS} report={pendingSource} ticks={{}} />
    );

    expect(liveRegion(container).textContent).toBe("");
  });

  test("anuncia o início e o fim, e nunca um tick, nem repetido nem novo", () => {
    const { container, rerender } = render(
      <StageProgress now={STARTED_MS} report={pendingSource} ticks={{}} />
    );
    const region = liveRegion(container);
    const label = analysisStageLabels.SOURCE_ANALYSIS;

    rerender(
      <StageProgress
        now={STARTED_MS}
        report={pendingSource}
        ticks={{ SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 0, 9) }}
      />
    );
    expect(region).toHaveTextContent(`Etapa iniciada: ${label}`);

    const mutations = watch(region);
    for (const completed of [0, 0, 2, 9, 9]) {
      rerender(
        <StageProgress
          now={STARTED_MS}
          report={pendingSource}
          ticks={{ SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", completed, 9) }}
        />
      );
    }
    expect(mutations.count()).toBe(0);

    rerender(
      <StageProgress
        now={STARTED_MS}
        report={statusReport({
          stages: {
            LAYOUT_DISCOVERY: COMPLETED_STAGES.LAYOUT_DISCOVERY,
            REPOSITORY_VALIDATION: COMPLETED_STAGES.REPOSITORY_VALIDATION,
            SOURCE_ANALYSIS: COMPLETED_STAGES.SOURCE_ANALYSIS,
          },
        })}
        ticks={{ SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 9, 9) }}
      />
    );
    expect(region).toHaveTextContent(`Etapa concluída: ${label}`);
    expect(region).not.toHaveTextContent("iniciada");
  });

  test("uma etapa vizinha que termina não reanuncia a que continua correndo", () => {
    const sourceRunning = {
      LAYOUT_DISCOVERY: COMPLETED_STAGES.LAYOUT_DISCOVERY,
      REPOSITORY_VALIDATION: COMPLETED_STAGES.REPOSITORY_VALIDATION,
      SOURCE_ANALYSIS: { startedAt: STARTED_AT, status: "RUNNING" },
    } as const;
    const ticks = { SOURCE_ANALYSIS: tick("SOURCE_ANALYSIS", 0, 9) };
    const { container, rerender } = render(
      <StageProgress
        now={STARTED_MS}
        report={statusReport({ stages: sourceRunning })}
        ticks={ticks}
      />
    );

    rerender(
      <StageProgress
        now={STARTED_MS}
        report={statusReport({
          stages: {
            ...sourceRunning,
            GIT_HISTORY_CHEAP: COMPLETED_STAGES.GIT_HISTORY_CHEAP,
          },
        })}
        ticks={ticks}
      />
    );

    const sentences = [...liveRegion(container).querySelectorAll("p")].map(
      (sentence) => sentence.textContent
    );
    expect(sentences).toEqual([
      `Etapa pulada: ${analysisStageLabels.GIT_HISTORY_CHEAP}`,
    ]);
  });

  test("a primeira leitura com relatório é só a linha de base", () => {
    const { container, rerender } = render(
      <StageProgress now={STARTED_MS} report={undefined} ticks={{}} />
    );

    rerender(
      <StageProgress
        now={STARTED_MS}
        report={statusReport({ stages: COMPLETED_STAGES, status: "COMPLETED" })}
        ticks={{}}
      />
    );

    expect(liveRegion(container).textContent).toBe("");
  });

  test("várias puladas de uma vez saem como frases separadas", () => {
    const sourceDone = {
      LAYOUT_DISCOVERY: COMPLETED_STAGES.LAYOUT_DISCOVERY,
      REPOSITORY_VALIDATION: COMPLETED_STAGES.REPOSITORY_VALIDATION,
      SOURCE_ANALYSIS: COMPLETED_STAGES.SOURCE_ANALYSIS,
    };
    const { container, rerender } = render(
      <StageProgress
        now={STARTED_MS}
        report={statusReport({ stages: sourceDone })}
        ticks={{}}
      />
    );

    rerender(
      <StageProgress
        now={STARTED_MS}
        report={statusReport({
          stages: {
            ...sourceDone,
            GIT_HISTORY_DETAILED: COMPLETED_STAGES.GIT_HISTORY_DETAILED,
            STRUCTURAL_GRAPH: COMPLETED_STAGES.STRUCTURAL_GRAPH,
          },
        })}
        ticks={{}}
      />
    );

    const sentences = [...liveRegion(container).querySelectorAll("p")].map(
      (sentence) => sentence.textContent
    );
    expect(sentences).toEqual([
      `Etapa pulada: ${analysisStageLabels.STRUCTURAL_GRAPH}`,
      `Etapa pulada: ${analysisStageLabels.GIT_HISTORY_DETAILED}`,
    ]);
  });
});
