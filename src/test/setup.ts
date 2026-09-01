import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

const PREFERS_DARK = "(prefers-color-scheme: dark)";

export const matchMediaStub =
  (prefersDark: boolean) =>
  (query: string): MediaQueryList => {
    const target = new EventTarget();
    const list = {
      addEventListener: target.addEventListener.bind(target),
      addListener: (
        listener: ((event: MediaQueryListEvent) => void) | null
      ) => {
        if (listener) {
          target.addEventListener("change", listener as EventListener);
        }
      },
      dispatchEvent: target.dispatchEvent.bind(target),
      matches: prefersDark && query === PREFERS_DARK,
      media: query,
      onchange: null,
      removeEventListener: target.removeEventListener.bind(target),
      removeListener: (
        listener: ((event: MediaQueryListEvent) => void) | null
      ) => {
        if (listener) {
          target.removeEventListener("change", listener as EventListener);
        }
      },
    };

    return list as unknown as MediaQueryList;
  };

// O Node 26 define um localStorage global que vale undefined sem --localstorage-file e
// esconde o do jsdom; por isso o armazenamento em memória.
const createStorage = (): Storage => {
  const entries = new Map<string, string>();
  return {
    clear: () => entries.clear(),
    getItem: (key: string) => entries.get(key) ?? null,
    key: (index: number) => [...entries.keys()][index] ?? null,
    get length() {
      return entries.size;
    },
    removeItem: (key: string) => {
      entries.delete(key);
    },
    setItem: (key: string, value: string) => {
      entries.set(key, String(value));
    },
  };
};

export const blockedStorageStub = (): Storage => {
  const blocked = (): never => {
    throw new DOMException(
      "os dados do site estão bloqueados",
      "SecurityError"
    );
  };
  return {
    clear: blocked,
    getItem: blocked,
    key: blocked,
    get length(): number {
      return blocked();
    },
    removeItem: blocked,
    setItem: blocked,
  };
};

Object.defineProperty(globalThis, "localStorage", {
  configurable: true,
  value: createStorage(),
  writable: true,
});

window.matchMedia = matchMediaStub(false);

afterEach(() => {
  // Com globals: false o Testing Library não registra o cleanup automático.
  cleanup();

  try {
    localStorage.clear();
  } catch {
    // Um teste pode ter deixado o dublê bloqueado; o unstubGlobals só roda depois deste hook.
  }
  document.documentElement.className = "";
  document.documentElement.style.colorScheme = "";
});
