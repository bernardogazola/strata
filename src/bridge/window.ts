import { getCurrentWindow } from "@tauri-apps/api/window";
import {
  isTauri,
  platformFromUserAgent,
  type WindowPlatform,
} from "./environment";

export const nativeWindowPlatform = (): WindowPlatform | null =>
  isTauri() ? platformFromUserAgent(navigator.userAgent) : null;

export interface WindowFrame {
  readonly fullscreen: boolean;
  readonly maximized: boolean;
}

export const watchWindowFrame = (
  listener: (frame: WindowFrame) => void
): (() => void) => {
  if (!isTauri()) {
    return () => undefined;
  }

  const lifetime = new AbortController();
  const sequence = { latest: 0 };
  try {
    const current = getCurrentWindow();
    const sync = async (): Promise<void> => {
      sequence.latest += 1;
      const request = sequence.latest;
      const [fullscreen, maximized] = await Promise.all([
        current.isFullscreen(),
        current.isMaximized(),
      ]);
      if (request === sequence.latest && !lifetime.signal.aborted) {
        listener({ fullscreen, maximized });
      }
    };

    const listen = async (): Promise<void> => {
      const stop = await current.onResized(() => {
        sync().catch(() => undefined);
      });
      if (lifetime.signal.aborted) {
        stop();
        return;
      }
      lifetime.signal.addEventListener("abort", stop);
      // Cobre uma maximização entre a primeira consulta e o registro do listener.
      await sync();
    };

    sync().catch(() => undefined);
    listen().catch(() => undefined);
  } catch {
    // Sem acesso à janela, a barra fica no estado de repouso.
  }
  return () => lifetime.abort();
};

export const minimizeWindow = (): void => {
  if (!isTauri()) {
    return;
  }

  try {
    getCurrentWindow()
      .minimize()
      .catch(() => undefined);
  } catch {
    // Falha ao acessar a janela não deve derrubar a aplicação.
  }
};

export const toggleMaximizeWindow = (): void => {
  if (!isTauri()) {
    return;
  }

  try {
    getCurrentWindow()
      .toggleMaximize()
      .catch(() => undefined);
  } catch {
    // Falha ao acessar a janela não deve derrubar a aplicação.
  }
};

export const closeWindow = (): void => {
  if (!isTauri()) {
    return;
  }

  try {
    getCurrentWindow()
      .close()
      .catch(() => undefined);
  } catch {
    // Falha ao acessar a janela não deve derrubar a aplicação.
  }
};

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
