import { render, screen } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { ThemeProvider, useTheme } from "@/components/theme-provider";
import { THEME_STORAGE_KEY } from "@/lib/theme";
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
