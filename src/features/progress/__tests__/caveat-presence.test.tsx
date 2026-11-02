import { render } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import {
  CompletenessCaveat,
  hasCompletenessCaveat,
} from "@/features/progress/completeness-caveat";
import type { AnalysisStatusReport } from "@/protocol/repository";
import {
  COMPLETED_STAGES,
  type StatusReportInput,
  statusReport,
} from "./status-report";

const completed = (
  completeness: StatusReportInput["completeness"]
): AnalysisStatusReport =>
  statusReport({ completeness, stages: COMPLETED_STAGES, status: "COMPLETED" });

const CASES: readonly [
  name: string,
  report: AnalysisStatusReport | undefined,
  percentiles: boolean,
  expected: boolean,
][] = [
  ["o relatório ainda não chegou", undefined, false, false],
  [
    "uma execução em andamento, mesmo parcial",
    statusReport({ completeness: { skippedFiles: 3, totalFiles: 9 } }),
    false,
    false,
  ],
  [
    "uma análise concluída e completa",
    completed({ totalFiles: 9 }),
    true,
    false,
  ],
  [
    "arquivos de fora",
    completed({ skippedFiles: 3, totalFiles: 259 }),
    false,
    true,
  ],
  [
    "um clone raso",
    completed({ shallowClone: true, totalFiles: 9 }),
    false,
    true,
  ],
  [
    "só falhas de parse, sem a linha dos percentis",
    completed({ parseFailures: 2, totalFiles: 9 }),
    false,
    false,
  ],
  [
    "só falhas de parse, com a linha dos percentis",
    completed({ parseFailures: 2, totalFiles: 9 }),
    true,
    true,
  ],
];

describe("hasCompletenessCaveat responde o que o componente renderiza", () => {
  test.each(CASES)("%s", (_, report, percentiles, expected) => {
    const { container } = render(
      <CompletenessCaveat compact percentiles={percentiles} report={report} />
    );

    expect(hasCompletenessCaveat(report, percentiles)).toBe(expected);
    expect(container.childElementCount > 0).toBe(expected);
  });

  test("sem o segundo argumento, a pergunta é a do inspetor, sem a linha dos percentis", () => {
    const report = completed({ parseFailures: 2, totalFiles: 9 });

    expect(hasCompletenessCaveat(report)).toBe(false);
    expect(
      hasCompletenessCaveat(completed({ skippedFiles: 1, totalFiles: 9 }))
    ).toBe(true);
  });
});
