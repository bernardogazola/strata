import {
  act,
  fireEvent,
  render,
  renderHook,
  screen,
} from "@testing-library/react";
import { createRef, useRef } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
  useCrossHighlight,
  useRowHighlightHandlers,
} from "@/lib/cross-highlight";
import { useAppStore } from "@/state/app-store";

const ROW_ID = "type:br.pucpr.synthetic.Row";
const OTHER_ID = "type:br.pucpr.synthetic.Other";
const NEXT_ID = "type:br.pucpr.synthetic.Next";

const highlightIdOf = (element: Element): string | null =>
  element.closest<HTMLElement>("[data-highlight-id]")?.dataset.highlightId ??
  null;

beforeEach(() => {
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  useAppStore.setState({ activeElementId: null });
});

const renderHighlight = () => {
  const containerRef = createRef<HTMLDivElement>();
  const hook = renderHook(() => useCrossHighlight(containerRef, highlightIdOf));
  return { containerRef, ...hook };
};

describe("useCrossHighlight", () => {
  test("escreve o id, lembra dele, e soltar sem argumento solta o que escreveu", () => {
    const { result } = renderHighlight();

    act(() => {
      result.current.write(ROW_ID);
    });
    expect(useAppStore.getState().activeElementId).toBe(ROW_ID);
    expect(result.current.written()).toBe(ROW_ID);

    act(() => {
      result.current.release();
    });
    expect(useAppStore.getState().activeElementId).toBeNull();
    expect(result.current.written()).toBeNull();
  });

  test("não apaga o destaque que outro escritor pôs depois", () => {
    const { result } = renderHighlight();
    act(() => {
      result.current.write(ROW_ID);
      useAppStore.getState().setActiveElementId(OTHER_ID);
    });

    act(() => {
      result.current.release();
    });

    expect(useAppStore.getState().activeElementId).toBe(OTHER_ID);
  });

  test("desmontar solta o que este escritor escreveu", () => {
    const { result, unmount } = renderHighlight();
    act(() => {
      result.current.write(ROW_ID);
    });

    unmount();

    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("outro escritor que solta o próprio destaque devolve o campo à linha focada daqui", () => {
    const containerRef = createRef<HTMLDivElement>();
    render(
      <div ref={containerRef}>
        <button data-highlight-id={ROW_ID} type="button">
          linha
        </button>
      </div>
    );
    const { result } = renderHook(() =>
      useCrossHighlight(containerRef, highlightIdOf)
    );
    const row = containerRef.current?.querySelector("button");
    act(() => {
      row?.focus();
      result.current.write(ROW_ID);
    });

    act(() => {
      useAppStore.getState().setActiveElementId(OTHER_ID);
    });
    act(() => {
      useAppStore.getState().releaseActiveElementId(OTHER_ID);
    });

    expect(useAppStore.getState().activeElementId).toBe(ROW_ID);
  });
});

interface HarnessRow {
  readonly highlightId: string | null;
  readonly label: string;
}

const ROW: HarnessRow = { highlightId: ROW_ID, label: "linha" };
const NEXT: HarnessRow = { highlightId: NEXT_ID, label: "próxima" };
const EXTERNAL: HarnessRow = { highlightId: null, label: "externa" };
const ROWS: readonly HarnessRow[] = [ROW, NEXT, EXTERNAL];

const keyOf = (element: Element): string | null =>
  element.closest<HTMLElement>("[data-row-key]")?.dataset.rowKey ?? null;

interface RowListProps {
  readonly onFocusLeave?: (relatedTarget: EventTarget | null) => void;
  readonly rows: readonly HarnessRow[];
}

function RowList({ onFocusLeave, rows }: RowListProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const { activeKey, containsFocus, ...handlers } = useRowHighlightHandlers(
    containerRef,
    highlightIdOf,
    { keyOf, onFocusLeave }
  );
  return (
    <>
      <div aria-label="linhas" ref={containerRef} role="toolbar" {...handlers}>
        {rows.map((row) => (
          <button
            data-highlight-id={row.highlightId ?? undefined}
            data-row-key={row.label}
            key={row.label}
            type="button"
          >
            {row.label}
          </button>
        ))}
        <button type="button">fora das linhas</button>
      </div>
      <output
        data-active-key={activeKey ?? ""}
        data-contains-focus={containsFocus ? "true" : "false"}
      />
      <button type="button">fora da lista</button>
    </>
  );
}

const button = (name: string): HTMLElement =>
  screen.getByRole("button", { name });

const focusState = () => {
  const output = document.querySelector("output");
  return {
    activeKey: output?.dataset.activeKey,
    containsFocus: output?.dataset.containsFocus === "true",
  };
};

const activeElementId = (): string | null =>
  useAppStore.getState().activeElementId;

const recordActiveIds = () => {
  const history: (string | null)[] = [];
  const stop = useAppStore.subscribe((state, previous) => {
    if (state.activeElementId !== previous.activeElementId) {
      history.push(state.activeElementId);
    }
  });
  return { history, stop };
};

describe("useRowHighlightHandlers", () => {
  test("focar uma linha que destaca escreve o id dela e a torna a linha ativa", () => {
    render(<RowList rows={ROWS} />);

    act(() => button(ROW.label).focus());

    expect(activeElementId()).toBe(ROW_ID);
    expect(focusState()).toStrictEqual({
      activeKey: ROW.label,
      containsFocus: true,
    });
  });

  test("focar uma linha que não destaca não escreve nada e solta o que a lista escreveu", () => {
    render(<RowList rows={ROWS} />);
    act(() => button(ROW.label).focus());

    act(() => button(EXTERNAL.label).focus());

    expect(activeElementId()).toBeNull();
    expect(focusState()).toStrictEqual({
      activeKey: EXTERNAL.label,
      containsFocus: true,
    });
  });

  test("focar uma linha que não destaca não apaga o destaque que outro escritor pôs", () => {
    render(<RowList rows={ROWS} />);
    act(() => button(ROW.label).focus());
    act(() => {
      useAppStore.getState().setActiveElementId(OTHER_ID);
    });

    act(() => button(EXTERNAL.label).focus());

    expect(activeElementId()).toBe(OTHER_ID);
  });

  test("um foco dentro da lista mas fora das linhas não conta", () => {
    render(<RowList rows={ROWS} />);

    act(() => button("fora das linhas").focus());

    expect(activeElementId()).toBeNull();
    expect(focusState()).toStrictEqual({ activeKey: "", containsFocus: false });
  });

  test("o foco que passa de uma linha a outra não solta no caminho nem avisa a saída", () => {
    const onFocusLeave = vi.fn();
    render(<RowList onFocusLeave={onFocusLeave} rows={ROWS} />);
    const { history, stop } = recordActiveIds();

    act(() => button(ROW.label).focus());
    act(() => button(NEXT.label).focus());
    stop();

    expect(history).toStrictEqual([ROW_ID, NEXT_ID]);
    expect(onFocusLeave).not.toHaveBeenCalled();
  });

  test("o foco que sai da lista solta o destaque e avisa quem o recebeu", () => {
    const onFocusLeave = vi.fn();
    render(<RowList onFocusLeave={onFocusLeave} rows={ROWS} />);
    act(() => button(ROW.label).focus());
    const outside = button("fora da lista");

    act(() => outside.focus());

    expect(activeElementId()).toBeNull();
    expect(focusState()).toStrictEqual({
      activeKey: ROW.label,
      containsFocus: false,
    });
    expect(onFocusLeave).toHaveBeenCalledTimes(1);
    expect(onFocusLeave).toHaveBeenCalledWith(outside);
  });

  test("o foco que sai da lista não apaga o destaque que outro escritor pôs", () => {
    render(<RowList rows={ROWS} />);
    act(() => button(ROW.label).focus());
    act(() => {
      useAppStore.getState().setActiveElementId(OTHER_ID);
    });

    act(() => button("fora da lista").focus());

    expect(activeElementId()).toBe(OTHER_ID);
  });

  test("um blur sem destino solta o destaque e avisa com null", () => {
    const onFocusLeave = vi.fn();
    render(<RowList onFocusLeave={onFocusLeave} rows={ROWS} />);
    act(() => button(ROW.label).focus());

    act(() => button(ROW.label).blur());

    expect(activeElementId()).toBeNull();
    expect(onFocusLeave).toHaveBeenCalledWith(null);
  });

  test("o ponteiro sobre uma linha que destaca escreve, e sobre uma que não destaca não muda nada", () => {
    render(<RowList rows={ROWS} />);

    fireEvent.mouseOver(button(ROW.label));
    expect(activeElementId()).toBe(ROW_ID);

    fireEvent.mouseOver(button(EXTERNAL.label));
    expect(activeElementId()).toBe(ROW_ID);
  });

  test("o ponteiro que passa a outra linha que destaca não solta no caminho", () => {
    render(<RowList rows={ROWS} />);
    const { history, stop } = recordActiveIds();

    fireEvent.mouseOver(button(ROW.label));
    fireEvent.mouseOut(button(ROW.label), {
      relatedTarget: button(NEXT.label),
    });
    fireEvent.mouseOver(button(NEXT.label));
    stop();

    expect(history).toStrictEqual([ROW_ID, NEXT_ID]);
  });

  test("o ponteiro que sai das linhas devolve o destaque direto à linha que tem o foco", () => {
    render(<RowList rows={ROWS} />);
    act(() => button(NEXT.label).focus());
    fireEvent.mouseOver(button(ROW.label));
    const { history, stop } = recordActiveIds();

    fireEvent.mouseOut(button(ROW.label), {
      relatedTarget: button("fora da lista"),
    });
    stop();

    expect(history).toStrictEqual([NEXT_ID]);
  });

  test("o ponteiro que sai para uma linha que não destaca, sem foco em uma que destaca, solta", () => {
    render(<RowList rows={ROWS} />);
    act(() => button(EXTERNAL.label).focus());
    fireEvent.mouseOver(button(ROW.label));

    fireEvent.mouseOut(button(ROW.label), {
      relatedTarget: button(EXTERNAL.label),
    });

    expect(activeElementId()).toBeNull();
  });

  test("o ponteiro que sai das linhas não apaga o destaque que outro escritor pôs", () => {
    render(<RowList rows={ROWS} />);
    fireEvent.mouseOver(button(ROW.label));
    act(() => {
      useAppStore.getState().setActiveElementId(OTHER_ID);
    });

    fireEvent.mouseOut(button(ROW.label), {
      relatedTarget: button("fora da lista"),
    });

    expect(activeElementId()).toBe(OTHER_ID);
  });

  test("a linha focada que sai do DOM solta o destaque, e a lista deixa de se dizer focada", () => {
    const { rerender } = render(<RowList rows={ROWS} />);
    act(() => button(ROW.label).focus());

    rerender(<RowList rows={[NEXT, EXTERNAL]} />);

    expect(document.activeElement).toBe(document.body);
    expect(activeElementId()).toBeNull();
    expect(focusState()).toStrictEqual({
      activeKey: ROW.label,
      containsFocus: false,
    });
  });
});
