import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { ThemeProvider } from "@/components/theme-provider";
import {
  AnalysisChip,
  type AnalysisChipState,
  AppHeader,
  type AppHeaderProps,
} from "@/features/shell/app-header";
import { t } from "@/i18n/messages";
import { type BackendState, useAppStore } from "@/state/app-store";

const RIGHT_TO_LEFT_OVERRIDE = String.fromCodePoint(0x20_2e);
const initialStore = useAppStore.getState();

beforeEach(() => {
  useAppStore.setState(initialStore, true);
});

const renderHeader = (
  props: AppHeaderProps = {},
  backend: BackendState = { phase: "READY", protocolVersion: 1 }
) => {
  useAppStore.setState({ backend });
  return render(
    <ThemeProvider>
      <AppHeader {...props} />
    </ThemeProvider>
  );
};

const repository = (displayName: string | undefined, onClose = vi.fn()) => ({
  displayName,
  onClose,
});

describe("AppHeader: a tela inicial, sem repositório", () => {
  test("é o único banner, com a marca, o estado do analisador e o tema", () => {
    renderHeader();

    const banner = screen.getByRole("banner");
    expect(screen.getAllByRole("banner")).toHaveLength(1);
    expect(within(banner).getByText(t.app.name)).toBeDefined();
    expect(within(banner).getByText(t.backend.READY)).toBeDefined();
    expect(
      within(banner).getByRole("button", { name: t.theme.label })
    ).toBeDefined();
    for (const svg of banner.querySelectorAll("svg")) {
      expect(svg.getAttribute("aria-hidden")).toBe("true");
    }
  });

  test("não oferece fechar nem Reiniciar: o alerta de travamento da tela é o dono do botão", () => {
    renderHeader({}, { exitCode: 137, phase: "CRASHED" });

    expect(screen.queryByRole("button", { name: t.header.close })).toBeNull();
    expect(
      screen.queryByRole("button", { name: t.repository.restart })
    ).toBeNull();
    expect(screen.getByText(t.backend.CRASHED)).toBeDefined();
  });
});

describe("AppHeader: com um repositório aberto", () => {
  test('o nome vem saneado em um <bdi lang="" translate="no">, com o nome inteiro no title', () => {
    renderHeader({
      repository: repository(`orders${RIGHT_TO_LEFT_OVERRIDE}-service`),
    });

    const name = screen.getByText("orders-service");
    expect(name.tagName).toBe("BDI");
    expect(name.getAttribute("lang")).toBe("");
    expect(name.getAttribute("translate")).toBe("no");
    expect(name.getAttribute("title")).toBe("orders-service");
    expect(name.textContent).not.toContain(RIGHT_TO_LEFT_OVERRIDE);
    expect(name.className).not.toContain("font-mono");
  });

  test("enquanto o describe carrega não há nome, mas fechar já está lá", () => {
    renderHeader({ repository: repository(undefined) });

    expect(screen.getByRole("banner").querySelector("bdi")).toBeNull();
    expect(screen.getByRole("button", { name: t.header.close })).toBeDefined();
  });

  test('"Fechar repositório" chama onClose uma vez', () => {
    const onClose = vi.fn<() => void>();
    renderHeader({ repository: repository("orders-service", onClose) });

    fireEvent.click(screen.getByRole("button", { name: t.header.close }));

    expect(onClose).toHaveBeenCalledOnce();
  });

  test("com o analisador parado, o espaço de trabalho oferece Reiniciar no cabeçalho", () => {
    renderHeader(
      { repository: repository("orders-service") },
      { exitCode: 137, phase: "CRASHED" }
    );

    expect(
      screen.getByRole("button", { name: t.repository.restart })
    ).toBeDefined();
  });

  test("as paradas de Tab do cabeçalho pronto são fechar e o tema, nessa ordem", () => {
    renderHeader({ repository: repository("orders-service") });

    const stops = [
      ...screen.getByRole("banner").querySelectorAll<HTMLElement>("button"),
    ]
      .filter((button) => button.tabIndex >= 0)
      .map((button) => button.textContent);
    expect(stops).toStrictEqual([t.header.close, t.theme.label]);
  });
});

describe("AppHeader: os encaixes", () => {
  test("o estado da análise e a trilha moram dentro do banner", () => {
    renderHeader({
      repository: repository("orders-service"),
      status: <span>selo</span>,
      trail: <nav aria-label="trilha">nível</nav>,
    });

    const banner = screen.getByRole("banner");
    expect(within(banner).getByText("selo")).toBeDefined();
    expect(
      within(banner).getByRole("navigation", { name: "trilha" })
    ).toBeDefined();
  });

  test("a trilha vem depois do nome do repositório, separada por uma barra decorativa", () => {
    renderHeader({
      repository: repository("orders-service"),
      trail: <nav aria-label="trilha">nível</nav>,
    });

    const name = screen.getByText("orders-service");
    const separator = name.nextElementSibling;
    expect(separator?.getAttribute("aria-hidden")).toBe("true");
    expect(separator?.nextElementSibling).toBe(
      screen.getByRole("navigation", { name: "trilha" })
    );
  });

  test("sem trilha não sobra barra solta depois do nome", () => {
    renderHeader({ repository: repository("orders-service") });

    expect(screen.getByText("orders-service").nextElementSibling).toBeNull();
  });
});

describe("AnalysisChip", () => {
  const STATES = Object.keys(t.header.analysis) as AnalysisChipState[];

  test("cada estado diz o seu rótulo, e o ponto colorido fica fora da árvore acessível", () => {
    for (const state of STATES) {
      const view = render(<AnalysisChip state={state} />);
      const chip = view.getByText(t.header.analysis[state]);
      const dot = chip.querySelector("span");
      expect(dot?.getAttribute("aria-hidden")).toBe("true");
      expect(dot?.textContent).toBe("");
      view.unmount();
    }
  });

  test("no encaixe de estado, o selo vem antes do estado do analisador", () => {
    renderHeader({
      repository: repository("orders-service"),
      status: <AnalysisChip state="PARTIAL" />,
    });

    const chip = screen.getByText(t.header.analysis.PARTIAL);
    const analyzer = screen.getByText(t.backend.READY);
    expect(chip.compareDocumentPosition(analyzer)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING
    );
  });
});
