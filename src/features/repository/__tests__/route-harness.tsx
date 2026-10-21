import { QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRouteWithContext,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
  stripSearchParams,
  useParams,
} from "@tanstack/react-router";
import { render } from "@testing-library/react";
import type { ReactNode } from "react";
import { ThemeProvider } from "@/components/theme-provider";
import {
  REPOSITORY_SEARCH_DEFAULTS,
  repositorySearchSchema,
} from "@/features/shell/repository-search";
import { createQueryClient } from "@/queries/client";
import type { RouterContext } from "@/router";

export const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";
export const OTHER_HANDLE = "repo_BBBBBBBBBBBBBBBBBBBBBA";

export interface Screens {
  readonly index: () => ReactNode;
  readonly repository: (handle: string) => ReactNode;
}

export const renderRoutes = (url: string, screens: Screens) => {
  const queryClient = createQueryClient();
  const rootRoute = createRootRouteWithContext<RouterContext>()({
    component: Outlet,
  });
  const indexRoute = createRoute({
    component: screens.index,
    getParentRoute: () => rootRoute,
    path: "/",
  });
  const RepositoryPage = () => {
    const { handle } = useParams({ from: "/repository/$handle" });
    return screens.repository(handle);
  };
  const repositoryRoute = createRoute({
    component: RepositoryPage,
    getParentRoute: () => rootRoute,
    path: "/repository/$handle",
    search: { middlewares: [stripSearchParams(REPOSITORY_SEARCH_DEFAULTS)] },
    validateSearch: repositorySearchSchema,
  });
  const router = createRouter({
    context: { queryClient },
    history: createMemoryHistory({ initialEntries: [url] }),
    routeTree: rootRoute.addChildren([indexRoute, repositoryRoute]),
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <RouterProvider router={router} />
      </ThemeProvider>
    </QueryClientProvider>
  );
  return { queryClient, router };
};
