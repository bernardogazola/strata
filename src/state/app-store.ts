import { create } from "zustand";
import { devtools } from "zustand/middleware";
import type { AnalysisStage, ProgressTick } from "@/protocol/analysis";

export type BackendState =
  | { phase: "STARTING" }
  | { phase: "READY"; protocolVersion: number }
  | { phase: "CRASHED"; exitCode: number | null }
  | { phase: "INCOMPATIBLE"; expected: number; received: number };

interface AppState {
  readonly activeElementId: string | null;
  readonly backend: BackendState;
  readonly progress: Readonly<Partial<Record<AnalysisStage, ProgressTick>>>;
}

interface AppActions {
  readonly applyProgress: (tick: ProgressTick) => void;
  readonly clearProgress: () => void;
  readonly recoverBackend: () => void;
  readonly setActiveElementId: (elementId: string | null) => void;
  readonly setBackend: (backend: BackendState) => void;
}

export type AppStore = AppState & AppActions;

const initialState: AppState = {
  activeElementId: null,
  backend: { phase: "STARTING" },
  progress: {},
};

export const useAppStore = create<AppStore>()(
  devtools(
    (set) => ({
      ...initialState,

      applyProgress: (tick) =>
        set(
          (state) => ({
            progress: { ...state.progress, [tick.stage]: tick },
          }),
          false,
          "applyProgress"
        ),

      clearProgress: () => set({ progress: {} }, false, "clearProgress"),

      recoverBackend: () =>
        set(
          (state) =>
            state.backend.phase === "CRASHED"
              ? { backend: { phase: "STARTING" as const }, progress: {} }
              : state,
          false,
          "recoverBackend"
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

export const selectAnalyzerUsable = (state: AppStore): boolean =>
  state.backend.phase === "READY";

export const selectBackendRecoverable = (state: AppStore): boolean =>
  state.backend.phase === "CRASHED";
