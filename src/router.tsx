import type { QueryClient } from "@tanstack/react-query";
import { createRouter } from "@tanstack/react-router";
import { createQueryClient } from "@/queries/client";
import { routeTree } from "./routeTree.gen";

export interface RouterContext {
  readonly queryClient: QueryClient;
}

export const createAppRouter = (
  queryClient: QueryClient = createQueryClient()
) => {
  const router = createRouter({
    context: { queryClient },
    defaultPreload: "intent",
    defaultPreloadStaleTime: 0,
    routeTree,
    scrollRestoration: false,
  });

  return { queryClient, router };
};

export type AppRouter = ReturnType<typeof createAppRouter>["router"];

declare module "@tanstack/react-router" {
  interface Register {
    router: AppRouter;
  }
}
