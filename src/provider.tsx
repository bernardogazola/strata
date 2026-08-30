import { CSPProvider } from "@base-ui/react/csp-provider";
import type { QueryClient } from "@tanstack/react-query";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
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
  return (
    <CSPProvider nonce={cspNonce}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider>{children}</ThemeProvider>
      </QueryClientProvider>
    </CSPProvider>
  );
}
