import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
  handleFrame,
  resetEventState,
  subscribeToAnalyzer,
} from "@/bridge/events";
import { type RawFrame, transport } from "@/bridge/transport";
import { type AnalysisStage, analysisStageSchema } from "@/protocol/analysis";
import { EVENTS } from "@/protocol/registry";
import { queryKeys } from "@/queries/keys";
import { DEPENDENCY_PAGE_LIMIT } from "@/queries/options";
import { useAppStore } from "@/state/app-store";
import { TYPE_IDS } from "@/test/fixture-graph";
import stageCompletedSource from "../../../protocol/fixtures/analysis.stage.completed.ok.jsonl?raw";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const OTHER = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1X";
const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";

const initialStore = useAppStore.getState();
let queryClient: QueryClient;

beforeEach(() => {
  useAppStore.setState(initialStore, true);
  resetEventState();
  queryClient = new QueryClient();
});

const forwarded = (event: string, payload: unknown): RawFrame => ({
  kind: "forwarded",
  value: { event, payload },
});

const state = (value: unknown): RawFrame => ({ kind: "state", value });

const tick = (analysisId: string) => ({
  analysisId,
  completed: 1,
  stage: "SOURCE_ANALYSIS",
  total: 10,
  unit: "JAVA_FILES",
});

describe("validação na fronteira", () => {
  test("um tick válido é aplicado e o id não entra na store", () => {
    expect(
      handleFrame(forwarded("analysis.progress", tick(AN)), queryClient)
    ).toBe(true);
    const stored = useAppStore.getState().progress.SOURCE_ANALYSIS;
    expect(stored?.completed).toBe(1);
    expect(stored).not.toHaveProperty("analysisId");
  });

  test("um payload que não passa no schema é recusado e não armazenado", () => {
    const hostile = { ...tick(AN), completed: -1 };
    expect(
      handleFrame(forwarded("analysis.progress", hostile), queryClient)
    ).toBe(false);
    expect(useAppStore.getState().progress).toStrictEqual({});
  });

  test("um nome de evento fora do registro é recusado", () => {
    expect(handleFrame(forwarded("analysis.invented", {}), queryClient)).toBe(
      false
    );
  });

  test("uma chave a mais no envelope é recusada", () => {
    const frame: RawFrame = {
      kind: "forwarded",
      value: { event: "analysis.progress", extra: 1, payload: tick(AN) },
    };
    expect(handleFrame(frame, queryClient)).toBe(false);
  });

  test("um BackendState inválido não sobrescreve a fase corrente", () => {
    expect(handleFrame(state({ phase: "LIGADO" }), queryClient)).toBe(false);
    expect(useAppStore.getState().backend.phase).toBe("STARTING");
  });

  test("um BackendState válido é aplicado", () => {
    expect(
      handleFrame(state({ phase: "READY", protocolVersion: 1 }), queryClient)
    ).toBe(true);
    expect(useAppStore.getState().backend).toStrictEqual({
      phase: "READY",
      protocolVersion: 1,
    });
  });
});

const completeness = {
  historyTruncatedAtCommits: null,
  parseFailures: 0,
  shallowClone: false,
  skippedFiles: 0,
  totalFiles: 1,
};

const terminalsFor = (analysisId: string) =>
  ({
    "analysis.cancelled": { analysisId, completeness },
    "analysis.completed": { analysisId, completeness },
    "analysis.failed": {
      analysisId,
      error: { code: "REPO_NO_JAVA_SOURCES", origin: "analyzer", params: {} },
    },
  }) as const;

const stageCompleted = (stage: AnalysisStage, analysisId = AN) => ({
  analysisId,
  counters: {},
  durationMs: 1,
  stage,
  status: "COMPLETED",
});

const seed = (queryKey: readonly unknown[]): void => {
  queryClient.setQueryData(queryKey, { any: true });
};

const isInvalidated = (queryKey: readonly unknown[]): boolean | undefined =>
  queryClient.getQueryState(queryKey)?.isInvalidated;

describe("clearProgress no fim da análise", () => {
  for (const [event, payload] of Object.entries(terminalsFor(AN))) {
    test(`${event} zera o progresso`, () => {
      handleFrame(forwarded("analysis.progress", tick(AN)), queryClient);
      expect(Object.keys(useAppStore.getState().progress)).toHaveLength(1);

      expect(handleFrame(forwarded(event, payload), queryClient)).toBe(true);
      expect(useAppStore.getState().progress).toStrictEqual({});
    });
  }
});

describe("ticks atrasados", () => {
  test("um tick de uma análise que terminou é descartado", () => {
    handleFrame(
      forwarded("analysis.failed", {
        analysisId: AN,
        error: { code: "INTERNAL_UNEXPECTED", origin: "analyzer", params: {} },
      }),
      queryClient
    );
    expect(
      handleFrame(forwarded("analysis.progress", tick(AN)), queryClient)
    ).toBe(true);
    expect(useAppStore.getState().progress).toStrictEqual({});
  });

  test("um tick de outra análise não é descartado", () => {
    handleFrame(
      forwarded("analysis.failed", {
        analysisId: AN,
        error: { code: "INTERNAL_UNEXPECTED", origin: "analyzer", params: {} },
      }),
      queryClient
    );
    handleFrame(forwarded("analysis.progress", tick(OTHER)), queryClient);
    expect(Object.keys(useAppStore.getState().progress)).toHaveLength(1);
  });
});

describe("avisos", () => {
  test("um aviso é acrescentado e analysis.started limpa a lista", () => {
    const warning = {
      analysisId: AN,
      error: {
        code: "SOURCE_PARSE_FAILED",
        origin: "analyzer",
        params: { filePath: "src/Main.java" },
      },
    };
    expect(
      handleFrame(forwarded("analysis.warning", warning), queryClient)
    ).toBe(true);
    expect(useAppStore.getState().warnings).toHaveLength(1);

    handleFrame(
      forwarded("analysis.started", { analysisId: OTHER }),
      queryClient
    );
    expect(useAppStore.getState().warnings).toStrictEqual([]);
  });
});

const CACHE_CORRUPT_ERROR = {
  code: "CACHE_CORRUPT",
  detail: "o cache da análise não pôde ser lido e foi reconstruído vazio",
  origin: "analyzer",
  params: {},
};
const CACHE_MIGRATION_FAILED_ERROR = {
  code: "CACHE_MIGRATION_FAILED",
  detail:
    "o cache da análise não pôde ser migrado para esta versão do schema e foi reconstruído vazio",
  origin: "analyzer",
  params: { fromVersion: 2, toVersion: 1 },
};

describe("invalidação", () => {
  const pairedCacheEvents = {
    CORRUPT: { error: CACHE_CORRUPT_ERROR, reason: "CORRUPT" },
    HEAD_CHANGED: { error: null, reason: "HEAD_CHANGED" },
    SCHEMA_VERSION: {
      error: CACHE_MIGRATION_FAILED_ERROR,
      reason: "SCHEMA_VERSION",
    },
  } as const;

  for (const [reason, payload] of Object.entries(pairedCacheEvents)) {
    test(`cache.invalidated ${reason} limpa o cache inteiro`, () => {
      queryClient.setQueryData(["analysis", AN, "structure"], { any: true });
      expect(
        handleFrame(forwarded("cache.invalidated", payload), queryClient)
      ).toBe(true);
      expect(
        queryClient.getQueryData(["analysis", AN, "structure"])
      ).toBeUndefined();
    });
  }

  const mispairedCacheEvents = {
    "CORRUPT carregando CACHE_MIGRATION_FAILED": {
      error: CACHE_MIGRATION_FAILED_ERROR,
      reason: "CORRUPT",
    },
    "CORRUPT com error nulo": { error: null, reason: "CORRUPT" },
    "HEAD_CHANGED com um erro": {
      error: CACHE_CORRUPT_ERROR,
      reason: "HEAD_CHANGED",
    },
    "SCHEMA_VERSION carregando CACHE_CORRUPT": {
      error: CACHE_CORRUPT_ERROR,
      reason: "SCHEMA_VERSION",
    },
    "sem a chave error": { reason: "HEAD_CHANGED" },
  } as const;

  for (const [name, payload] of Object.entries(mispairedCacheEvents)) {
    test(`cache.invalidated ${name} é recusado e não limpa nada`, () => {
      queryClient.setQueryData(["analysis", AN, "structure"], { any: true });
      expect(
        handleFrame(forwarded("cache.invalidated", payload), queryClient)
      ).toBe(false);
      expect(
        queryClient.getQueryData(["analysis", AN, "structure"])
      ).toStrictEqual({ any: true });
    });
  }

  test("todo estágio tem regra, e SOURCE_ANALYSIS não invalida nada de propósito", () => {
    queryClient.setQueryData(["analysis", AN, "structure"], { any: true });

    for (const stage of analysisStageSchema.options) {
      expect(
        handleFrame(
          forwarded("analysis.stage.completed", stageCompleted(stage)),
          queryClient
        ),
        `${stage} deve ter uma regra`
      ).toBe(true);
    }

    const entry = queryClient
      .getQueryCache()
      .find({ queryKey: ["analysis", AN, "structure"] });
    expect(entry?.state.isInvalidated).toBe(true);
  });
});

describe("a tabela de invalidação, estágio por estágio", () => {
  const fixtureStageCompleted = (stage: AnalysisStage): unknown => {
    const [line = ""] = stageCompletedSource.trim().split("\n");
    const frame = JSON.parse(line) as { payload: Record<string, unknown> };
    return EVENTS["analysis.stage.completed"].parse({
      ...frame.payload,
      analysisId: AN,
      stage,
    });
  };

  const [ELEMENT = ""] = TYPE_IDS;

  const analysisSeeds = (analysisId: string) =>
    ({
      evolution: queryKeys.evolution.all(analysisId),
      hotspots: queryKeys.hotspots.all(analysisId),
      status: queryKeys.analysisStatus(analysisId),
      "structure.dependencies": queryKeys.structure.dependencies(
        analysisId,
        ELEMENT,
        "OUT",
        DEPENDENCY_PAGE_LIMIT
      ),
      "structure.element": queryKeys.structure.element(analysisId, ELEMENT),
      "structure.outline": queryKeys.structure.outline(analysisId, null),
      "structure.slice": queryKeys.structure.slice(analysisId, {
        level: "PACKAGE",
      }),
      tour: queryKeys.tour(analysisId),
    }) as const;

  const REPOSITORY_SEEDS = {
    "repository.currentAnalysis": queryKeys.repository.currentAnalysis(HANDLE),
    "repository.describe": queryKeys.repository.describe(HANDLE),
  } as const;

  type Seed =
    | keyof ReturnType<typeof analysisSeeds>
    | keyof typeof REPOSITORY_SEEDS;

  const SEEDS: readonly (readonly [string, readonly unknown[]])[] = [
    ...Object.entries(analysisSeeds(AN)),
    ...Object.entries(analysisSeeds(OTHER)).map(
      ([name, key]) => [`outra análise: ${name}`, key] as const
    ),
    ...Object.entries(REPOSITORY_SEEDS),
  ];

  const STRUCTURE: readonly Seed[] = [
    "structure.dependencies",
    "structure.element",
    "structure.outline",
    "structure.slice",
  ];

  const STAGE_PREFIXES = {
    EVOLUTIONARY_COUPLING: ["evolution"],
    GIT_HISTORY_CHEAP: ["evolution"],
    GIT_HISTORY_DETAILED: ["evolution"],
    GUIDED_TOUR: ["tour"],
    HOTSPOT_PROFILES: ["hotspots"],
    LAYOUT_DISCOVERY: [],
    PERSISTENCE: ["repository.currentAnalysis"],
    REPOSITORY_VALIDATION: [],
    SOURCE_ANALYSIS: [],
    STRUCTURAL_GRAPH: STRUCTURE,
  } as const satisfies Record<AnalysisStage, readonly Seed[]>;

  const invalidatedSeeds = (): string[] =>
    SEEDS.filter(
      ([, key]) => queryClient.getQueryState(key)?.isInvalidated === true
    )
      .map(([name]) => name)
      .sort();

  test("a tabela tem uma linha por estágio do protocolo, e só elas", () => {
    expect(Object.keys(STAGE_PREFIXES).sort()).toStrictEqual(
      [...analysisStageSchema.options].sort()
    );
  });

  test.each(analysisStageSchema.options)(
    "%s invalida exatamente o status e a linha dele, e nada mais",
    (stage) => {
      for (const [, key] of SEEDS) {
        seed(key);
      }

      expect(
        handleFrame(
          forwarded("analysis.stage.completed", fixtureStageCompleted(stage)),
          queryClient
        )
      ).toBe(true);

      expect(invalidatedSeeds()).toStrictEqual(
        ["status", ...STAGE_PREFIXES[stage]].sort()
      );
    }
  );
});

describe("o status da análise", () => {
  test("analysis.started invalida o status da análise que começou", () => {
    seed(queryKeys.analysisStatus(AN));

    handleFrame(forwarded("analysis.started", { analysisId: AN }), queryClient);

    expect(isInvalidated(queryKeys.analysisStatus(AN))).toBe(true);
  });

  test("todo analysis.stage.completed invalida o status, até o que não tem regra própria", () => {
    for (const stage of analysisStageSchema.options) {
      queryClient.clear();
      seed(queryKeys.analysisStatus(AN));

      handleFrame(
        forwarded("analysis.stage.completed", stageCompleted(stage)),
        queryClient
      );

      expect(isInvalidated(queryKeys.analysisStatus(AN)), stage).toBe(true);
    }
  });

  test("o fim de um estágio não invalida o status de outra análise", () => {
    seed(queryKeys.analysisStatus(OTHER));

    handleFrame(
      forwarded("analysis.stage.completed", stageCompleted("SOURCE_ANALYSIS")),
      queryClient
    );

    expect(isInvalidated(queryKeys.analysisStatus(OTHER))).toBe(false);
  });

  for (const [event, payload] of Object.entries(terminalsFor(AN))) {
    test(`${event} invalida o status pelo prefixo da análise`, () => {
      seed(queryKeys.analysisStatus(AN));

      handleFrame(forwarded(event, payload), queryClient);

      expect(isInvalidated(queryKeys.analysisStatus(AN))).toBe(true);
    });
  }
});

describe("a execução em voo", () => {
  test("analysis.started a põe na store", () => {
    handleFrame(forwarded("analysis.started", { analysisId: AN }), queryClient);

    expect(useAppStore.getState().runningAnalysisId).toBe(AN);
  });

  for (const [event, payload] of Object.entries(terminalsFor(AN))) {
    test(`${event} da mesma análise a tira`, () => {
      handleFrame(
        forwarded("analysis.started", { analysisId: AN }),
        queryClient
      );

      expect(handleFrame(forwarded(event, payload), queryClient)).toBe(true);
      expect(useAppStore.getState().runningAnalysisId).toBeNull();
    });
  }

  for (const [event, payload] of Object.entries(terminalsFor(OTHER))) {
    test(`${event} de outra análise não a tira`, () => {
      handleFrame(
        forwarded("analysis.started", { analysisId: AN }),
        queryClient
      );

      handleFrame(forwarded(event, payload), queryClient);

      expect(useAppStore.getState().runningAnalysisId).toBe(AN);
    });
  }

  test("um analysis.started inválido não põe nada", () => {
    expect(
      handleFrame(
        forwarded("analysis.started", { analysisId: "an_minúsculo" }),
        queryClient
      )
    ).toBe(false);
    expect(useAppStore.getState().runningAnalysisId).toBeNull();
  });
});

describe("o describe não é refeito no meio da execução", () => {
  const describeStages = [
    "REPOSITORY_VALIDATION",
    "LAYOUT_DISCOVERY",
  ] as const satisfies readonly AnalysisStage[];

  for (const stage of describeStages) {
    test(`${stage} não invalida o describe`, () => {
      seed(queryKeys.repository.describe(HANDLE));

      expect(
        handleFrame(
          forwarded("analysis.stage.completed", stageCompleted(stage)),
          queryClient
        )
      ).toBe(true);

      expect(isInvalidated(queryKeys.repository.describe(HANDLE))).toBe(false);
    });
  }

  test("nenhum estágio invalida o describe", () => {
    seed(queryKeys.repository.describe(HANDLE));

    for (const stage of analysisStageSchema.options) {
      handleFrame(
        forwarded("analysis.stage.completed", stageCompleted(stage)),
        queryClient
      );
    }

    expect(isInvalidated(queryKeys.repository.describe(HANDLE))).toBe(false);
  });

  for (const [event, payload] of Object.entries(terminalsFor(AN))) {
    test(`${event} invalida o describe, sem precisar do handle`, () => {
      seed(queryKeys.repository.describe(HANDLE));

      handleFrame(forwarded(event, payload), queryClient);

      expect(isInvalidated(queryKeys.repository.describe(HANDLE))).toBe(true);
    });
  }

  test("PERSISTENCE continua invalidando a análise corrente, e não o describe", () => {
    seed(queryKeys.repository.currentAnalysis(HANDLE));
    seed(queryKeys.repository.describe(HANDLE));

    handleFrame(
      forwarded("analysis.stage.completed", stageCompleted("PERSISTENCE")),
      queryClient
    );

    expect(isInvalidated(queryKeys.repository.currentAnalysis(HANDLE))).toBe(
      true
    );
    expect(isInvalidated(queryKeys.repository.describe(HANDLE))).toBe(false);
  });

  test("analysis.started invalida a análise corrente, e não o describe", () => {
    seed(queryKeys.repository.currentAnalysis(HANDLE));
    seed(queryKeys.repository.describe(HANDLE));

    handleFrame(forwarded("analysis.started", { analysisId: AN }), queryClient);

    expect(isInvalidated(queryKeys.repository.currentAnalysis(HANDLE))).toBe(
      true
    );
    expect(isInvalidated(queryKeys.repository.describe(HANDLE))).toBe(false);
  });
});

describe("o analisador de volta ao READY", () => {
  const stopChannels = vi.fn<() => void>();
  let stopBridge: () => void = () => undefined;

  beforeEach(async () => {
    vi.spyOn(transport, "subscribe").mockResolvedValue(stopChannels);
    stopBridge = await subscribeToAnalyzer(queryClient);
  });

  afterEach(() => {
    stopBridge();
    vi.restoreAllMocks();
    stopChannels.mockClear();
  });

  const dataOf = (queryKey: readonly unknown[]): unknown =>
    queryClient.getQueryState(queryKey)?.data;

  test("o reinício depois de um travamento esquece toda análise, e só ela", () => {
    handleFrame(state({ phase: "READY", protocolVersion: 1 }), queryClient);
    seed(queryKeys.analysisStatus(AN));
    seed(queryKeys.structure.all(OTHER));
    seed(queryKeys.repository.describe(HANDLE));

    handleFrame(state({ exitCode: 137, phase: "CRASHED" }), queryClient);
    useAppStore.getState().recoverBackend();
    expect(dataOf(queryKeys.analysisStatus(AN))).toStrictEqual({ any: true });
    handleFrame(state({ phase: "READY", protocolVersion: 1 }), queryClient);

    expect(dataOf(queryKeys.analysisStatus(AN))).toBeUndefined();
    expect(dataOf(queryKeys.structure.all(OTHER))).toBeUndefined();
    expect(dataOf(queryKeys.repository.describe(HANDLE))).toStrictEqual({
      any: true,
    });
  });

  test("um READY que chega de novo ao READY não esquece nada", () => {
    handleFrame(state({ phase: "READY", protocolVersion: 1 }), queryClient);
    seed(queryKeys.analysisStatus(AN));

    handleFrame(state({ phase: "READY", protocolVersion: 1 }), queryClient);

    expect(dataOf(queryKeys.analysisStatus(AN))).toStrictEqual({ any: true });
  });

  test("depois de desassinar, nem os canais nem a store chegam à ponte", () => {
    seed(queryKeys.analysisStatus(AN));

    stopBridge();
    handleFrame(state({ phase: "READY", protocolVersion: 1 }), queryClient);

    expect(stopChannels).toHaveBeenCalledOnce();
    expect(dataOf(queryKeys.analysisStatus(AN))).toStrictEqual({ any: true });
  });
});
