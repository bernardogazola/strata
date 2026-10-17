import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { useCallback, useEffect } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { ThemeProvider, useTheme } from "@/components/theme-provider";
import { onResolvedThemeApplied, THEME_STORAGE_KEY } from "@/lib/theme";
import { blockedStorageStub, matchMediaStub } from "@/test/setup";

function ThemeProbe() {
  const { resolvedTheme, theme } = useTheme();
  return <output data-testid="probe">{`${theme}/${resolvedTheme}`}</output>;
}

const renderProbe = () =>
  render(
    <ThemeProvider>
      <ThemeProbe />
    </ThemeProvider>
  );

const probeText = () => screen.getByTestId("probe").textContent;

describe("ThemeProvider", () => {
  test("sem preferência salva, segue a preferência do sistema por claro", () => {
    renderProbe();
    expect(probeText()).toBe("system/light");
    expect(document.documentElement.classList.contains("dark")).toBe(false);
    expect(document.documentElement.style.colorScheme).toBe("light");
  });

  test("sem preferência salva, segue a preferência do sistema por escuro", () => {
    vi.stubGlobal("matchMedia", matchMediaStub(true));
    renderProbe();
    expect(probeText()).toBe("system/dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(document.documentElement.style.colorScheme).toBe("dark");
  });

  test("a preferência salva vence a do sistema", () => {
    vi.stubGlobal("matchMedia", matchMediaStub(true));
    localStorage.setItem(THEME_STORAGE_KEY, "light");
    renderProbe();
    expect(probeText()).toBe("light/light");
    expect(document.documentElement.classList.contains("dark")).toBe(false);
  });

  test("valor corrompido no armazenamento cai no padrão, não quebra", () => {
    localStorage.setItem(THEME_STORAGE_KEY, "roxo");
    renderProbe();
    expect(probeText()).toBe("system/light");
  });

  test("armazenamento bloqueado não derruba o render", () => {
    vi.stubGlobal("localStorage", blockedStorageStub());
    expect(() => renderProbe()).not.toThrow();
    expect(probeText()).toBe("system/light");
  });

  test("armazenamento bloqueado não derruba a troca de tema", () => {
    vi.stubGlobal("localStorage", blockedStorageStub());
    renderProbe();
    expect(() => screen.getByTestId("probe")).not.toThrow();
  });

  test("useTheme fora do provedor falha alto", () => {
    expect(() => render(<ThemeProbe />)).toThrow(
      "useTheme precisa ser usado dentro de um ThemeProvider"
    );
  });
});

const GLOBAL_CSS = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../global.css"
);

const TOKEN_BLOCKS = /^(?::root|\.dark) \{[^}]*\}/gm;

const LABEL_TOKEN = "--graph-node-label";
const LIGHT_LABEL = "#171717";
const DARK_LABEL = "#fafafa";

const injectedStyles: HTMLStyleElement[] = [];

const installThemeTokens = async (): Promise<void> => {
  const css = await readFile(GLOBAL_CSS, "utf8");
  const style = document.createElement("style");
  style.textContent = (css.match(TOKEN_BLOCKS) ?? []).join("\n");
  document.head.append(style);
  injectedStyles.push(style);
};

afterEach(() => {
  for (const style of injectedStyles.splice(0)) {
    style.remove();
  }
});

const readLabelToken = (): string =>
  getComputedStyle(document.documentElement)
    .getPropertyValue(LABEL_TOKEN)
    .trim();

function SinkListener({ reads }: { readonly reads: string[] }) {
  useEffect(
    () =>
      onResolvedThemeApplied(() => {
        reads.push(readLabelToken());
      }),
    [reads]
  );
  return null;
}

function ThemeKeyedEffect({ reads }: { readonly reads: string[] }) {
  const { resolvedTheme } = useTheme();
  useEffect(() => {
    reads.push(`${resolvedTheme}:${readLabelToken()}`);
  }, [resolvedTheme, reads]);
  return null;
}

function DarkSwitch() {
  const { setTheme } = useTheme();
  const switchToDark = useCallback(() => setTheme("dark"), [setTheme]);
  return (
    <button onClick={switchToDark} type="button">
      dark
    </button>
  );
}

describe("o terceiro consumidor do tema", () => {
  test("os blocos de token chegaram ao documento, senão os testes abaixo passariam vazios", async () => {
    await installThemeTokens();
    expect(readLabelToken()).toBe(LIGHT_LABEL);
    document.documentElement.classList.add("dark");
    expect(readLabelToken()).toBe(DARK_LABEL);
  });

  test("o ouvinte do sink lê a paleta escura ao trocar para escuro", async () => {
    await installThemeTokens();
    const reads: string[] = [];
    render(
      <ThemeProvider>
        <SinkListener reads={reads} />
        <DarkSwitch />
      </ThemeProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: "dark" }));

    expect(reads).toStrictEqual([LIGHT_LABEL, DARK_LABEL]);
  });

  test("um efeito filho keyed no tema lê a paleta antiga, por isso o ouvinte existe", async () => {
    await installThemeTokens();
    const reads: string[] = [];
    render(
      <ThemeProvider>
        <ThemeKeyedEffect reads={reads} />
        <DarkSwitch />
      </ThemeProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: "dark" }));

    expect(reads).toStrictEqual([
      `light:${LIGHT_LABEL}`,
      `dark:${LIGHT_LABEL}`,
    ]);
  });

  test("uma troca de tema do sistema também chega ao ouvinte, depois da classe", async () => {
    await installThemeTokens();
    const state = { prefersDark: false };
    const lists: MediaQueryList[] = [];
    vi.stubGlobal("matchMedia", (query: string) => {
      const list = matchMediaStub(state.prefersDark)(query);
      lists.push(list);
      return list;
    });
    const reads: string[] = [];
    render(
      <ThemeProvider>
        <SinkListener reads={reads} />
      </ThemeProvider>
    );

    state.prefersDark = true;
    act(() => {
      for (const list of lists) {
        list.dispatchEvent(new Event("change"));
      }
    });

    expect(reads).toStrictEqual([LIGHT_LABEL, DARK_LABEL]);
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  test("um ouvinte desmontado não é mais chamado", async () => {
    await installThemeTokens();
    const reads: string[] = [];
    const { rerender } = render(
      <ThemeProvider>
        <SinkListener reads={reads} />
        <DarkSwitch />
      </ThemeProvider>
    );
    rerender(
      <ThemeProvider>
        <DarkSwitch />
      </ThemeProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: "dark" }));

    expect(reads).toStrictEqual([LIGHT_LABEL]);
  });
});
