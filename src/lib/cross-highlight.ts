import {
  type Dispatch,
  type FocusEvent,
  type MouseEvent,
  type RefObject,
  type SetStateAction,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
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

export interface RowHighlightOptions {
  readonly keyOf: (target: Element) => string | null;

  readonly onFocusLeave?: (relatedTarget: EventTarget | null) => void;
}

export interface RowHighlight {
  readonly activeKey: string | null;

  readonly containsFocus: boolean;
  readonly onBlur: (event: FocusEvent<Element>) => void;
  readonly onFocus: (event: FocusEvent<Element>) => void;
  readonly onMouseOut: (event: MouseEvent<Element>) => void;
  readonly onMouseOver: (event: MouseEvent<Element>) => void;

  readonly setActiveKey: Dispatch<SetStateAction<string | null>>;
}

export const useRowHighlightHandlers = (
  containerRef: RefObject<HTMLElement | null>,
  idOf: (element: Element) => string | null,
  { keyOf, onFocusLeave }: RowHighlightOptions
): RowHighlight => {
  const { release, write } = useCrossHighlight(containerRef, idOf);
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const [containsFocus, setContainsFocus] = useState(false);

  useLayoutEffect(() => {
    if (
      containsFocus &&
      !containerRef.current?.contains(document.activeElement)
    ) {
      setContainsFocus(false);
      release();
    }
  });

  const onFocus = useCallback(
    (event: FocusEvent<Element>) => {
      const { target } = event;
      const key = target instanceof Element ? keyOf(target) : null;
      if (key === null) {
        return;
      }
      setContainsFocus(true);
      setActiveKey(key);
      const highlightId = idOf(target);
      if (highlightId === null) {
        release();
      } else {
        write(highlightId);
      }
    },
    [idOf, keyOf, release, write]
  );

  const onBlur = useCallback(
    (event: FocusEvent<Element>) => {
      const { relatedTarget } = event;
      if (containerRef.current?.contains(relatedTarget)) {
        return;
      }
      setContainsFocus(false);
      release();
      onFocusLeave?.(relatedTarget);
    },
    [containerRef, onFocusLeave, release]
  );

  const onMouseOver = useCallback(
    (event: MouseEvent<Element>) => {
      const { target } = event;
      const highlightId = target instanceof Element ? idOf(target) : null;
      if (highlightId !== null) {
        write(highlightId);
      }
    },
    [idOf, write]
  );

  const onMouseOut = useCallback(
    (event: MouseEvent<Element>) => {
      const container = containerRef.current;
      const { relatedTarget } = event;
      if (
        relatedTarget instanceof Element &&
        container?.contains(relatedTarget) &&
        idOf(relatedTarget) !== null
      ) {
        return;
      }
      const active = document.activeElement;
      const focused =
        active !== null && container?.contains(active) ? idOf(active) : null;
      if (focused === null) {
        release();
      } else {
        write(focused);
      }
    },
    [containerRef, idOf, release, write]
  );

  return {
    activeKey,
    containsFocus,
    onBlur,
    onFocus,
    onMouseOut,
    onMouseOver,
    setActiveKey,
  };
};
