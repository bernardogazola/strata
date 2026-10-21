import { render, screen, within } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import { AppShell } from "@/features/shell/app-shell";
import { t } from "@/i18n/messages";

const renderShell = () =>
  render(
    <AppShell
      header={<header>cabeçalho</header>}
      inspector={<p>inspetor</p>}
      main={<p>canvas</p>}
      sidebar={<p>lateral</p>}
    />
  );

describe("AppShell", () => {
  test("as quatro regiões são landmarks, e as duas <aside> têm nomes diferentes", () => {
    renderShell();

    expect(screen.getByRole("banner")).toBeDefined();
    expect(
      screen.getByRole("complementary", { name: t.shell.regions.sidebar })
    ).toBeDefined();
    expect(
      screen.getByRole("main", { name: t.shell.regions.canvas })
    ).toBeDefined();
    expect(
      screen.getByRole("complementary", { name: t.shell.regions.inspector })
    ).toBeDefined();
    expect(t.shell.regions.sidebar).not.toBe(t.shell.regions.inspector);
  });

  test("cada slot é desenhado dentro da sua região", () => {
    renderShell();

    expect(
      within(screen.getByRole("banner")).getByText("cabeçalho")
    ).toBeDefined();
    expect(
      within(
        screen.getByRole("complementary", { name: t.shell.regions.sidebar })
      ).getByText("lateral")
    ).toBeDefined();
    expect(
      within(
        screen.getByRole("main", { name: t.shell.regions.canvas })
      ).getByText("canvas")
    ).toBeDefined();
    expect(
      within(
        screen.getByRole("complementary", { name: t.shell.regions.inspector })
      ).getByText("inspetor")
    ).toBeDefined();
  });

  test("a barra lateral é uma coluna de altura limitada e não rola: quem rola é o filho", () => {
    renderShell();

    const classes = screen
      .getByRole("complementary", { name: t.shell.regions.sidebar })
      .className.split(" ");
    expect(classes).toStrictEqual(
      expect.arrayContaining(["flex", "flex-col", "min-h-0"])
    );
    expect(classes).not.toContain("overflow-y-auto");
  });

  test("há um <main> só", () => {
    renderShell();

    expect(screen.getAllByRole("main")).toHaveLength(1);
  });

  test("não embrulha o cabeçalho em um segundo <header>: o banner é um só", () => {
    renderShell();

    const banners = screen.getAllByRole("banner");
    expect(banners).toHaveLength(1);
    expect(banners[0]?.parentElement?.tagName).not.toBe("HEADER");
  });
});
