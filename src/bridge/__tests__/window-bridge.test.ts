import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { platformFromUserAgent } from "@/bridge/environment";
import {
  nativeWindowPlatform,
  type WindowFrame,
  watchWindowFrame,
} from "@/bridge/window";

interface Deferred<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
}

const deferred = <T>(): Deferred<T> => {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>((fulfil) => {
    resolve = fulfil;
  });
  return { promise, resolve };
};

const fakeWindow = {
  isFullscreen: vi.fn<() => Promise<boolean>>(),
  isMaximized: vi.fn<() => Promise<boolean>>(),
  onResized: vi.fn<(handler: () => void) => Promise<() => void>>(),
};

vi.mock("@tauri-apps/api/window", () => ({
  getCurrentWindow: () => fakeWindow,
}));

const INSIDE_TAURI = "__TAURI_INTERNALS__";

const enterTauri = (): void => {
  Object.defineProperty(window, INSIDE_TAURI, {
    configurable: true,
    value: {},
  });
};

const settle = (): Promise<void> =>
  new Promise((done) => {
    setTimeout(done, 0);
  });

beforeEach(() => {
  fakeWindow.isFullscreen.mockReset().mockResolvedValue(false);
  fakeWindow.isMaximized.mockReset().mockResolvedValue(false);
  fakeWindow.onResized.mockReset().mockResolvedValue(() => undefined);
});

afterEach(() => {
  Reflect.deleteProperty(window, INSIDE_TAURI);
  vi.restoreAllMocks();
});

describe("o sistema da janela, pelo user agent da WebView", () => {
  test.each([
    [
      "macos",
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)",
    ],
    [
      "windows",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
    ],
    [
      "linux",
      "Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
    ],
  ] as const)("%s", (expected, userAgent) => {
    expect(platformFromUserAgent(userAgent)).toBe(expected);
  });

  test("fora do Tauri não há janela nativa a imitar", () => {
    expect(nativeWindowPlatform()).toBeNull();
  });

  test("dentro do Tauri, o sistema do user agent", () => {
    enterTauri();
    vi.spyOn(navigator, "userAgent", "get").mockReturnValue(
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
    );

    expect(nativeWindowPlatform()).toBe("windows");
  });
});

describe("watchWindowFrame", () => {
  test("fora do Tauri não consulta a janela e devolve um retorno inerte", () => {
    const listener = vi.fn();

    const stop = watchWindowFrame(listener);
    stop();

    expect(fakeWindow.onResized).not.toHaveBeenCalled();
    expect(listener).not.toHaveBeenCalled();
  });

  test("avisa o estado ao começar e de novo a cada redimensionamento", async () => {
    enterTauri();
    let resized: () => void = () => undefined;
    fakeWindow.onResized.mockImplementation((handler) => {
      resized = handler;
      return Promise.resolve(() => undefined);
    });
    const frames: WindowFrame[] = [];

    watchWindowFrame((frame) => frames.push(frame));
    await settle();
    fakeWindow.isMaximized.mockResolvedValue(true);
    resized();
    await settle();

    expect(frames.at(0)).toStrictEqual({ fullscreen: false, maximized: false });
    expect(frames.at(-1)).toStrictEqual({ fullscreen: false, maximized: true });
  });

  test("uma consulta que volta depois da seguinte não desenha o estado de antes", async () => {
    enterTauri();
    let resized: () => void = () => undefined;
    fakeWindow.onResized.mockImplementation((handler) => {
      resized = handler;
      return Promise.resolve(() => undefined);
    });
    const frames: WindowFrame[] = [];
    watchWindowFrame((frame) => frames.push(frame));
    await settle();
    frames.length = 0;
    const slow = deferred<boolean>();
    fakeWindow.isMaximized
      .mockReturnValueOnce(slow.promise)
      .mockResolvedValueOnce(false);

    resized();
    resized();
    await settle();
    slow.resolve(true);
    await settle();

    expect(frames).toStrictEqual([{ fullscreen: false, maximized: false }]);
  });

  test("depois de parado, desliga o ouvinte e não avisa mais", async () => {
    enterTauri();
    const unlisten = vi.fn();
    fakeWindow.onResized.mockResolvedValue(unlisten);
    const listener = vi.fn();

    const stop = watchWindowFrame(listener);
    await settle();
    listener.mockClear();
    stop();
    await settle();

    expect(unlisten).toHaveBeenCalledOnce();
    expect(listener).not.toHaveBeenCalled();
  });
});
