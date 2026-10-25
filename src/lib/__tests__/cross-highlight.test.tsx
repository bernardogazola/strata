import { act, render, renderHook } from "@testing-library/react";
import { createRef } from "react";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { useCrossHighlight } from "@/lib/cross-highlight";
import { useAppStore } from "@/state/app-store";

const ROW_ID = "type:br.pucpr.synthetic.Row";
const OTHER_ID = "type:br.pucpr.synthetic.Other";

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
