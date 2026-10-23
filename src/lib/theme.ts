import { setNativeBackground } from "@/bridge/window";

export type Theme = "dark" | "light" | "system";
export type ResolvedTheme = "dark" | "light";

export const THEME_STORAGE_KEY = "vite-ui-theme";

// Tem de bater com global.css, o <style> inline do index.html e BG_LIGHT/BG_DARK em lib.rs;
// cada um atua em um momento do carregamento, e juntos evitam o flash de tema.
export const BACKGROUND_COLORS: Record<ResolvedTheme, string> = {
  dark: "#111111",
  light: "#ffffff",
};

export const readStoredTheme = (storageKey: string): Theme | null => {
  let stored: string | null = null;
  try {
    stored = localStorage.getItem(storageKey);
  } catch {
    return null;
  }
  return stored === "dark" || stored === "light" || stored === "system"
    ? stored
    : null;
};

export const writeStoredTheme = (storageKey: string, theme: Theme): void => {
  try {
    localStorage.setItem(storageKey, theme);
  } catch {
    // Sem armazenamento, o tema vale só para esta sessão.
  }
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

export type ResolvedThemeListener = (resolved: ResolvedTheme) => void;

const resolvedThemeListeners = new Set<ResolvedThemeListener>();

// O canvas assina aqui em vez de usar um efeito: o React roda os efeitos dos filhos antes
// dos do pai, e o efeito leria a paleta antiga antes de o ThemeProvider trocar a classe.
export const onResolvedThemeApplied = (
  listener: ResolvedThemeListener
): (() => void) => {
  resolvedThemeListeners.add(listener);
  return () => {
    resolvedThemeListeners.delete(listener);
  };
};

export const notifyResolvedThemeApplied = (resolved: ResolvedTheme): void => {
  for (const listener of resolvedThemeListeners) {
    listener(resolved);
  }
};
