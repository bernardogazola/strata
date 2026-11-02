import { QueryClientProvider } from "@tanstack/react-query";
import { act, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { transport } from "@/bridge/transport";
import { DependencyPanel } from "@/features/inspector/dependency-panel";
import { countPhrase } from "@/features/repository/count-phrase";
import { t } from "@/i18n/messages";
import type { DependencyDirection } from "@/protocol/enums";
import type { DependencyPage } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { pageOf } from "@/test/dependency-rows";
import { detailOf } from "@/test/fixture-graph";
import {
  asRendered,
  renderWithQuery,
  settle,
  WORLD_ANALYSIS_ID,
} from "@/test/inspector";
import { stubTableLayout } from "@/test/virtual";
import {
  CATALOG_ELEMENT,
  heldAnswer,
  mockPaging,
  syntheticRows,
} from "./paging-transport";

const GRAPH = detailOf(CATALOG_ELEMENT.elementId).graph;

const pageFor = (direction: DependencyDirection): DependencyPage => {
  const count = direction === "OUT" ? GRAPH.fanOut : GRAPH.fanIn;
  return pageOf(syntheticRows(0, count), {
    in: GRAPH.fanIn,
    out: GRAPH.fanOut,
    outExternal: 0,
  });
};

const panelOf = (direction: DependencyDirection) => (
  <DependencyPanel
    analysisId={WORLD_ANALYSIS_ID}
    direction={direction}
    element={CATALOG_ELEMENT}
    graph={GRAPH}
    onReveal={vi.fn()}
    onSelectDirection={vi.fn()}
  />
);

const summarySkeletons = (): Element[] => {
  const panel = screen.getByRole("radiogroup").parentElement as HTMLElement;
  return [...panel.querySelectorAll(':scope > [data-slot="skeleton"]')];
};

const summaryText = (direction: DependencyDirection): string =>
  countPhrase(
    direction === "OUT" ? GRAPH.fanOut : GRAPH.fanIn,
    t.inspector.dependencies.summary.internal.TYPE
  );

beforeEach(() => {
  stubTableLayout();
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("a região de status do painel", () => {
  test("está montada antes e depois da espera, e só o texto dela muda", async () => {
    const held = heldAnswer();
    mockPaging(held.answer, []);
    renderWithQuery(panelOf("OUT"));

    const status = screen.getByRole("status");
    await waitFor(() => {
      expect(status).toHaveTextContent(t.inspector.dependencies.loading);
    });

    act(() => {
      held.release(pageFor("OUT"));
    });
    await screen.findByRole("table");
    await settle();

    expect(screen.getByRole("status")).toBe(status);
    expect(status.textContent).toBe("");
  });

  test("troca de direção: a região continua a mesma e volta a dizer que carrega", async () => {
    const outbound = heldAnswer();
    const inbound = heldAnswer();
    vi.spyOn(transport, "request").mockImplementation(
      (_method: string, payload: unknown) =>
        (payload as { direction: DependencyDirection }).direction === "OUT"
          ? outbound.answer()
          : inbound.answer()
    );
    const { queryClient, rerender } = renderWithQuery(panelOf("OUT"));
    const status = screen.getByRole("status");
    act(() => {
      outbound.release(pageFor("OUT"));
    });
    await screen.findByRole("table");
    await settle();
    expect(status.textContent).toBe("");

    rerender(
      <QueryClientProvider client={queryClient}>
        {panelOf("IN")}
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(status).toHaveTextContent(t.inspector.dependencies.loading);
    });
    expect(screen.getByRole("status")).toBe(status);
  });
});

describe("a linha da frase enquanto a primeira página vem", () => {
  test("'Depende de' de um tipo: a barra fantasma ocupa a linha até a frase chegar", async () => {
    const held = heldAnswer();
    mockPaging(held.answer, []);
    renderWithQuery(panelOf("OUT"));

    expect(summarySkeletons()).toHaveLength(1);

    act(() => {
      held.release(pageFor("OUT"));
    });
    await screen.findByRole("table");

    expect(summarySkeletons()).toHaveLength(0);
    expect(
      screen.getByText(
        (_, element) =>
          element?.tagName === "P" &&
          asRendered(element.textContent ?? "") ===
            asRendered(summaryText("OUT"))
      )
    ).toBeInTheDocument();
  });

  test("'Usado por': a frase já está lá pelo describe, e nenhuma barra a reserva", () => {
    const held = heldAnswer();
    mockPaging(held.answer, []);
    renderWithQuery(panelOf("IN"));

    expect(summarySkeletons()).toHaveLength(0);
    expect(
      screen.getByText(
        (_, element) =>
          element?.tagName === "P" &&
          asRendered(element.textContent ?? "") ===
            asRendered(summaryText("IN"))
      )
    ).toBeInTheDocument();
  });
});
