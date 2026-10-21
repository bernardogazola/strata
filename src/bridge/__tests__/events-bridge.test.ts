import { QueryClient } from "@tanstack/react-query";
import { beforeEach, describe, expect, test } from "vitest";
import { handleFrame, resetEventState } from "@/bridge/events";
import type { RawFrame } from "@/bridge/transport";
import { analysisStageSchema } from "@/protocol/analysis";
import { useAppStore } from "@/state/app-store";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const OTHER = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1X";

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

describe("clearProgress no fim da análise", () => {
  const completeness = {
    historyTruncatedAtCommits: null,
    parseFailures: 0,
    shallowClone: false,
    skippedFiles: 0,
    totalFiles: 1,
  };

  const terminal = {
    "analysis.cancelled": { analysisId: AN, completeness },
    "analysis.completed": { analysisId: AN, completeness },
    "analysis.failed": {
      analysisId: AN,
      error: { code: "REPO_NO_JAVA_SOURCES", origin: "analyzer", params: {} },
    },
  } as const;

  for (const [event, payload] of Object.entries(terminal)) {
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
    const stageEvent = (stage: string) => ({
      analysisId: AN,
      counters: {},
      durationMs: 1,
      stage,
      status: "COMPLETED",
    });

    for (const stage of analysisStageSchema.options) {
      expect(
        handleFrame(
          forwarded("analysis.stage.completed", stageEvent(stage)),
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
