import { getRouteApi } from "@tanstack/react-router";
import { useCallback } from "react";
import { closeRepository } from "@/bridge/analyzer";

const routeApi = getRouteApi("/repository/$handle");

export const useCloseRepository = (handle: string): (() => void) => {
  const navigate = routeApi.useNavigate();

  const close = useCallback(async () => {
    try {
      await closeRepository(handle);
    } finally {
      await navigate({ to: "/" });
    }
  }, [handle, navigate]);

  return useCallback(() => {
    close().catch(() => undefined);
  }, [close]);
};
