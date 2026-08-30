import type { ReactNode } from "react";
import { ThemeProvider } from "@/components/theme-provider";

export default function AppProvider({ children }: { children: ReactNode }) {
  return <ThemeProvider>{children}</ThemeProvider>;
}
