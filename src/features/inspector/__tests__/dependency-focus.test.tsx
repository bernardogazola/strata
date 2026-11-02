import { act, fireEvent, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { DependencyTable } from "@/features/inspector/dependency-table";
import { t } from "@/i18n/messages";
import { useAppStore } from "@/state/app-store";
import { NEXT_CURSOR, pageOf } from "@/test/dependency-rows";
import {
  focusTargetOf,
  pressKey,
  renderWithQuery,
  rowWithLabel,
  settle,
  WORLD_ANALYSIS_ID,
} from "@/test/inspector";
import { stubTableLayout } from "@/test/virtual";
import {
  answerError,
  answerPage,
  CATALOG_ELEMENT,
  cursorsOf,
  findTable,
  foreignCursorRefusal,
  heldAnswer,
  inTotals,
  mockPaging,
  renderPagedTable,
  shownLabels,
  syntheticRows,
  timeoutError,
} from "./paging-transport";

const TALL_LAYOUT = { rowHeight: 40, viewport: { height: 1000, width: 280 } };

const PAGE_ONE_ROWS = 10;
const PAGE_TWO_ROWS = 5;
const TOTAL = PAGE_ONE_ROWS + PAGE_TWO_ROWS;

const pageOne = () =>
  answerPage(
    pageOf(syntheticRows(0, PAGE_ONE_ROWS), inTotals(TOTAL), NEXT_CURSOR)
  );

const pageTwo = () =>
  pageOf(syntheticRows(PAGE_ONE_ROWS, PAGE_TWO_ROWS), inTotals(TOTAL));

const focusRetry = async (table: HTMLElement): Promise<HTMLElement> => {
  const retry = await within(table).findByRole("button", {
    name: t.graph.retry,
  });
  act(() => {
    retry.focus();
  });
  return retry;
};

beforeEach(() => {
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("o foco depois de 'Tentar de novo' em uma página seguinte", () => {
  test("a página chega: o foco vai ao botão da primeira linha nova", async () => {
    stubTableLayout(TALL_LAYOUT);
    mockPaging(pageOne(), [answerError(timeoutError()), answerPage(pageTwo())]);
    renderPagedTable();
    const table = await findTable();
    const retry = await focusRetry(table);
    expect(document.activeElement).toBe(retry);

    pressKey(retry, "Enter");
    await waitFor(() => {
      expect(shownLabels(table)).toHaveLength(TOTAL);
    });
    await settle();

    expect(document.activeElement).toBe(
      focusTargetOf(rowWithLabel(table, `T${PAGE_ONE_ROWS}`))
    );
  });

  test("falha de novo: o foco fica no mesmo botão da cauda, que nunca saiu do DOM", async () => {
    stubTableLayout(TALL_LAYOUT);
    const request = mockPaging(pageOne(), [
      answerError(timeoutError()),
      answerError(timeoutError()),
    ]);
    renderPagedTable();
    const table = await findTable();
    const retry = await focusRetry(table);
    expect(document.activeElement).toBe(retry);

    pressKey(retry, "Enter");
    await waitFor(() => {
      expect(cursorsOf(request)).toHaveLength(3);
    });
    await settle();

    expect(document.activeElement).toBe(retry);
    expect(retry).toHaveAccessibleName(t.graph.retry);
  });

  test("enquanto a página vem, o foco fica no botão da cauda, indisponível e dizendo que carrega", async () => {
    stubTableLayout(TALL_LAYOUT);
    const held = heldAnswer();
    const request = mockPaging(pageOne(), [
      answerError(timeoutError()),
      held.answer,
    ]);
    renderPagedTable();
    const table = await findTable();
    const retry = await focusRetry(table);
    expect(document.activeElement).toBe(retry);

    pressKey(retry, "Enter");
    await waitFor(() => {
      expect(retry).toHaveAccessibleName(t.inspector.dependencies.loadingMore);
    });
    expect(document.activeElement).toBe(retry);
    expect(retry).toHaveAttribute("aria-disabled", "true");

    pressKey(retry, "Enter");
    await settle();
    expect(cursorsOf(request)).toStrictEqual([
      undefined,
      NEXT_CURSOR,
      NEXT_CURSOR,
    ]);

    act(() => {
      held.release(pageTwo());
    });
    await waitFor(() => {
      expect(document.activeElement).toBe(
        focusTargetOf(rowWithLabel(table, `T${PAGE_ONE_ROWS}`))
      );
    });
  });

  test("a nova tentativa recusada de vez: sem botão a focar, o foco vai à última linha carregada", async () => {
    stubTableLayout(TALL_LAYOUT);
    const request = mockPaging(pageOne(), [
      answerError(timeoutError()),
      answerError(foreignCursorRefusal()),
    ]);
    renderPagedTable();
    const table = await findTable();
    const retry = await focusRetry(table);
    expect(document.activeElement).toBe(retry);

    pressKey(retry, "Enter");
    await waitFor(() => {
      expect(cursorsOf(request)).toHaveLength(3);
    });
    await settle();

    expect(retry.isConnected).toBe(false);
    expect(document.activeElement).toBe(
      focusTargetOf(rowWithLabel(table, `T${PAGE_ONE_ROWS - 1}`))
    );
  });

  test("quem saiu da tabela durante o pedido não é puxado de volta, e a parada de tabulação vai à primeira linha nova", async () => {
    stubTableLayout(TALL_LAYOUT);
    const held = heldAnswer();
    const request = mockPaging(pageOne(), [
      answerError(timeoutError()),
      held.answer,
    ]);
    const { getByRole } = renderWithQuery(
      <>
        <button type="button">fora</button>
        <DependencyTable
          analysisId={WORLD_ANALYSIS_ID}
          direction="IN"
          element={CATALOG_ELEMENT}
          onReveal={vi.fn()}
        />
      </>
    );
    const table = await findTable();
    const retry = await focusRetry(table);
    expect(document.activeElement).toBe(retry);

    pressKey(retry, "Enter");
    await waitFor(() => {
      expect(cursorsOf(request)).toHaveLength(3);
    });
    const outside = getByRole("button", { name: "fora" });
    act(() => {
      outside.focus();
    });
    act(() => {
      held.release(pageTwo());
    });
    await waitFor(() => {
      expect(shownLabels(table)).toHaveLength(TOTAL);
    });
    await settle();

    expect(document.activeElement).toBe(outside);
    expect(
      focusTargetOf(rowWithLabel(table, `T${PAGE_ONE_ROWS}`))
    ).toHaveAttribute("tabindex", "0");
  });

  test("↓ na última linha carregada não entra na cauda que carrega e que ninguém focou", async () => {
    stubTableLayout(TALL_LAYOUT);
    const held = heldAnswer();
    mockPaging(pageOne(), [held.answer]);
    renderPagedTable();
    const table = await findTable();
    await within(table).findByRole("button", {
      name: t.inspector.dependencies.loadingMore,
    });
    const last = focusTargetOf(rowWithLabel(table, `T${PAGE_ONE_ROWS - 1}`));
    act(() => {
      last.focus();
    });

    pressKey(last, "ArrowDown");
    await settle();

    expect(document.activeElement).toBe(last);
  });
});

describe("a linha focada presa no intervalo", () => {
  test("continua montada, e focada, quando a rolagem a leva para longe", async () => {
    stubTableLayout({ rowHeight: 40, viewport: { height: 200, width: 280 } });
    const total = 200;
    mockPaging(
      answerPage(pageOf(syntheticRows(0, total), inTotals(total))),
      []
    );
    renderPagedTable();
    const table = await findTable();
    const first = focusTargetOf(rowWithLabel(table, "T0"));
    act(() => {
      first.focus();
    });

    const scroller = table.parentElement as HTMLElement;
    scroller.scrollTop = 4000;
    fireEvent.scroll(scroller);

    await waitFor(() => {
      expect(within(table).queryByText("T70")).not.toBeNull();
    });
    expect(within(table).queryByText("T0")).not.toBeNull();
    expect(document.activeElement).toBe(first);
  });
});
