import { z } from "zod";
import { create } from "zustand";
import { devtools } from "zustand/middleware";
import type { AnalysisStage, ProgressTick } from "@/protocol/analysis";
import type { ProtocolError } from "@/protocol/errors";

export type BackendState =
  | { phase: "STARTING" }
  | { phase: "READY"; protocolVersion: number }
  | { phase: "CRASHED"; exitCode: number | null }
  | { phase: "INCOMPATIBLE"; expected: number; received: number };

export const backendStateSchema = z.discriminatedUnion("phase", [
  z.strictObject({ phase: z.literal("STARTING") }),
  z.strictObject({
    phase: z.literal("READY"),
    protocolVersion: z.number().int(),
  }),
  z.strictObject({
    exitCode: z.number().int().nullable(),
    phase: z.literal("CRASHED"),
  }),
  z.strictObject({
    expected: z.number().int(),
    phase: z.literal("INCOMPATIBLE"),
    received: z.number().int(),
  }),
]) satisfies z.ZodType<BackendState>;

export interface AnalysisWarning {
  readonly analysisId: string;
  readonly error: ProtocolError;
}

export const MAX_RETAINED_WARNINGS = 500;

interface AppState {
  readonly activeElementId: string | null;
  readonly backend: BackendState;
  readonly progress: Readonly<Partial<Record<AnalysisStage, ProgressTick>>>;
  readonly runningAnalysisId: string | null;
  readonly warnings: readonly AnalysisWarning[];
}

interface AppActions {
  readonly appendWarning: (warning: AnalysisWarning) => void;
  readonly applyProgress: (tick: ProgressTick) => void;
  readonly clearProgress: () => void;
  readonly clearWarnings: () => void;
  readonly markAnalysisEnded: (analysisId: string) => void;
  readonly markAnalysisRunning: (analysisId: string) => void;
  readonly recoverBackend: () => void;
  readonly releaseActiveElementId: (elementId: string) => void;
  readonly setActiveElementId: (elementId: string | null) => void;
  readonly setBackend: (backend: BackendState) => void;
}

export type AppStore = AppState & AppActions;

const initialState: AppState = {
  activeElementId: null,
  backend: { phase: "STARTING" },
  progress: {},
  runningAnalysisId: null,
  warnings: [],
};

export const useAppStore = create<AppStore>()(
  devtools(
    (set) => ({
      ...initialState,

      appendWarning: (warning) =>
        set(
          (state) =>
            state.warnings.length >= MAX_RETAINED_WARNINGS
              ? state
              : { warnings: [...state.warnings, warning] },
          false,
          "appendWarning"
        ),

      applyProgress: (tick) =>
        set(
          (state) => ({
            progress: { ...state.progress, [tick.stage]: tick },
          }),
          false,
          "applyProgress"
        ),

      clearProgress: () => set({ progress: {} }, false, "clearProgress"),

      clearWarnings: () => set({ warnings: [] }, false, "clearWarnings"),

      markAnalysisEnded: (analysisId) =>
        set(
          (state) =>
            state.runningAnalysisId === analysisId
              ? { runningAnalysisId: null }
              : state,
          false,
          "markAnalysisEnded"
        ),

      markAnalysisRunning: (analysisId) =>
        set({ runningAnalysisId: analysisId }, false, "markAnalysisRunning"),

      recoverBackend: () =>
        set(
          (state) =>
            state.backend.phase === "CRASHED"
              ? {
                  backend: { phase: "STARTING" as const },
                  progress: {},
                  runningAnalysisId: null,
                }
              : state,
          false,
          "recoverBackend"
        ),

      releaseActiveElementId: (elementId) =>
        set(
          (state) =>
            state.activeElementId === elementId
              ? { activeElementId: null }
              : state,
          false,
          "releaseActiveElementId"
        ),

      setActiveElementId: (elementId) =>
        set({ activeElementId: elementId }, false, "setActiveElementId"),

      setBackend: (backend) => set({ backend }, false, "setBackend"),
    }),
    { name: "strata" }
  )
);

export const selectActiveElementId = (state: AppStore): string | null =>
  state.activeElementId;

export const selectIsAnalysing = (state: AppStore): boolean =>
  Object.keys(state.progress).length > 0;

export const selectIsRunInFlight = (state: AppStore): boolean =>
  state.runningAnalysisId !== null;

export const selectAnalyzerUsable = (state: AppStore): boolean =>
  state.backend.phase === "READY";

export const selectBackendRecoverable = (state: AppStore): boolean =>
  state.backend.phase === "CRASHED";

export const selectWarningCount = (state: AppStore): number =>
  state.warnings.length;
