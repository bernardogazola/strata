import { getRouteApi } from "@tanstack/react-router";
import { useCallback } from "react";
import { cancelAnalysis, closeRepository } from "@/bridge/analyzer";
import { GESTURE_STATE } from "./focus-title";

const routeApi = getRouteApi("/repository/$handle");

export interface CloseRepositoryOptions {
  readonly cancelAnalysisId?: string;
}

export const useCloseRepository = (
  handle: string,
  { cancelAnalysisId }: CloseRepositoryOptions = {}
): (() => void) => {
  const navigate = routeApi.useNavigate();

  const close = useCallback(async () => {
    if (cancelAnalysisId !== undefined) {
      cancelAnalysis(cancelAnalysisId).catch(() => undefined);
    }
    try {
      await closeRepository(handle);
    } finally {
      await navigate({ state: GESTURE_STATE, to: "/" });
    }
  }, [cancelAnalysisId, handle, navigate]);

  return useCallback(() => {
    close().catch(() => undefined);
  }, [close]);
};
