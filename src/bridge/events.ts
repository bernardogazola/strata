import type { QueryClient } from "@tanstack/react-query";
import type { AnalysisStage, ProgressTick } from "@/protocol/analysis";
import type { ProtocolEventName } from "@/protocol/envelope";
import { EVENTS } from "@/protocol/registry";
import { queryKeys } from "@/queries/keys";
import type { BackendState } from "@/state/app-store";
import { backendStateSchema, useAppStore } from "@/state/app-store";
import { analyzerState } from "./analyzer";
import { forwardedFrameSchema } from "./contracts";
import { type RawFrame, transport } from "./transport";

type StageInvalidation =
  | { readonly scope: "none" }
  | {
      readonly scope: "analysis";
      readonly key: (id: string) => readonly unknown[];
    }
  | { readonly scope: "repository"; readonly describe: boolean };

const STAGE_INVALIDATION = {
  EVOLUTIONARY_COUPLING: { key: queryKeys.evolution.all, scope: "analysis" },
  GIT_HISTORY_CHEAP: { key: queryKeys.evolution.all, scope: "analysis" },
  GIT_HISTORY_DETAILED: { key: queryKeys.evolution.all, scope: "analysis" },
  GUIDED_TOUR: { key: queryKeys.tour, scope: "analysis" },
  HOTSPOT_PROFILES: { key: queryKeys.hotspots.all, scope: "analysis" },
  LAYOUT_DISCOVERY: { describe: true, scope: "repository" },
  PERSISTENCE: { describe: false, scope: "repository" },
  REPOSITORY_VALIDATION: { describe: true, scope: "repository" },
  SOURCE_ANALYSIS: { scope: "none" },
  STRUCTURAL_GRAPH: { key: queryKeys.structure.all, scope: "analysis" },
} as const satisfies Record<AnalysisStage, StageInvalidation>;

const invalidateAnalysis = (
  queryClient: QueryClient,
  key: readonly unknown[]
): void => {
  queryClient
    .invalidateQueries({ queryKey: key, refetchType: "all" })
    .catch(() => undefined);
};

const invalidateRepository = (
  queryClient: QueryClient,
  describe: boolean
): void => {
  queryClient
    .invalidateQueries({
      predicate: (query) => {
        const key = query.queryKey;
        if (key[0] !== "repository") {
          return false;
        }
        return describe
          ? key[2] === "describe"
          : key[2] === "analysis" && key[3] === "current";
      },
      refetchType: "all",
    })
    .catch(() => undefined);
};

const ENDED_RING = 8;
let endedAnalyses: readonly string[] = [];

const rememberEnded = (analysisId: string): void => {
  endedAnalyses = [...endedAnalyses, analysisId].slice(-ENDED_RING);
};

export const resetEventState = (): void => {
  endedAnalyses = [];
};

const TERMINAL_EVENTS: readonly ProtocolEventName[] = [
  "analysis.completed",
  "analysis.failed",
  "analysis.cancelled",
];

const applyForwarded = (
  event: ProtocolEventName,
  payload: unknown,
  queryClient: QueryClient
): void => {
  const store = useAppStore.getState();

  if (event === "analysis.progress") {
    const { analysisId, ...tick } = payload as ProgressTick & {
      analysisId: string;
    };
    if (endedAnalyses.includes(analysisId)) {
      return;
    }
    store.applyProgress(tick);
    return;
  }

  if (event === "analysis.started") {
    const { analysisId } = payload as { analysisId: string };
    store.clearWarnings();
    invalidateRepository(queryClient, false);
    endedAnalyses = endedAnalyses.filter((id) => id !== analysisId);
    return;
  }

  if (TERMINAL_EVENTS.includes(event)) {
    const { analysisId } = payload as { analysisId: string };
    rememberEnded(analysisId);
    store.clearProgress();
    invalidateAnalysis(queryClient, queryKeys.analysis(analysisId));
    return;
  }

  if (event === "analysis.warning") {
    const warning = payload as { analysisId: string; error: never };
    store.appendWarning({
      analysisId: warning.analysisId,
      error: warning.error,
    });
    return;
  }

  if (event === "analysis.stage.completed") {
    const { analysisId, stage } = payload as {
      analysisId: string;
      stage: AnalysisStage;
    };
    const rule = STAGE_INVALIDATION[stage];
    if (rule.scope === "analysis") {
      invalidateAnalysis(queryClient, rule.key(analysisId));
    } else if (rule.scope === "repository") {
      invalidateRepository(queryClient, rule.describe);
    }
    return;
  }

  if (event === "cache.invalidated") {
    queryClient.clear();
  }
};

export const handleFrame = (
  frame: RawFrame,
  queryClient: QueryClient
): boolean => {
  if (frame.kind === "state") {
    const parsed = backendStateSchema.safeParse(frame.value);
    if (!parsed.success) {
      return false;
    }
    useAppStore.getState().setBackend(parsed.data);
    return true;
  }

  const envelope = forwardedFrameSchema.safeParse(frame.value);
  if (!envelope.success) {
    return false;
  }
  const payload = EVENTS[envelope.data.event].safeParse(envelope.data.payload);
  if (!payload.success) {
    return false;
  }
  applyForwarded(envelope.data.event, payload.data, queryClient);
  return true;
};

export const applyResolvedBackend = (resolved: BackendState): void => {
  if (resolved.phase === "STARTING") {
    return;
  }
  const store = useAppStore.getState();
  if (store.backend.phase !== "STARTING") {
    return;
  }
  store.setBackend(resolved);
};

export const primeBackendState = async (): Promise<void> => {
  applyResolvedBackend(await analyzerState());
};

export const subscribeToAnalyzer = (
  queryClient: QueryClient
): Promise<() => void> =>
  transport.subscribe((frame) => {
    handleFrame(frame, queryClient);
  });
