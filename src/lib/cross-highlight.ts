import { type RefObject, useCallback, useEffect, useRef } from "react";
import { useAppStore } from "@/state/app-store";

export interface CrossHighlight {
  readonly release: (elementId?: string) => void;

  readonly write: (elementId: string) => void;

  readonly written: () => string | null;
}

export const useCrossHighlight = (
  containerRef: RefObject<HTMLElement | null>,
  focusedIdOf: (element: Element) => string | null
): CrossHighlight => {
  const writtenRef = useRef<string | null>(null);

  const write = useCallback((elementId: string) => {
    writtenRef.current = elementId;
    useAppStore.getState().setActiveElementId(elementId);
  }, []);

  const release = useCallback((elementId?: string) => {
    const released = elementId ?? writtenRef.current;
    if (released === null) {
      return;
    }
    if (writtenRef.current === released) {
      writtenRef.current = null;
    }
    useAppStore.getState().releaseActiveElementId(released);
  }, []);

  const written = useCallback(() => writtenRef.current, []);

  useEffect(() => () => release(), [release]);

  useEffect(
    () =>
      useAppStore.subscribe((state, previous) => {
        if (
          state.activeElementId !== null ||
          previous.activeElementId === null
        ) {
          return;
        }
        const active = document.activeElement;
        const focused =
          active !== null && containerRef.current?.contains(active)
            ? focusedIdOf(active)
            : null;
        if (focused !== null) {
          write(focused);
        }
      }),
    [containerRef, focusedIdOf, write]
  );

  return { release, write, written };
};
