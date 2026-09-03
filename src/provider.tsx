import { CSPProvider } from "@base-ui/react/csp-provider";
import type { QueryClient } from "@tanstack/react-query";
import { QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode, useEffect } from "react";
import { primeBackendState, subscribeToAnalyzer } from "@/bridge/events";
import { ThemeProvider } from "@/components/theme-provider";
import { readCspNonce } from "@/lib/csp";

interface AppProviderProps {
  readonly children: ReactNode;
  readonly queryClient: QueryClient;
}

const cspNonce = readCspNonce();

export default function AppProvider({
  children,
  queryClient,
}: AppProviderProps) {
  useEffect(() => {
    let disposed = false;
    let unsubscribe: (() => void) | undefined;

    const install = async () => {
      try {
        const stop = await subscribeToAnalyzer(queryClient);
        if (disposed) {
          stop();
          return;
        }
        unsubscribe = stop;
      } finally {
        await primeBackendState().catch(() => undefined);
      }
    };
    install().catch(() => undefined);

    return () => {
      disposed = true;
      unsubscribe?.();
    };
  }, [queryClient]);

  return (
    <CSPProvider nonce={cspNonce}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider>{children}</ThemeProvider>
      </QueryClientProvider>
    </CSPProvider>
  );
}
