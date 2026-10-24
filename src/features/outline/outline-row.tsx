import { IconChevronRight } from "@tabler/icons-react";
import { MessageSegments } from "@/features/errors/message-segment";
import { ElementKindIcon } from "@/features/graph/element-kind-icon";
import { ElementLabel } from "@/features/graph/element-label";
import type { ErrorMessage } from "@/i18n/errors";
import { formatInteger, interpolate, plural } from "@/i18n/format";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import { useAppStore } from "@/state/app-store";
import type {
  OutlineErrorRow,
  OutlineMoreRow,
  OutlineNodeRow,
  OutlineRow,
  OutlineStatusRow,
} from "./use-outline";

export const ROW_HEIGHT = 28;

const ROW_CLASS =
  "absolute inset-x-0 top-0 flex cursor-default select-none items-center gap-1.5 rounded-md pr-2 text-sm transition-[background-color,box-shadow] duration-150 ease-out-quart hover:not-aria-selected:bg-accent aria-selected:bg-raised aria-selected:font-medium aria-selected:shadow-raised data-active:not-aria-selected:bg-accent motion-reduce:transition-none";

const rowStyle = (start: number) => ({
  height: ROW_HEIGHT,
  transform: `translateY(${start}px)`,
});

const wrappingRowStyle = (start: number) => ({
  minHeight: ROW_HEIGHT,
  transform: `translateY(${start}px)`,
});
const WRAPPING_TEXT_CLASS = "min-w-0 break-words py-1 pl-0.5";

const indentOf = (level: 1 | 2): string => (level === 2 ? "pl-7" : "pl-2");

const ChevronSlot = () => (
  <span aria-hidden="true" className="w-3.5 shrink-0" />
);

function Chevron({ expanded }: { readonly expanded: boolean | undefined }) {
  if (expanded === undefined) {
    return <ChevronSlot />;
  }
  return (
    <span
      aria-hidden="true"
      className="flex h-full w-3.5 shrink-0 items-center justify-center"
      data-chevron=""
    >
      <IconChevronRight
        className={cn(
          "size-3.5 transition-transform duration-150 ease-out-quart motion-reduce:transition-none",
          expanded ? "rotate-90 text-foreground" : "text-subtle-foreground"
        )}
        stroke={1.5}
      />
    </span>
  );
}

function TypeCount({ count }: { readonly count: number }) {
  return (
    <>
      <span
        aria-hidden="true"
        className="w-6 shrink-0 text-right text-muted-foreground text-xs tabular-nums"
      >
        {formatInteger(count)}
      </span>
      <span className="sr-only">
        {` ${interpolate(plural(count, t.graph.canvas.nodes.TYPE), { count })}`}
      </span>
    </>
  );
}

interface PositionedRow<R extends OutlineRow> {
  readonly index: number;
  readonly row: R;

  readonly start: number;
  readonly tabbable: boolean;
}

function NodeRow({
  index,
  row,
  selected,
  start,
  tabbable,
}: PositionedRow<OutlineNodeRow> & { readonly selected: boolean }) {
  const { node } = row;
  const active = useAppStore((state) => state.activeElementId === node.id);
  return (
    <div
      aria-expanded={row.expanded}
      aria-level={row.level}
      aria-posinset={row.posInSet}
      aria-selected={selected}
      aria-setsize={row.setSize}
      className={cn(ROW_CLASS, indentOf(row.level))}
      data-active={active ? "" : undefined}
      data-element-id={node.id}
      data-index={index}
      role="treeitem"
      style={rowStyle(start)}
      tabIndex={tabbable ? 0 : -1}
    >
      {row.level === 1 ? <Chevron expanded={row.expanded} /> : <ChevronSlot />}
      <ElementKindIcon kind={node.kind} selected={selected} />
      <ElementLabel
        className={cn(
          "min-w-0 flex-1 truncate pl-0.5",
          row.level === 2 && !selected && "text-muted-foreground"
        )}
        kind={node.kind}
        label={node.label}
      />
      {node.kind === "PACKAGE" ? <TypeCount count={node.childCount} /> : null}
    </div>
  );
}

const ActionLanes = () => (
  <>
    <ChevronSlot />
    <span aria-hidden="true" className="w-3 shrink-0" />
  </>
);

function ActionRow({
  index,
  measureRef,
  row,
  start,
  tabbable,
  text,
}: PositionedRow<OutlineErrorRow | OutlineMoreRow> & {
  readonly measureRef: ((element: HTMLElement | null) => void) | undefined;
  readonly text: string;
}) {
  const failure = row.type === "error";
  return (
    <div
      aria-level={row.level}
      aria-posinset={row.type === "more" ? row.posInSet : undefined}
      aria-setsize={row.type === "more" ? row.setSize : undefined}
      className={cn(
        ROW_CLASS,
        indentOf(row.level),
        "underline-offset-4 hover:underline",
        row.type === "more" ? "text-accent-strong" : "text-muted-foreground"
      )}
      data-index={index}
      ref={failure ? measureRef : undefined}
      role="treeitem"
      style={failure ? wrappingRowStyle(start) : rowStyle(start)}
      tabIndex={tabbable ? 0 : -1}
    >
      <ActionLanes />
      <span
        className={failure ? WRAPPING_TEXT_CLASS : "min-w-0 truncate pl-0.5"}
      >
        {text}
      </span>
    </div>
  );
}

function RefusalRow({
  index,
  measureRef,
  refusal,
  row,
  start,
  tabbable,
}: PositionedRow<OutlineErrorRow> & {
  readonly measureRef: ((element: HTMLElement | null) => void) | undefined;
  readonly refusal: ErrorMessage;
}) {
  return (
    <div
      aria-level={row.level}
      className={cn(ROW_CLASS, indentOf(row.level), "text-muted-foreground")}
      data-index={index}
      ref={measureRef}
      role="treeitem"
      style={wrappingRowStyle(start)}
      tabIndex={tabbable ? 0 : -1}
    >
      <ActionLanes />
      <span className={WRAPPING_TEXT_CLASS}>
        <MessageSegments segments={refusal.body} />
      </span>
    </div>
  );
}

function StatusRow({
  index,
  row,
  start,
}: Omit<PositionedRow<OutlineStatusRow>, "tabbable">) {
  return (
    <div
      aria-hidden="true"
      className={cn(ROW_CLASS, indentOf(row.level), "text-muted-foreground")}
      data-index={index}
      style={rowStyle(start)}
    >
      <ActionLanes />
      <span className="min-w-0 truncate pl-0.5">
        {row.type === "loading" ? t.outline.loadingTypes : t.graph.empty.TYPE}
      </span>
    </div>
  );
}

const moreText = (row: OutlineMoreRow): string =>
  row.total === null
    ? t.outline.loadMore.PACKAGE
    : interpolate(t.outline.loadMore.TYPE, {
        shown: row.shown,
        total: row.total,
      });

const errorText = (row: OutlineErrorRow): string => {
  if (row.parentId !== null) {
    return t.outline.failed;
  }
  return row.failure === "nextPage"
    ? t.outline.failedRoot
    : t.outline.failedRefresh;
};

export interface OutlineTreeRowProps extends PositionedRow<OutlineRow> {
  readonly focus: string | null;

  readonly measureRef?: (element: HTMLElement | null) => void;

  readonly refusal?: ErrorMessage | null;
}

export function OutlineTreeRow({
  focus,
  index,
  measureRef,
  refusal = null,
  row,
  start,
  tabbable,
}: OutlineTreeRowProps) {
  switch (row.type) {
    case "node":
      return (
        <NodeRow
          index={index}
          row={row}
          selected={row.node.id === focus}
          start={start}
          tabbable={tabbable}
        />
      );
    case "more":
      return (
        <ActionRow
          index={index}
          measureRef={undefined}
          row={row}
          start={start}
          tabbable={tabbable}
          text={moreText(row)}
        />
      );
    case "error":
      return refusal === null ? (
        <ActionRow
          index={index}
          measureRef={measureRef}
          row={row}
          start={start}
          tabbable={tabbable}
          text={errorText(row)}
        />
      ) : (
        <RefusalRow
          index={index}
          measureRef={measureRef}
          refusal={refusal}
          row={row}
          start={start}
          tabbable={tabbable}
        />
      );
    default:
      return <StatusRow index={index} row={row} start={start} />;
  }
}
