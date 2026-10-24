import {
  defaultRangeExtractor,
  type Range,
  useVirtualizer,
} from "@tanstack/react-virtual";
import {
  type FocusEvent,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
  type Ref,
  type RefObject,
  useCallback,
  useEffect,
  useId,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { protocolErrorOf } from "@/bridge/analyzer";
import { SECONDARY_ACTION_CLASS } from "@/components/action-styles";
import { Button } from "@/components/ui/button";
import { type ErrorMessage, formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import type { ElementKind } from "@/protocol/enums";
import type { GraphViewParams } from "@/queries/options";
import { useAppStore } from "@/state/app-store";
import {
  EMPTY_TYPEAHEAD,
  extendTypeahead,
  type FocusTarget,
  followTarget,
  intentOf,
  isTypeaheadKey,
  type KeyIntent,
  pinIndexes,
  resolveTabbable,
  resolveTarget,
  toggleIntent,
  typeaheadIntent,
} from "./outline-navigation";
import { OutlineTreeRow, ROW_HEIGHT } from "./outline-row";
import { type OutlineRow, useOutline } from "./use-outline";

export interface OutlineHandle {
  readonly focus: () => void;
}

export interface ArchitectureOutlineProps {
  readonly analysisId: string;

  readonly focus: string | null;

  readonly onCollapse: () => void;

  readonly onExpand: (packageId: string) => void;

  readonly onSelect: (elementId: string, kind: ElementKind) => void;
  readonly ref?: Ref<OutlineHandle>;

  readonly view: GraphViewParams;
}

const OVERSCAN = 8;

const estimateRowSize = (): number => ROW_HEIGHT;

const measureRow = (
  element: Element,
  entry: ResizeObserverEntry | undefined
): number => {
  const height =
    entry?.borderBoxSize[0]?.blockSize ??
    element.getBoundingClientRect().height;
  return height > 0 ? Math.round(height) : ROW_HEIGHT;
};

const rowIndexOf = (target: EventTarget | null): number | null => {
  if (!(target instanceof Element)) {
    return null;
  }
  const row = target.closest<HTMLElement>("[data-index]");
  return row === null ? null : Number(row.dataset.index);
};

const elementIdOf = (target: EventTarget | null): string | null => {
  if (!(target instanceof Element)) {
    return null;
  }
  return (
    target.closest<HTMLElement>("[data-element-id]")?.dataset.elementId ?? null
  );
};

const refusalOf = (error: Error | null): ErrorMessage | null => {
  if (error === null) {
    return null;
  }
  const protocolError = protocolErrorOf(error);
  return protocolError.code === "ELEMENT_NOT_FOUND"
    ? formatProtocolError(protocolError)
    : null;
};

const useCrossHighlight = (treeRef: RefObject<HTMLDivElement | null>) => {
  const written = useRef<string | null>(null);
  const write = useCallback((elementId: string) => {
    written.current = elementId;
    useAppStore.getState().setActiveElementId(elementId);
  }, []);
  const release = useCallback(() => {
    const elementId = written.current;
    written.current = null;
    if (elementId !== null) {
      useAppStore.getState().releaseActiveElementId(elementId);
    }
  }, []);
  useEffect(() => release, [release]);
  useEffect(
    () =>
      useAppStore.subscribe((state, previous) => {
        if (
          state.activeElementId !== null ||
          previous.activeElementId === null
        ) {
          return;
        }
        const focused = treeRef.current?.contains(document.activeElement)
          ? elementIdOf(document.activeElement)
          : null;
        if (focused !== null) {
          write(focused);
        }
      }),
    [treeRef, write]
  );
  return { release, write };
};

interface OutlineTreeProps {
  readonly busy: boolean;

  readonly childrenRefusal: ErrorMessage | null;
  readonly expandedId: string | null;
  readonly focus: string | null;
  readonly headingId: string;

  readonly hintId: string;

  readonly onCollapse: () => void;
  readonly onExpand: (packageId: string) => void;
  readonly onLoadMore: (parentId: string | null) => void;
  readonly onRetry: (parentId: string | null) => void;
  readonly onSelect: (elementId: string, kind: ElementKind) => void;
  readonly rows: readonly OutlineRow[];
  readonly treeRef: RefObject<HTMLDivElement | null>;
  readonly view: GraphViewParams;
}

function OutlineTree({
  busy,
  childrenRefusal,
  expandedId,
  focus,
  headingId,
  hintId,
  onCollapse,
  onExpand,
  onLoadMore,
  onRetry,
  onSelect,
  rows,
  treeRef,
  view,
}: OutlineTreeProps) {
  const { release, write } = useCrossHighlight(treeRef);

  const [activeKey, setActiveKey] = useState<string | null>(null);
  const [containsFocus, setContainsFocus] = useState(false);
  const [focusTarget, setFocusTarget] = useState<FocusTarget | null>(null);
  const typeahead = useRef(EMPTY_TYPEAHEAD);

  const viewKey = `${view.level}:${view.parentId ?? ""}`;
  const refocusFrom = useRef<string | null>(null);

  const indexByKey = useMemo(
    () => new Map(rows.map((row, index) => [row.key, index])),
    [rows]
  );
  const tabbableIndex = resolveTabbable(rows, indexByKey, [
    containsFocus ? activeKey : null,
    focus,
    expandedId,
  ]);
  const targetIndex =
    focusTarget === null ? null : resolveTarget(focusTarget, rows, indexByKey);

  const rangeExtractor = useCallback(
    (range: Range) =>
      pinIndexes(defaultRangeExtractor(range), [tabbableIndex, targetIndex]),
    [tabbableIndex, targetIndex]
  );
  const getItemKey = useCallback(
    (index: number) => rows[index]?.key ?? index,
    [rows]
  );
  const virtualizer = useVirtualizer({
    count: rows.length,
    estimateSize: estimateRowSize,
    getItemKey,
    getScrollElement: () => treeRef.current,
    measureElement: measureRow,
    overscan: OVERSCAN,
    rangeExtractor,
  });

  useLayoutEffect(() => {
    if (targetIndex === null) {
      return;
    }
    const element = treeRef.current?.querySelector<HTMLElement>(
      `[data-index="${targetIndex}"]`
    );
    if (element === null || element === undefined) {
      return;
    }
    element.focus({ preventScroll: true });
    setFocusTarget(null);
  }, [targetIndex, treeRef]);

  useLayoutEffect(() => {
    if (focusTarget === null) {
      return;
    }
    const followed = followTarget(focusTarget, rows, indexByKey);
    if (followed !== focusTarget) {
      setFocusTarget(followed);
    }
  }, [focusTarget, indexByKey, rows]);

  useLayoutEffect(() => {
    const armedFrom = refocusFrom.current;
    if (armedFrom === null || armedFrom === viewKey) {
      return;
    }
    refocusFrom.current = null;
    if (document.activeElement !== document.body || tabbableIndex === null) {
      return;
    }
    treeRef.current
      ?.querySelector<HTMLElement>(`[data-index="${tabbableIndex}"]`)
      ?.focus({ preventScroll: true });
  }, [tabbableIndex, treeRef, viewKey]);

  useLayoutEffect(() => {
    if (containsFocus && !treeRef.current?.contains(document.activeElement)) {
      setContainsFocus(false);
      release();
    }
  });

  useEffect(() => {
    if (!containsFocus && tabbableIndex !== null) {
      virtualizer.scrollToIndex(tabbableIndex, { align: "auto" });
    }
  }, [containsFocus, tabbableIndex, virtualizer]);

  const armRefocus = useCallback(() => {
    refocusFrom.current = viewKey;
  }, [viewKey]);

  const activate = useCallback(
    (index: number) => {
      const row = rows[index];
      if (row?.type === "node") {
        if (row.node.kind === "PACKAGE" && view.level !== "PACKAGE") {
          armRefocus();
        }
        onSelect(row.node.id, row.node.kind);
      } else if (row?.type === "more") {
        const previous = rows[index - 1];
        if (previous !== undefined) {
          setFocusTarget({ after: previous.key, waitingOn: row.key });
        }
        onLoadMore(row.parentId);
      } else if (row?.type === "error") {
        if (row.parentId !== null && childrenRefusal !== null) {
          return;
        }
        const previous = rows[index - 1];
        if (previous !== undefined) {
          setFocusTarget({ after: previous.key, waitingOn: row.key });
        }
        onRetry(row.parentId);
      }
    },
    [
      armRefocus,
      childrenRefusal,
      onLoadMore,
      onRetry,
      onSelect,
      rows,
      view.level,
    ]
  );

  const run = useCallback(
    (intent: KeyIntent, index: number) => {
      switch (intent.type) {
        case "move": {
          const row = rows[intent.index];
          if (row !== undefined) {
            setFocusTarget({ key: row.key });
            virtualizer.scrollToIndex(intent.index, { align: "auto" });
          }
          return;
        }
        case "expand":
          armRefocus();
          onExpand(intent.packageId);
          return;
        case "collapse":
          armRefocus();
          onCollapse();
          return;
        default:
          activate(index);
      }
    },
    [activate, armRefocus, onCollapse, onExpand, rows, virtualizer]
  );

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      const index = rowIndexOf(event.target);
      if (index === null) {
        return;
      }
      let intent: KeyIntent | null;
      if (isTypeaheadKey(event)) {
        typeahead.current = extendTypeahead(
          typeahead.current,
          event.key,
          Date.now()
        );
        intent = typeaheadIntent(rows, index, typeahead.current);
      } else {
        intent = intentOf(event.key, rows, index, expandedId);
      }
      if (intent === null) {
        return;
      }
      event.preventDefault();
      run(intent, index);
    },
    [expandedId, rows, run]
  );

  const handleClick = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      const index = rowIndexOf(event.target);
      const row = index === null ? undefined : rows[index];
      if (index === null || row === undefined) {
        return;
      }
      const onChevron =
        event.target instanceof Element &&
        event.target.closest("[data-chevron]") !== null;
      if (onChevron && row.type === "node") {
        const intent = toggleIntent(row, expandedId);
        if (intent !== null) {
          run(intent, index);
        }
        return;
      }
      activate(index);
    },
    [activate, expandedId, rows, run]
  );

  const handleFocus = useCallback(
    (event: FocusEvent<HTMLDivElement>) => {
      const index = rowIndexOf(event.target);
      const row = index === null ? undefined : rows[index];
      if (row === undefined) {
        return;
      }
      setContainsFocus(true);
      setActiveKey(row.key);
      if (row.type === "node") {
        write(row.node.id);
      } else {
        release();
      }
    },
    [release, rows, write]
  );

  const handleBlur = useCallback(
    (event: FocusEvent<HTMLDivElement>) => {
      const { relatedTarget } = event;
      if (event.currentTarget.contains(relatedTarget)) {
        return;
      }
      setContainsFocus(false);
      release();
      if (relatedTarget !== null) {
        setFocusTarget(null);
      }
    },
    [release]
  );

  const handleMouseOver = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      const elementId = elementIdOf(event.target);
      if (elementId !== null) {
        write(elementId);
      }
    },
    [write]
  );

  const handleMouseOut = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      const tree = event.currentTarget;
      const { relatedTarget } = event;
      if (
        relatedTarget instanceof Node &&
        tree.contains(relatedTarget) &&
        elementIdOf(relatedTarget) !== null
      ) {
        return;
      }
      const focused = tree.contains(document.activeElement)
        ? elementIdOf(document.activeElement)
        : null;
      if (focused === null) {
        release();
      } else {
        write(focused);
      }
    },
    [release, write]
  );

  return (
    <div
      aria-busy={busy}
      aria-describedby={hintId}
      aria-labelledby={headingId}
      className="-mx-1 min-h-0 flex-1 overflow-y-auto px-1"
      onBlur={handleBlur}
      onClick={handleClick}
      onFocus={handleFocus}
      onKeyDown={handleKeyDown}
      onMouseOut={handleMouseOut}
      onMouseOver={handleMouseOver}
      ref={treeRef}
      role="tree"
    >
      <div
        className="relative w-full"
        role="none"
        style={{ height: virtualizer.getTotalSize() }}
      >
        {virtualizer.getVirtualItems().map(({ index, start }) => {
          const row = rows[index];
          return row === undefined ? null : (
            <OutlineTreeRow
              focus={focus}
              index={index}
              key={row.key}
              measureRef={virtualizer.measureElement}
              refusal={
                row.type === "error" && row.parentId !== null
                  ? childrenRefusal
                  : null
              }
              row={row}
              start={start}
              tabbable={index === tabbableIndex}
            />
          );
        })}
      </div>
    </div>
  );
}

function RootFailure({
  error,
  onRetry,
}: {
  readonly error: Error;
  readonly onRetry: () => void;
}) {
  const message = formatProtocolError(protocolErrorOf(error));
  return (
    <div className="flex flex-col items-start gap-3 px-2">
      <div className="flex flex-col gap-1" role="alert">
        <p className="font-medium text-sm">{message.title}</p>
        {message.hint === undefined ? null : (
          <p className="text-muted-foreground text-sm">{message.hint}</p>
        )}
      </div>
      {message.retryable ? (
        <Button
          className={cn(SECONDARY_ACTION_CLASS, "shadow-control")}
          onClick={onRetry}
          size="sm"
          variant="outline"
        >
          {t.graph.retry}
        </Button>
      ) : null}
    </div>
  );
}

function RootStatus({ text }: { readonly text: string }) {
  return (
    <p className="px-2 py-1.5 text-muted-foreground text-sm" role="status">
      {text}
    </p>
  );
}

export function ArchitectureOutline({
  analysisId,
  focus,
  onCollapse,
  onExpand,
  onSelect,
  ref,
  view,
}: ArchitectureOutlineProps): ReactNode {
  const headingId = useId();
  const hintId = useId();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const treeRef = useRef<HTMLDivElement>(null);
  const outline = useOutline(analysisId, view, focus);
  const { childrenError, retry, rootError, rootStatus, rows } = outline;
  const childrenRefusal = useMemo(
    () => refusalOf(childrenError),
    [childrenError]
  );

  useImperativeHandle(
    ref,
    () => ({
      focus: () => {
        const row = treeRef.current?.querySelector<HTMLElement>(
          '[role="treeitem"][tabindex="0"]'
        );
        (row ?? headingRef.current)?.focus();
      },
    }),
    []
  );

  const retryRoot = useCallback(() => retry(null), [retry]);

  let body: ReactNode;
  if (rows.length > 0) {
    body = (
      <>
        <p className="px-2 pb-2 text-muted-foreground text-xs" id={hintId}>
          {t.outline.keyboardHint}
        </p>
        <OutlineTree
          busy={outline.busy}
          childrenRefusal={childrenRefusal}
          expandedId={outline.expandedId}
          focus={focus}
          headingId={headingId}
          hintId={hintId}
          onCollapse={onCollapse}
          onExpand={onExpand}
          onLoadMore={outline.loadMore}
          onRetry={retry}
          onSelect={onSelect}
          rows={rows}
          treeRef={treeRef}
          view={view}
        />
      </>
    );
  } else if (rootStatus === "error" && rootError !== null) {
    body = <RootFailure error={rootError} onRetry={retryRoot} />;
  } else if (rootStatus === "pending") {
    body = <RootStatus text={t.outline.loading} />;
  } else {
    body = <RootStatus text={t.graph.empty.PACKAGE} />;
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <h2
        className="rounded-sm px-2 pt-1.5 pb-1 font-medium text-muted-foreground text-xs"
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
      >
        {t.outline.title}
      </h2>
      {body}
    </div>
  );
}
