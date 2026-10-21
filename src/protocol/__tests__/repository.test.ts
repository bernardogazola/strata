import { describe, expect, test } from "vitest";
import { analysisStatusSchema } from "../analysis";
import {
  analysisStatusReportSchema,
  repositoryDescriptionSchema,
} from "../repository";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const MAX_DESCRIBED_MODULES = 256;
const MAX_MODULE_CHARS = 1024;

const description = (declaredModules: unknown): Record<string, unknown> => ({
  buildSystem: "MAVEN",
  cachedAnalysisId: null,
  declaredModules,
  defaultBranch: "main",
  displayName: "multi-module",
  headCommitId: null,
  isBare: false,
  isGitRepository: true,
  isShallow: false,
  sourceRoots: [],
  totalFileCount: 0,
});

const modules = (count: number): string[] =>
  Array.from({ length: count }, (_, index) => `m${index}`);

const FAILURE = {
  code: "CACHE_UNAVAILABLE",
  detail: "another process holds the cache lock",
  origin: "analyzer",
  params: { cause: "LOCKED" },
} as const;

const report = (status: string, error: unknown): Record<string, unknown> => ({
  analysisId: AN,
  completeness: {
    historyTruncatedAtCommits: null,
    parseFailures: 0,
    shallowClone: false,
    skippedFiles: 0,
    totalFiles: 3,
  },
  error,
  finishedAt: status === "RUNNING" ? null : "2026-10-21T12:00:01Z",
  headCommitId: null,
  stages: [],
  startedAt: "2026-10-21T12:00:00Z",
  status,
});

describe("analysisStatusReportSchema", () => {
  test("o erro existe só em um relatório FAILED, e todo FAILED tem o seu", () => {
    const accepted = {
      CANCELLED: { withError: false, withoutError: true },
      COMPLETED: { withError: false, withoutError: true },
      FAILED: { withError: true, withoutError: false },
      RUNNING: { withError: false, withoutError: true },
    } as const;

    expect(Object.keys(accepted).sort()).toStrictEqual(
      [...analysisStatusSchema.options].sort()
    );
    for (const status of analysisStatusSchema.options) {
      expect(
        analysisStatusReportSchema.safeParse(report(status, FAILURE)).success,
        `${status} com erro`
      ).toBe(accepted[status].withError);
      expect(
        analysisStatusReportSchema.safeParse(report(status, null)).success,
        `${status} sem erro`
      ).toBe(accepted[status].withoutError);
    }
  });

  test("a recusa aponta para `error`, com uma frase por sentido", () => {
    const missing = analysisStatusReportSchema.safeParse(
      report("FAILED", null)
    );
    const stray = analysisStatusReportSchema.safeParse(
      report("COMPLETED", FAILURE)
    );

    expect(missing.error?.issues.map((issue) => issue.path)).toStrictEqual([
      ["error"],
    ]);
    expect(stray.error?.issues.map((issue) => issue.path)).toStrictEqual([
      ["error"],
    ]);
    expect(missing.error?.issues[0]?.message).not.toBe(
      stray.error?.issues[0]?.message
    );
  });

  test("a chave `error` é obrigatória: ausente não é o mesmo que nula", () => {
    const { error: _omitted, ...withoutKey } = report("COMPLETED", null);

    expect(analysisStatusReportSchema.safeParse(withoutKey).success).toBe(
      false
    );
  });

  test("o erro do relatório é um ProtocolError estrito", () => {
    expect(
      analysisStatusReportSchema.safeParse(
        report("FAILED", { ...FAILURE, sobrando: 1 })
      ).success
    ).toBe(false);
  });
});

describe("repositoryDescriptionSchema.declaredModules", () => {
  test("a ordem do documento chega intacta, sem ordenar", () => {
    const parsed = repositoryDescriptionSchema.parse(
      description(["core", "api"])
    );

    expect(parsed.declaredModules).toStrictEqual(["core", "api"]);
  });

  test("256 módulos passam e 257 reprovam no bridge", () => {
    expect(
      repositoryDescriptionSchema.safeParse(
        description(modules(MAX_DESCRIBED_MODULES))
      ).success
    ).toBe(true);
    expect(
      repositoryDescriptionSchema.safeParse(
        description(modules(MAX_DESCRIBED_MODULES + 1))
      ).success
    ).toBe(false);
  });

  test("uma entrada cabe no teto de um caminho, e uma unidade a mais reprova", () => {
    expect(
      repositoryDescriptionSchema.safeParse(
        description(["a".repeat(MAX_MODULE_CHARS)])
      ).success
    ).toBe(true);
    expect(
      repositoryDescriptionSchema.safeParse(
        description(["a".repeat(MAX_MODULE_CHARS + 1)])
      ).success
    ).toBe(false);
  });

  test("a chave é obrigatória: um Java sem ela é divergência de versão", () => {
    const { declaredModules: _omitted, ...withoutKey } = description([]);

    expect(repositoryDescriptionSchema.safeParse(withoutKey).success).toBe(
      false
    );
    expect(
      repositoryDescriptionSchema.safeParse(description(null)).success
    ).toBe(false);
  });
});
