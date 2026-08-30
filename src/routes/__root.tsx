import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Outlet } from "@tanstack/react-router";
import { TanStackRouterDevtools } from "@tanstack/react-router-devtools";

interface RootContext {
  readonly queryClient: QueryClient;
}

const RootLayout = () => (
  <>
    <Outlet />
    {import.meta.env.DEV ? <TanStackRouterDevtools /> : null}
  </>
);

export const Route = createRootRouteWithContext<RootContext>()({
  component: RootLayout,
});
