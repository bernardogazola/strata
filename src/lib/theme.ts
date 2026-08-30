import { setNativeBackground } from "@/bridge/window";

export type Theme = "dark" | "light" | "system";
export type ResolvedTheme = "dark" | "light";

export const THEME_STORAGE_KEY = "vite-ui-theme";

export const BACKGROUND_COLORS: Record<ResolvedTheme, string> = {
  dark: "#0A0A0A",
  light: "#f6f6f6",
};

export const getSystemTheme = (): ResolvedTheme =>
  window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";

export const resolveTheme = (theme: Theme): ResolvedTheme =>
  theme === "system" ? getSystemTheme() : theme;

export const applyResolvedTheme = (resolved: ResolvedTheme): void => {
  const root = document.documentElement;
  root.classList.toggle("dark", resolved === "dark");
  root.style.colorScheme = resolved;
};

export const syncNativeBackground = (resolved: ResolvedTheme): void => {
  setNativeBackground(BACKGROUND_COLORS[resolved]);
};
