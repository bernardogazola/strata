import {
  defaultRangeExtractor,
  measureElement,
  type Range,
  useVirtualizer,
  type Virtualizer,
} from "@tanstack/react-virtual";
import {
  type FocusEvent,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import type { RevealTarget } from "@/features/shell/repository-search";
import { t } from "@/i18n/messages";
import { useCrossHighlight } from "@/lib/cross-highlight";
import { pinIndexes } from "@/lib/virtual-range";
import type { DependencyDirection } from "@/protocol/enums";
import {
  type RowContext,
  type RowControlHandlers,
  renderDependencyRow,
  withSpacers,
} from "./dependency-row";
import type { InspectedKind } from "./inspector-model";
import { DependenciesFailure, LoadingFirstPage } from "./inspector-states";
import {
  type FocusTarget,
  highlightIdOf,
  highlightIdOfTarget,
  isFocusable,
  keyTargetOf,
  resolveTarget,
  rowIndexOf,
} from "./table-navigation";
import {
  type Dependencies,
  type DependencyTableRow,
  rowKeyOf,
  useDependencies,
} from "./use-dependencies";

export interface InspectedElement {
  readonly elementId: string;
  readonly kind: InspectedKind;
  readonly packageId: string | null;
}

export interface DependencyTableProps {
  readonly analysisId: string;
  readonly direction: DependencyDirection;
  readonly element: InspectedElement;
  readonly onReveal: (target: RevealTarget) => void;
}

const OVERSCAN = 8;

const PREFETCH_ROWS = OVERSCAN;

const ESTIMATED_ROW_HEIGHT = 56;

const estimateRowSize = (): number => ESTIMATED_ROW_HEIGHT;

const measureRow = (
  element: HTMLTableRowElement,
  entry: ResizeObserverEntry | undefined,
  instance: Virtualizer<HTMLDivElement, HTMLTableRowElement>
): number => {
  const size = measureElement(element, entry, instance);
  return size > 0 ? size : ESTIMATED_ROW_HEIGHT;
};

interface DependencyGridProps {
  readonly dependencies: Dependencies;
  readonly direction: DependencyDirection;
  readonly element: InspectedElement;
  readonly onReveal: (target: RevealTarget) => void;
  readonly rows: readonly DependencyTableRow[];
}

function DependencyGrid({
  dependencies,
  direction,
  element,
  onReveal,
  rows,
}: DependencyGridProps) {
  const { canFetchMore, fetchMore, list, loaded, retryMore } = dependencies;
  const scrollRef = useRef<HTMLDivElement>(null);
  const theadRef = useRef<HTMLTableSectionElement>(null);
  const hintId = useId();
  const externalDescriptionId = useId();
  const { release, write } = useCrossHighlight(scrollRef, highlightIdOf);

  const [activeKey, setActiveKey] = useState<string | null>(null);
  const [focusTarget, setFocusTarget] = useState<FocusTarget | null>(null);
  const [containsFocus, setContainsFocus] = useState(false);

  const [scrollMargin, setScrollMargin] = useState(0);
  useLayoutEffect(() => {
    setScrollMargin(theadRef.current?.offsetHeight ?? 0);
  }, []);

  const indexByKey = useMemo(
    () => new Map(rows.map((row, index) => [rowKeyOf(row), index])),
    [rows]
  );
  const activeIndex =
    activeKey === null ? undefined : indexByKey.get(activeKey);
  const firstFocusable = rows.findIndex(isFocusable);
  let tabbableIndex: number | null =
    firstFocusable === -1 ? null : firstFocusable;
  if (activeIndex !== undefined && isFocusable(rows[activeIndex])) {
    tabbableIndex = activeIndex;
  }
  const targetIndex =
    focusTarget === null ? null : resolveTarget(focusTarget, rows, indexByKey);

  const rangeExtractor = useCallback(
    (range: Range) =>
      pinIndexes(defaultRangeExtractor(range), [tabbableIndex, targetIndex]),
    [tabbableIndex, targetIndex]
  );
  const getItemKey = useCallback(
    (index: number) => {
      const row = rows[index];
      return row === undefined ? index : rowKeyOf(row);
    },
    [rows]
  );
  const virtualizer = useVirtualizer<HTMLDivElement, HTMLTableRowElement>({
    count: rows.length,
    estimateSize: estimateRowSize,
    getItemKey,
    getScrollElement: () => scrollRef.current,
    measureElement: measureRow,
    overscan: OVERSCAN,
    rangeExtractor,
    scrollMargin,
  });
  const items = virtualizer.getVirtualItems();

  const endIndex = virtualizer.range?.endIndex ?? null;
  useEffect(() => {
    if (
      endIndex !== null &&
      canFetchMore &&
      endIndex >= loaded - 1 - PREFETCH_ROWS
    ) {
      fetchMore();
    }
  }, [canFetchMore, endIndex, fetchMore, loaded]);

  useLayoutEffect(() => {
    if (targetIndex === null) {
      return;
    }
    const control = scrollRef.current?.querySelector<HTMLElement>(
      `tr[data-index="${targetIndex}"] button`
    );
    if (control === null || control === undefined) {
      return;
    }
    control.focus({ preventScroll: true });
    setFocusTarget(null);
  }, [targetIndex]);

  useLayoutEffect(() => {
    if (containsFocus && !scrollRef.current?.contains(document.activeElement)) {
      setContainsFocus(false);
      release();
    }
  });

  const moveTo = useCallback(
    (index: number) => {
      const row = rows[index];
      if (row === undefined) {
        return;
      }
      setFocusTarget({ key: rowKeyOf(row) });
      virtualizer.scrollToIndex(index, { align: "auto" });
    },
    [rows, virtualizer]
  );

  const activate = useCallback(
    (index: number) => {
      const row = rows[index];
      if (row?.type === "row" && !row.entry.isExternal) {
        onReveal(row.entry);
      } else if (row?.type === "tail-error" && row.retryable) {
        const previous = rows[index - 1];
        if (previous !== undefined) {
          setFocusTarget({ after: rowKeyOf(previous) });
        }
        retryMore();
      }
    },
    [onReveal, retryMore, rows]
  );

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLButtonElement>) => {
      const index = rowIndexOf(event.target);
      if (index === null) {
        return;
      }
      const next = keyTargetOf(event.key, index, rows);
      if (next === undefined) {
        return;
      }
      event.preventDefault();
      if (next !== null && next !== -1) {
        moveTo(next);
      }
    },
    [moveTo, rows]
  );

  const handleClick = useCallback(
    (event: MouseEvent<HTMLButtonElement>) => {
      const index = rowIndexOf(event.currentTarget);
      if (index !== null) {
        activate(index);
      }
    },
    [activate]
  );

  const handleFocus = useCallback(
    (event: FocusEvent<HTMLButtonElement>) => {
      const index = rowIndexOf(event.currentTarget);
      const row = index === null ? undefined : rows[index];
      if (row === undefined) {
        return;
      }
      setContainsFocus(true);
      setActiveKey(rowKeyOf(row));
      const highlightId = highlightIdOfTarget(event.target);
      if (highlightId === null) {
        release();
      } else {
        write(highlightId);
      }
    },
    [release, rows, write]
  );

  const handleBlur = useCallback(
    (event: FocusEvent<HTMLButtonElement>) => {
      if (scrollRef.current?.contains(event.relatedTarget)) {
        return;
      }
      setContainsFocus(false);
      release();
    },
    [release]
  );

  const handleMouseOver = useCallback(
    (event: MouseEvent<HTMLButtonElement>) => {
      const highlightId = highlightIdOfTarget(event.target);
      if (highlightId !== null) {
        write(highlightId);
      }
    },
    [write]
  );

  const handleMouseOut = useCallback(
    (event: MouseEvent<HTMLButtonElement>) => {
      const scroller = scrollRef.current;
      const { relatedTarget } = event;
      if (
        relatedTarget instanceof Node &&
        scroller?.contains(relatedTarget) &&
        highlightIdOfTarget(relatedTarget) !== null
      ) {
        return;
      }
      const active = document.activeElement;
      const focused =
        active !== null && scroller?.contains(active)
          ? highlightIdOf(active)
          : null;
      if (focused === null) {
        release();
      } else {
        write(focused);
      }
    },
    [release, write]
  );

  const controlHandlers = useMemo(
    (): RowControlHandlers => ({
      onBlur: handleBlur,
      onClick: handleClick,
      onFocus: handleFocus,
      onKeyDown: handleKeyDown,
      onMouseOut: handleMouseOut,
      onMouseOver: handleMouseOver,
    }),
    [
      handleBlur,
      handleClick,
      handleFocus,
      handleKeyDown,
      handleMouseOut,
      handleMouseOver,
    ]
  );
  const context: RowContext = {
    controlHandlers,
    direction,
    externalDescriptionId,
    measureRef: virtualizer.measureElement,
    packageId: element.packageId,
    tabbableIndex,
  };
  const reachable = list?.reachable ?? rows.length;

  return (
    <>
      <p className="sr-only" id={hintId}>
        {t.inspector.dependencies.keyboardHint}
      </p>
      <p className="sr-only" id={externalDescriptionId}>
        {t.inspector.dependencies.externalDescription}
      </p>
      <div
        className="-mx-2 min-h-48 flex-1 overflow-y-auto px-2"
        ref={scrollRef}
      >
        <table
          aria-busy={dependencies.busy}
          aria-describedby={hintId}
          aria-label={
            direction === "OUT" ? t.inspector.dependsOn : t.inspector.usedBy
          }
          aria-rowcount={reachable + 1}
          className="w-full table-fixed border-t"
        >
          <colgroup>
            <col />
          </colgroup>
          <thead ref={theadRef}>
            <tr aria-rowindex={1}>
              <th className="sr-only" scope="col">
                {t.inspector.kind}
              </th>
            </tr>
          </thead>
          <tbody>
            {withSpacers(
              items,
              virtualizer.getTotalSize(),
              scrollMargin,
              (item) => {
                const row = rows[item.index];
                return row === undefined
                  ? null
                  : renderDependencyRow(item, row, context);
              }
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}

export function DependencyTable({
  analysisId,
  direction,
  element,
  onReveal,
}: DependencyTableProps): ReactNode {
  const dependencies = useDependencies(
    analysisId,
    element.elementId,
    direction
  );
  const { rows } = dependencies;

  if (rows === undefined) {
    return dependencies.status === "pending" ? (
      <LoadingFirstPage />
    ) : (
      <DependenciesFailure
        error={dependencies.error}
        onRetry={dependencies.retryFirst}
      />
    );
  }
  if (rows.length === 0) {
    return (
      <p className="border-t py-3 text-muted-foreground text-sm">
        {t.inspector.dependencies.empty[direction][element.kind]}
      </p>
    );
  }
  return (
    <DependencyGrid
      dependencies={dependencies}
      direction={direction}
      element={element}
      onReveal={onReveal}
      rows={rows}
    />
  );
}
