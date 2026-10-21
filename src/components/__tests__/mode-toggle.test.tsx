import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import { ModeToggle } from "@/components/mode-toggle";
import { ThemeProvider } from "@/components/theme-provider";
import { THEME_STORAGE_KEY } from "@/lib/theme";

const renderToggle = () => {
  render(
    <ThemeProvider>
      <ModeToggle />
    </ThemeProvider>
  );
  return screen.getByRole("button", { name: "Alternar tema" });
};

describe("ModeToggle: estado fechado", () => {
  test("o único nome acessível do gatilho é o rótulo sr-only", () => {
    const trigger = renderToggle();
    expect(trigger.tagName).toBe("BUTTON");
    expect(trigger.getAttribute("aria-haspopup")).toBe("menu");
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
  });

  test("o ícone é o do tema em uso: lua no escuro, sol no claro", () => {
    localStorage.setItem(THEME_STORAGE_KEY, "dark");
    const dark = renderToggle();
    expect(dark.querySelector(".tabler-icon-moon")).not.toBeNull();
    expect(dark.querySelector(".tabler-icon-sun")).toBeNull();
    cleanup();

    localStorage.setItem(THEME_STORAGE_KEY, "light");
    const light = renderToggle();
    expect(light.querySelector(".tabler-icon-sun")).not.toBeNull();
    expect(light.querySelector(".tabler-icon-moon")).toBeNull();
  });
});

describe("ModeToggle: navegação por teclado", () => {
  test("ArrowDown abre o menu com as três opções, na ordem", async () => {
    const trigger = renderToggle();
    trigger.focus();
    fireEvent.keyDown(trigger, { key: "ArrowDown" });

    const items = await screen.findAllByRole("menuitemradio");
    expect(items.map((item) => item.textContent)).toEqual([
      "Claro",
      "Escuro",
      "Sistema",
    ]);
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
  });

  test("a opção corrente é a marcada", async () => {
    const trigger = renderToggle();
    trigger.focus();
    fireEvent.keyDown(trigger, { key: "ArrowDown" });

    const current = await screen.findByRole("menuitemradio", {
      name: "Sistema",
    });
    expect(current.getAttribute("aria-checked")).toBe("true");
  });

  test("escolher Escuro aplica o tema e o persiste", async () => {
    const trigger = renderToggle();
    trigger.focus();
    fireEvent.keyDown(trigger, { key: "ArrowDown" });

    fireEvent.click(
      await screen.findByRole("menuitemradio", { name: "Escuro" })
    );

    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
  });
});
