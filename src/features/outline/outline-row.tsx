import { IconChevronRight } from "@tabler/icons-react";
import { ElementLabel } from "@/features/graph/element-label";
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
  "absolute inset-x-0 top-0 flex cursor-default select-none items-center gap-1.5 rounded-sm pr-2 text-sm outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset aria-selected:bg-accent aria-selected:font-medium data-active:bg-muted";

const rowStyle = (start: number) => ({
  height: ROW_HEIGHT,
  transform: `translateY(${start}px)`,
});

const indentOf = (level: 1 | 2): string => (level === 2 ? "pl-8" : "pl-1");

function Chevron({ expanded }: { readonly expanded: boolean | undefined }) {
  if (expanded === undefined) {
    return <span aria-hidden="true" className="size-4 shrink-0" />;
  }
  return (
    <span
      aria-hidden="true"
      className="flex size-4 shrink-0 items-center justify-center"
      data-chevron=""
    >
      <IconChevronRight
        className={cn(
          "size-3.5 transition-transform motion-reduce:transition-none",
          expanded && "rotate-90"
        )}
      />
    </span>
  );
}

function TypeCount({ count }: { readonly count: number }) {
  return (
    <>
      <span
        aria-hidden="true"
        className="ml-auto shrink-0 text-muted-foreground text-xs tabular-nums"
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
      {row.level === 1 ? <Chevron expanded={row.expanded} /> : null}
      <ElementLabel
        className="min-w-0 truncate"
        kind={node.kind}
        label={node.label}
      />
      {node.kind === "PACKAGE" ? <TypeCount count={node.childCount} /> : null}
    </div>
  );
}

function ActionRow({
  index,
  row,
  start,
  tabbable,
  text,
}: PositionedRow<OutlineErrorRow | OutlineMoreRow> & {
  readonly text: string;
}) {
  return (
    <div
      aria-level={row.level}
      aria-posinset={row.type === "more" ? row.posInSet : undefined}
      aria-setsize={row.type === "more" ? row.setSize : undefined}
      className={cn(
        ROW_CLASS,
        indentOf(row.level),
        "text-muted-foreground underline-offset-4 hover:underline"
      )}
      data-index={index}
      role="treeitem"
      style={rowStyle(start)}
      tabIndex={tabbable ? 0 : -1}
    >
      <span className="size-4 shrink-0" />
      <span className="min-w-0 truncate">{text}</span>
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
      <span className="size-4 shrink-0" />
      <span className="min-w-0 truncate">
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
}

export function OutlineTreeRow({
  focus,
  index,
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
          row={row}
          start={start}
          tabbable={tabbable}
          text={moreText(row)}
        />
      );
    case "error":
      return (
        <ActionRow
          index={index}
          row={row}
          start={start}
          tabbable={tabbable}
          text={errorText(row)}
        />
      );
    default:
      return <StatusRow index={index} row={row} start={start} />;
  }
}
