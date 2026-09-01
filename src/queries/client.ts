import { QueryClient } from "@tanstack/react-query";

export const createQueryClient = (): QueryClient =>
  new QueryClient({
    defaultOptions: {
      mutations: {
        networkMode: "always",
        retry: false,
      },
      queries: {
        networkMode: "always",

        refetchOnMount: false,
        refetchOnReconnect: false,
        refetchOnWindowFocus: false,

        retry: false,

        staleTime: Number.POSITIVE_INFINITY,
      },
    },
  });
