import { render, screen } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import { CompletenessCaveat } from "@/features/progress/completeness-caveat";
import { t } from "@/i18n/messages";
import { COMPLETED_STAGES, statusReport } from "./status-report";

const PARTIAL = {
  shallowClone: true,
  skippedFiles: 3,
  totalFiles: 259,
} as const;

describe("CompletenessCaveat", () => {
  test("um relatório concluído e parcial mostra o que ficou de fora", () => {
    render(
      <CompletenessCaveat
        report={statusReport({
          completeness: PARTIAL,
          stages: COMPLETED_STAGES,
          status: "COMPLETED",
        })}
      />
    );

    const files = screen.getByText(
      "Análise parcial: 3 de 259 arquivos ficaram de fora."
    );
    expect(files).toHaveClass("font-medium");
    expect(screen.getByText(t.analysis.partial.shallowClone)).toBeDefined();
  });

  test("um arquivo só conjuga no singular, com ponto de milhar no total", () => {
    render(
      <CompletenessCaveat
        report={statusReport({
          completeness: { skippedFiles: 1, totalFiles: 3902 },
          status: "COMPLETED",
        })}
      />
    );

    expect(
      screen.getByText("Análise parcial: 1 de 3.902 arquivos ficou de fora.")
    ).toBeDefined();
  });

  test.each([
    ["RUNNING", null],
    ["CANCELLED", null],
    ["FAILED", { code: "INTERNAL_UNEXPECTED" }],
  ] as const)(
    "nada a partir de um relatório %s, mesmo com a completude parcial",
    (status, error) => {
      const { container } = render(
        <CompletenessCaveat
          report={statusReport({ completeness: PARTIAL, error, status })}
        />
      );

      expect(container).toBeEmptyDOMElement();
    }
  );

  test("nada de um relatório concluído e completo", () => {
    const { container } = render(
      <CompletenessCaveat
        percentiles
        report={statusReport({
          completeness: { totalFiles: 9 },
          stages: COMPLETED_STAGES,
          status: "COMPLETED",
        })}
      />
    );

    expect(container).toBeEmptyDOMElement();
  });

  test("nada enquanto o relatório não chegou", () => {
    const { container } = render(<CompletenessCaveat report={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  test("só o clone raso: a linha dele sozinha, como a primeira", () => {
    const { container } = render(
      <CompletenessCaveat
        report={statusReport({
          completeness: { shallowClone: true, totalFiles: 259 },
          status: "COMPLETED",
        })}
      />
    );

    const lines = [...container.querySelectorAll("p")];
    expect(lines.map((line) => line.textContent)).toEqual([
      t.analysis.partial.shallowClone,
    ]);
    expect(lines[0]).toHaveClass("font-medium");
  });

  test("a linha dos percentis só quando a tela pede", () => {
    const report = statusReport({ completeness: PARTIAL, status: "COMPLETED" });
    const without = render(<CompletenessCaveat report={report} />);
    expect(without.queryByText(t.analysis.partial.percentiles)).toBeNull();
    without.unmount();

    render(<CompletenessCaveat percentiles report={report} />);
    expect(screen.getByText(t.analysis.partial.percentiles)).toBeDefined();
  });

  test("o histórico truncado sai com o número de commits", () => {
    render(
      <CompletenessCaveat
        report={statusReport({
          completeness: { historyTruncatedAtCommits: 10_000, totalFiles: 9 },
          status: "COMPLETED",
        })}
      />
    );

    expect(
      screen.getByText("Histórico truncado em 10.000 commits.")
    ).toBeDefined();
  });

  test("é um bloco persistente: nem alerta, nem status, nem botão de fechar", () => {
    render(
      <CompletenessCaveat
        report={statusReport({ completeness: PARTIAL, status: "COMPLETED" })}
      />
    );

    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
  });

  test("a forma compacta do inspetor diz o mesmo em um parágrafo só", () => {
    const { container } = render(
      <CompletenessCaveat
        compact
        percentiles
        report={statusReport({ completeness: PARTIAL, status: "COMPLETED" })}
      />
    );

    const paragraphs = container.querySelectorAll("p");
    expect(paragraphs).toHaveLength(1);
    expect(paragraphs[0].textContent).toBe(
      [
        "Análise parcial: 3 de 259 arquivos ficaram de fora.",
        t.analysis.partial.shallowClone,
        t.analysis.partial.percentiles,
      ].join(" ")
    );
    expect(paragraphs[0]).toHaveClass("text-xs/[18px]");
  });
});
