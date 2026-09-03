import { getCurrentWindow } from "@tauri-apps/api/window";
import { isTauri } from "./environment";

export const revealWindow = (): void => {
  if (!isTauri()) {
    return;
  }

  try {
    const current = getCurrentWindow();
    current
      .show()
      .then(() => current.setFocus())
      .catch(() => undefined);
  } catch {
    // Falha ao acessar a janela não deve derrubar a aplicação.
  }
};

export const setNativeBackground = (color: string): void => {
  if (!isTauri()) {
    return;
  }

  try {
    getCurrentWindow()
      .setBackgroundColor(color)
      .catch(() => undefined);
  } catch {
    // Sem permissão, só a cor de fundo nativa deixa de acompanhar o tema.
  }
};
