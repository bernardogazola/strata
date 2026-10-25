import {
  defaultRangeExtractor,
  measureElement,
  type Range,
  useVirtualizer,
  type Virtualizer,
} from "@tanstack/react-virtual";
import {
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
import { useRowHighlightHandlers } from "@/lib/cross-highlight";
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
  hasControl,
  highlightIdOf,
  isFocusable,
  keyTargetOf,
  resolveTarget,
  rowIndexOf,
} from "./table-navigation";
import {
  type Dependencies,
  type DependencyTableRow,
  rowKeyOf,
  TAIL_ROW_KEY,
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

  const keyOf = useCallback(
    (target: Element) => {
      const index = rowIndexOf(target);
      const row = index === null ? undefined : rows[index];
      return row === undefined ? null : rowKeyOf(row);
    },
    [rows]
  );
  const { activeKey, onBlur, onFocus, onMouseOut, onMouseOver, setActiveKey } =
    useRowHighlightHandlers(scrollRef, highlightIdOf, { keyOf });
  const [focusTarget, setFocusTarget] = useState<FocusTarget | null>(null);

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
  if (activeIndex !== undefined && hasControl(rows[activeIndex])) {
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
    if (focusTarget === null) {
      return;
    }
    if ("after" in focusTarget && activeKey !== TAIL_ROW_KEY) {
      setFocusTarget(null);
      return;
    }
    if (targetIndex === null) {
      return;
    }
    const row = rows[targetIndex];
    const control = scrollRef.current?.querySelector<HTMLElement>(
      `tr[data-index="${targetIndex}"] button`
    );
    if (row === undefined || control === null || control === undefined) {
      return;
    }
    setFocusTarget(null);
    const active = document.activeElement;
    const focusIsFree =
      active === null ||
      active === document.body ||
      scrollRef.current?.contains(active) === true;
    if (focusIsFree) {
      control.focus({ preventScroll: true });
    } else {
      setActiveKey(rowKeyOf(row));
    }
  }, [activeKey, focusTarget, rows, setActiveKey, targetIndex]);

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
      } else if (row?.type === "tail" && isFocusable(row)) {
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

  const controlHandlers = useMemo(
    (): RowControlHandlers => ({
      onBlur,
      onClick: handleClick,
      onFocus,
      onKeyDown: handleKeyDown,
      onMouseOut,
      onMouseOver,
    }),
    [handleClick, handleKeyDown, onBlur, onFocus, onMouseOut, onMouseOver]
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
      <p hidden id={hintId}>
        {t.inspector.dependencies.keyboardHint}
      </p>
      <p hidden id={externalDescriptionId}>
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
  const { refreshError, retryFirst, rows } = dependencies;

  if (rows === undefined) {
    return dependencies.status === "pending" ? (
      <LoadingFirstPage />
    ) : (
      <DependenciesFailure error={dependencies.error} onRetry={retryFirst} />
    );
  }
  const refreshFailure =
    refreshError === null ? null : (
      <DependenciesFailure error={refreshError} onRetry={retryFirst} />
    );
  if (rows.length === 0) {
    return (
      <>
        {refreshFailure}
        <p className="border-t py-3 text-muted-foreground text-sm">
          {t.inspector.dependencies.empty[direction][element.kind]}
        </p>
      </>
    );
  }
  return (
    <>
      {refreshFailure}
      <DependencyGrid
        dependencies={dependencies}
        direction={direction}
        element={element}
        onReveal={onReveal}
        rows={rows}
      />
    </>
  );
}
