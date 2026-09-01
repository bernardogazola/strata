import { beforeEach, describe, expect, test } from "vitest";
import type { ProgressTick } from "@/protocol/analysis";
import {
  type BackendState,
  selectAnalyzerUsable,
  selectBackendRecoverable,
  selectIsAnalysing,
  useAppStore,
} from "../app-store";

const initialStore = useAppStore.getState();

beforeEach(() => {
  useAppStore.setState(initialStore, true);
});

const tick = (
  stage: ProgressTick["stage"],
  unit: ProgressTick["unit"],
  completed: number
): ProgressTick => ({ completed, stage, total: 100, unit });

const INCOMPATIBLE: BackendState = {
  expected: 1,
  phase: "INCOMPATIBLE",
  received: 7,
};

describe("BackendState", () => {
  test("INCOMPATIBLE é construtível e carrega as duas versões", () => {
    useAppStore.getState().setBackend(INCOMPATIBLE);
    expect(useAppStore.getState().backend).toEqual(INCOMPATIBLE);
  });

  test("INCOMPATIBLE não é um analisador utilizável", () => {
    useAppStore.getState().setBackend(INCOMPATIBLE);
    expect(selectAnalyzerUsable(useAppStore.getState())).toBe(false);
  });

  test("só READY é utilizável", () => {
    useAppStore.getState().setBackend({ phase: "READY", protocolVersion: 1 });
    expect(selectAnalyzerUsable(useAppStore.getState())).toBe(true);
  });
});

describe("applyProgress", () => {
  test("indexa por estágio: dois estágios concorrentes coexistem", () => {
    const { applyProgress } = useAppStore.getState();
    applyProgress(tick("SOURCE_ANALYSIS", "JAVA_FILES", 10));
    applyProgress(tick("GIT_HISTORY_CHEAP", "COMMITS", 400));

    const { progress } = useAppStore.getState();
    expect(Object.keys(progress).sort()).toEqual([
      "GIT_HISTORY_CHEAP",
      "SOURCE_ANALYSIS",
    ]);
    expect(progress.SOURCE_ANALYSIS?.completed).toBe(10);
    expect(progress.GIT_HISTORY_CHEAP?.completed).toBe(400);
  });

  test("um tick novo de um estágio não mexe no outro", () => {
    const { applyProgress } = useAppStore.getState();
    applyProgress(tick("SOURCE_ANALYSIS", "JAVA_FILES", 10));
    applyProgress(tick("GIT_HISTORY_CHEAP", "COMMITS", 400));
    applyProgress(tick("SOURCE_ANALYSIS", "JAVA_FILES", 55));

    const { progress } = useAppStore.getState();
    expect(progress.SOURCE_ANALYSIS?.completed).toBe(55);
    expect(progress.GIT_HISTORY_CHEAP?.completed).toBe(400);
    expect(Object.keys(progress)).toHaveLength(2);
  });

  test("qualquer estágio em progresso já torna selectIsAnalysing true", () => {
    useAppStore.getState().applyProgress(tick("PERSISTENCE", "TYPES", 0));
    expect(selectIsAnalysing(useAppStore.getState())).toBe(true);
  });
});

describe("clearProgress", () => {
  test("esvazia o progresso e devolve selectIsAnalysing a false", () => {
    const { applyProgress, clearProgress } = useAppStore.getState();
    applyProgress(tick("SOURCE_ANALYSIS", "JAVA_FILES", 10));
    applyProgress(tick("GIT_HISTORY_CHEAP", "COMMITS", 400));
    expect(selectIsAnalysing(useAppStore.getState())).toBe(true);

    clearProgress();
    expect(useAppStore.getState().progress).toEqual({});
    expect(selectIsAnalysing(useAppStore.getState())).toBe(false);
  });

  test("é idempotente", () => {
    const { clearProgress } = useAppStore.getState();
    clearProgress();
    clearProgress();
    expect(selectIsAnalysing(useAppStore.getState())).toBe(false);
  });
});

describe("recoverBackend", () => {
  test("de CRASHED volta para STARTING", () => {
    const { recoverBackend, setBackend } = useAppStore.getState();
    setBackend({ exitCode: 134, phase: "CRASHED" });
    expect(selectBackendRecoverable(useAppStore.getState())).toBe(true);

    recoverBackend();
    expect(useAppStore.getState().backend).toEqual({ phase: "STARTING" });
  });

  test("limpa o progresso que o travamento deixou para trás", () => {
    const { applyProgress, recoverBackend, setBackend } =
      useAppStore.getState();
    applyProgress(tick("SOURCE_ANALYSIS", "JAVA_FILES", 10));
    setBackend({ exitCode: null, phase: "CRASHED" });

    recoverBackend();
    expect(selectIsAnalysing(useAppStore.getState())).toBe(false);
  });

  test("de INCOMPATIBLE não faz nada: o estado é terminal", () => {
    const { recoverBackend, setBackend } = useAppStore.getState();
    setBackend(INCOMPATIBLE);
    expect(selectBackendRecoverable(useAppStore.getState())).toBe(false);

    recoverBackend();
    expect(useAppStore.getState().backend).toEqual(INCOMPATIBLE);
  });

  test("de READY não faz nada", () => {
    const ready: BackendState = { phase: "READY", protocolVersion: 1 };
    const { recoverBackend, setBackend } = useAppStore.getState();
    setBackend(ready);
    recoverBackend();
    expect(useAppStore.getState().backend).toEqual(ready);
  });

  test("de STARTING não faz nada, então dois cliques não geram dois spawns", () => {
    const { recoverBackend } = useAppStore.getState();
    recoverBackend();
    recoverBackend();
    expect(useAppStore.getState().backend).toEqual({ phase: "STARTING" });
  });
});
