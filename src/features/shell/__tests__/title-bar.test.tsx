import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { WindowPlatform } from "@/bridge/environment";
import {
  closeWindow,
  minimizeWindow,
  nativeWindowPlatform,
  toggleMaximizeWindow,
  type WindowFrame,
  watchWindowFrame,
} from "@/bridge/window";
import { ThemeProvider } from "@/components/theme-provider";
import { AppHeader } from "@/features/shell/app-header";
import { t } from "@/i18n/messages";
import { useAppStore } from "@/state/app-store";

vi.mock("@/bridge/window", () => ({
  closeWindow: vi.fn(),
  minimizeWindow: vi.fn(),
  nativeWindowPlatform: vi.fn(() => null),
  revealWindow: vi.fn(),
  setNativeBackground: vi.fn(),
  toggleMaximizeWindow: vi.fn(),
  watchWindowFrame: vi.fn(() => () => undefined),
}));

const initialStore = useAppStore.getState();

beforeEach(() => {
  useAppStore.setState(initialStore, true);
  useAppStore.setState({ backend: { phase: "READY", protocolVersion: 1 } });
});

afterEach(() => {
  vi.clearAllMocks();
  vi.restoreAllMocks();
});

const onPlatform = (
  platform: WindowPlatform | null,
  frame: WindowFrame = { fullscreen: false, maximized: false }
): void => {
  vi.mocked(nativeWindowPlatform).mockReturnValue(platform);
  vi.mocked(watchWindowFrame).mockImplementation((listener) => {
    listener(frame);
    return () => undefined;
  });
};

const renderHeader = () =>
  render(
    <ThemeProvider>
      <AppHeader
        repository={{ displayName: "orders-service", onClose: vi.fn() }}
      />
    </ThemeProvider>
  );

const WINDOW_LABELS: readonly string[] = Object.values(t.header.window);

const windowButtons = (banner: HTMLElement): HTMLElement[] =>
  within(banner)
    .queryAllByRole("button")
    .filter((button) =>
      WINDOW_LABELS.includes(button.getAttribute("aria-label") ?? "")
    );

describe("a barra de título em toda plataforma", () => {
  test.each([null, "linux", "macos", "windows"] as const)(
    "%s: a barra inteira arrasta a janela",
    (platform) => {
      onPlatform(platform);

      renderHeader();

      expect(
        screen.getByRole("banner").getAttribute("data-tauri-drag-region")
      ).toBe("deep");
    }
  );

  test("no navegador não há botões de janela nem lugar reservado", () => {
    onPlatform(null);

    renderHeader();

    const banner = screen.getByRole("banner");
    expect(windowButtons(banner)).toStrictEqual([]);
    expect(banner.querySelector("[data-traffic-lights]")).toBeNull();
  });
});

describe("macOS: os botões são os do sistema", () => {
  test("a barra reserva o lugar deles antes da marca e não desenha botão de janela", () => {
    onPlatform("macos");

    renderHeader();

    const banner = screen.getByRole("banner");
    const reserved = banner.querySelector("[data-traffic-lights]");
    expect(reserved?.getAttribute("aria-hidden")).toBe("true");
    expect(reserved?.nextElementSibling?.tagName.toLowerCase()).toBe("svg");
    expect(windowButtons(banner)).toStrictEqual([]);
  });

  test("em tela cheia os botões somem, e o lugar deles também", () => {
    onPlatform("macos", { fullscreen: true, maximized: false });

    renderHeader();

    expect(
      screen.getByRole("banner").querySelector("[data-traffic-lights]")
    ).toBeNull();
  });
});

describe.each(["linux", "windows"] as const)(
  "%s: a barra desenha os botões",
  (platform) => {
    test("minimizar, maximizar e fechar, nessa ordem, depois do tema", () => {
      onPlatform(platform);

      renderHeader();

      const banner = screen.getByRole("banner");
      expect(
        windowButtons(banner).map((button) => button.getAttribute("aria-label"))
      ).toStrictEqual([
        t.header.window.minimize,
        t.header.window.maximize,
        t.header.window.close,
      ]);
      const buttons = within(banner).getAllByRole("button");
      expect(buttons.at(-4)?.textContent).toBe(t.theme.label);
    });

    test("fora da ordem do Tab, com nome e dica, como os nativos", () => {
      onPlatform(platform);

      renderHeader();

      for (const button of windowButtons(screen.getByRole("banner"))) {
        expect(button.tabIndex).toBe(-1);
        expect(button.getAttribute("title")).toBe(
          button.getAttribute("aria-label")
        );
      }
    });

    test("cada botão chama a operação da janela", () => {
      onPlatform(platform);
      renderHeader();

      fireEvent.click(
        screen.getByRole("button", { name: t.header.window.minimize })
      );
      fireEvent.click(
        screen.getByRole("button", { name: t.header.window.maximize })
      );
      fireEvent.click(
        screen.getByRole("button", { name: t.header.window.close })
      );

      expect(minimizeWindow).toHaveBeenCalledOnce();
      expect(toggleMaximizeWindow).toHaveBeenCalledOnce();
      expect(closeWindow).toHaveBeenCalledOnce();
    });

    test("maximizada, o botão do meio restaura", () => {
      onPlatform(platform, { fullscreen: false, maximized: true });

      renderHeader();

      expect(
        screen.getByRole("button", { name: t.header.window.restore })
      ).toBeDefined();
      expect(
        screen.queryByRole("button", { name: t.header.window.maximize })
      ).toBeNull();
    });

    test("apertar um botão não tira o foco de quem o tinha", () => {
      onPlatform(platform);
      renderHeader();

      const pressed = fireEvent.mouseDown(
        screen.getByRole("button", { name: t.header.window.maximize })
      );

      expect(pressed).toBe(false);
    });
  }
);

describe("a janela sem foco apaga o título", () => {
  test("na janela nativa, a barra marca a perda de foco e a volta dele", () => {
    onPlatform("windows");
    const hasFocus = vi.spyOn(document, "hasFocus").mockReturnValue(true);
    renderHeader();
    const banner = screen.getByRole("banner");
    expect(banner.hasAttribute("data-inactive")).toBe(false);

    hasFocus.mockReturnValue(false);
    fireEvent.blur(window);
    expect(banner.hasAttribute("data-inactive")).toBe(true);

    hasFocus.mockReturnValue(true);
    fireEvent.focus(window);
    expect(banner.hasAttribute("data-inactive")).toBe(false);
  });

  test("no navegador, nunca: a aba não sabe que a janela perdeu o foco", () => {
    onPlatform(null);
    vi.spyOn(document, "hasFocus").mockReturnValue(false);

    renderHeader();

    expect(screen.getByRole("banner").hasAttribute("data-inactive")).toBe(
      false
    );
  });
});
