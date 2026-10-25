import {
  act,
  fireEvent,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { transport } from "@/bridge/transport";
import { formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";
import { useAppStore } from "@/state/app-store";
import { CURSORS, NEXT_CURSOR, pageOf } from "@/test/dependency-rows";
import {
  focusTargetOf,
  rowWithLabel,
  settle,
  WORLD_ANALYSIS_ID,
} from "@/test/inspector";
import { stubTableLayout } from "@/test/virtual";
import {
  answerError,
  answerPage,
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

const SHORT_LAYOUT = { rowHeight: 40, viewport: { height: 200, width: 280 } };

const JSDOM_ROW_HEIGHT = 56;

const failedTailOf = async (table: HTMLElement): Promise<HTMLElement> => {
  const message = await within(table).findByText(
    t.inspector.dependencies.failedMore
  );
  return message.closest("tr") as HTMLElement;
};

beforeEach(() => {
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("a cauda de uma página seguinte que falhou", () => {
  test("uma falha passageira mostra o título do registro e oferece tentar de novo", async () => {
    stubTableLayout(TALL_LAYOUT);
    const failure = timeoutError();
    mockPaging(
      answerPage(pageOf(syntheticRows(0, 10), inTotals(15), NEXT_CURSOR)),
      [answerError(failure)]
    );
    renderPagedTable();
    const table = await findTable();

    const tail = await failedTailOf(table);
    await settle();

    const { title } = formatProtocolError(failure.protocolError);
    expect(within(tail).getByText(title)).toBeInTheDocument();
    expect(
      within(tail).getByRole("button", { name: t.graph.retry })
    ).toBeInTheDocument();
  });

  test("uma recusa determinística mostra o título e não oferece tentar de novo", async () => {
    stubTableLayout(TALL_LAYOUT);
    const refusal = foreignCursorRefusal();
    const request = mockPaging(
      answerPage(pageOf(syntheticRows(0, 10), inTotals(15), NEXT_CURSOR)),
      [answerError(refusal)]
    );
    renderPagedTable();
    const table = await findTable();

    const tail = await failedTailOf(table);
    await settle();

    const message = formatProtocolError(refusal.protocolError);
    expect(message.retryable).toBe(false);
    expect(within(tail).getByText(message.title)).toBeInTheDocument();
    expect(within(tail).queryByRole("button")).toBeNull();
    expect(shownLabels(table)).toHaveLength(10);
    expect(cursorsOf(request)).toStrictEqual([undefined, NEXT_CURSOR]);
  });
});

describe("uma linha repetida entre páginas", () => {
  test("é o erro de contrato na cauda: nada aparece duas vezes, e nada se pede de novo", async () => {
    stubTableLayout(TALL_LAYOUT);
    const errors = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    const request = mockPaging(
      answerPage(pageOf(syntheticRows(0, 10), inTotals(15), NEXT_CURSOR)),
      [answerPage(pageOf(syntheticRows(9, 5), inTotals(15)))]
    );
    renderPagedTable();
    const table = await findTable();

    const tail = await failedTailOf(table);
    await settle();

    expect(tail.textContent).toBe(t.inspector.dependencies.failedMore);
    expect(within(tail).queryByRole("button")).toBeNull();
    expect(shownLabels(table)).toStrictEqual(
      syntheticRows(0, 10).map((entry) => entry.label)
    );
    expect(cursorsOf(request)).toStrictEqual([undefined, NEXT_CURSOR]);
    expect(
      errors.mock.calls.filter((call) => String(call[0]).includes("same key"))
    ).toStrictEqual([]);
  });
});

describe("a atualização que falha em uma lista toda carregada", () => {
  test("aparece acima da tabela, como a falha da primeira página, e nunca como cauda", async () => {
    stubTableLayout(TALL_LAYOUT);
    const total = 3;
    const request = vi
      .spyOn(transport, "request")
      .mockResolvedValue(pageOf(syntheticRows(0, total), inTotals(total)));
    const { queryClient } = renderPagedTable();
    const table = await findTable();
    await within(table).findByText(`T${total - 1}`);

    request.mockRejectedValueOnce(timeoutError());
    await act(async () => {
      await queryClient
        .invalidateQueries({
          queryKey: ["analysis", WORLD_ANALYSIS_ID],
          refetchType: "all",
        })
        .catch(() => undefined);
    });

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(t.inspector.dependencies.failed);
    expect(table).toHaveAttribute("aria-rowcount", String(total + 1));
    expect(shownLabels(table)).toHaveLength(total);
    expect(
      within(table).queryByText(t.inspector.dependencies.failedMore)
    ).toBeNull();
    expect(
      within(table).queryByRole("button", { name: t.graph.retry })
    ).toBeNull();

    const calls = request.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: t.graph.retry }));
    await waitFor(() => {
      expect(screen.queryByRole("alert")).toBeNull();
    });
    expect(request.mock.calls.length).toBe(calls + 1);
  });
});

describe("a página seguinte pelo intervalo à vista", () => {
  test("a linha presa perto do fim não pede página: só o intervalo que se vê", async () => {
    stubTableLayout(SHORT_LAYOUT);
    const firstPage = 30;
    const total = 60;
    const held = heldAnswer();
    const request = mockPaging(
      answerPage(
        pageOf(syntheticRows(0, firstPage), inTotals(total), NEXT_CURSOR)
      ),
      [
        held.answer,
        answerPage(pageOf(syntheticRows(firstPage + 1, 5), inTotals(total))),
      ]
    );
    renderPagedTable();
    const table = await findTable();
    await within(table).findByText("T0");
    const scroller = table.parentElement as HTMLElement;

    scroller.scrollTop = firstPage * JSDOM_ROW_HEIGHT;
    fireEvent.scroll(scroller);
    await waitFor(() => {
      expect(cursorsOf(request)).toHaveLength(2);
    });
    const pinned = focusTargetOf(
      await waitFor(() => rowWithLabel(table, `T${firstPage - 1}`))
    );
    act(() => {
      pinned.focus();
    });

    scroller.scrollTop = 0;
    fireEvent.scroll(scroller);
    await within(table).findByText("T0");
    expect(within(table).queryByText("T20")).toBeNull();
    expect(within(table).queryByText(`T${firstPage - 1}`)).not.toBeNull();

    act(() => {
      held.release(
        pageOf(syntheticRows(firstPage, 1), inTotals(total), CURSORS[1])
      );
    });
    await settle();

    expect(cursorsOf(request)).toStrictEqual([undefined, NEXT_CURSOR]);
    expect(document.activeElement).toBe(pinned);
  });
});
