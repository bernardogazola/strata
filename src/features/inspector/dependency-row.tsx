import type { VirtualItem } from "@tanstack/react-virtual";
import type {
  FocusEventHandler,
  KeyboardEventHandler,
  MouseEventHandler,
  ReactNode,
  Ref,
} from "react";
import {
  elementDisplayLabel,
  GRAPH_LABEL_MAX_CHARS,
} from "@/features/graph/adapter";
import { ElementKindIcon } from "@/features/graph/element-kind-icon";
import { interpolate, plural } from "@/i18n/format";
import { resolutionQualityLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import type { DependencyDirection, ResolutionQuality } from "@/protocol/enums";
import type { DependencyEntry } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { BreakableName } from "./breakable-name";
import { InfoGlyph, ResolutionGlyph } from "./inspector-glyphs";
import { packageLineOf, verbsOf } from "./inspector-model";
import type { DependencyTableRow } from "./use-dependencies";

const RESOLUTION_TEXT_CLASS = {
  INFERRED: "font-medium text-foreground",
  RESOLVED: "text-muted-foreground",
  UNRESOLVED_EXTERNAL: "font-medium text-foreground",
} as const satisfies Record<ResolutionQuality, string>;

function ExternalTypeIcon() {
  return (
    <span
      aria-hidden="true"
      className="flex w-3 shrink-0 items-center justify-center"
    >
      <span className="block size-[9px] shrink-0 rounded-full border-[1.5px] border-subtle-foreground border-dashed" />
    </span>
  );
}

function PackageGlyph() {
  return (
    <span
      aria-hidden="true"
      className="block h-[7px] w-2.5 shrink-0 rounded-[1.5px] border-[1.2px] border-subtle-foreground"
    />
  );
}

export interface RowControlHandlers {
  readonly onBlur: FocusEventHandler<HTMLButtonElement>;
  readonly onClick: MouseEventHandler<HTMLButtonElement>;
  readonly onFocus: FocusEventHandler<HTMLButtonElement>;
  readonly onKeyDown: KeyboardEventHandler<HTMLButtonElement>;
  readonly onMouseOut: MouseEventHandler<HTMLButtonElement>;
  readonly onMouseOver: MouseEventHandler<HTMLButtonElement>;
}

interface MeasuredRowProps {
  readonly index: number;
  readonly measureRef: Ref<HTMLTableRowElement>;
}

const ROW_CONTROL_CLASS =
  "-mx-2 flex w-[calc(100%+1rem)] cursor-default gap-2 rounded-sm px-2 py-2 text-left transition-[background-color] duration-150 ease-out-quart hover:bg-accent focus-visible:bg-accent data-active:bg-accent motion-reduce:transition-none";

export interface DependencyRowProps extends MeasuredRowProps {
  readonly controlHandlers: RowControlHandlers;
  readonly direction: DependencyDirection;
  readonly entry: DependencyEntry;

  readonly externalDescriptionId: string;

  readonly packageLine: string | null;
  readonly tabbable: boolean;
}

export function DependencyRow({
  controlHandlers,
  direction,
  entry,
  externalDescriptionId,
  index,
  measureRef,
  packageLine,
  tabbable,
}: DependencyRowProps) {
  const { ambiguous, elementId, isExternal, kind, label, resolution, types } =
    entry;
  const active = useAppStore(
    (state) => !isExternal && state.activeElementId === elementId
  );
  return (
    <tr aria-rowindex={index + 2} data-index={index} ref={measureRef}>
      <th className="border-b p-0 text-left align-top font-normal" scope="row">
        <button
          aria-describedby={isExternal ? externalDescriptionId : undefined}
          aria-disabled={isExternal ? true : undefined}
          className={ROW_CONTROL_CLASS}
          data-active={active ? "" : undefined}
          data-highlight-id={isExternal ? undefined : elementId}
          data-slot="dependency-row"
          tabIndex={tabbable ? 0 : -1}
          type="button"
          {...controlHandlers}
        >
          <span className="flex h-[18px] shrink-0 items-center">
            {isExternal ? (
              <ExternalTypeIcon />
            ) : (
              <ElementKindIcon kind={kind} />
            )}
          </span>
          <span className="flex min-w-0 flex-1 flex-col gap-0.5">
            <BreakableName
              className={cn(
                "text-sm",
                isExternal ? "text-muted-foreground" : "text-foreground"
              )}
              kind={kind}
              maxChars={GRAPH_LABEL_MAX_CHARS}
              text={label}
            />
            {packageLine === null ? null : (
              <span className="flex items-center gap-[5px] text-muted-foreground text-xs">
                <PackageGlyph />
                <span className="sr-only">
                  {interpolate(t.inspector.dependencies.packageOf, {
                    name: elementDisplayLabel("PACKAGE", packageLine),
                  })}
                </span>
                <span aria-hidden="true" className="min-w-0">
                  <BreakableName kind="PACKAGE" text={packageLine} />
                </span>
              </span>
            )}
            <span className="flex flex-wrap items-center justify-between gap-x-2.5 gap-y-0.5 text-xs">
              <span className="text-muted-foreground">
                {verbsOf(types, direction)}
              </span>
              <span className="ml-auto flex items-center gap-1.5 whitespace-nowrap">
                <ResolutionGlyph quality={resolution} width={14} />
                <span className={RESOLUTION_TEXT_CLASS[resolution]}>
                  {resolutionQualityLabels[resolution]}
                </span>
              </span>
            </span>
            {ambiguous ? (
              <span className="flex items-start gap-1.5 pt-0.5 text-muted-foreground text-xs">
                <InfoGlyph />
                <span>{t.inspector.dependencies.ambiguous}</span>
              </span>
            ) : null}
          </span>
        </button>
      </th>
    </tr>
  );
}

export function ExternalsHeadingRow({
  count,
  index,
  measureRef,
}: MeasuredRowProps & { readonly count: number }) {
  return (
    <tr aria-rowindex={index + 2} data-index={index} ref={measureRef}>
      <th
        className="border-b pt-3.5 pb-1.5 text-left align-top font-normal text-muted-foreground text-xs"
        scope="row"
      >
        <span className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
          <span className="font-medium">
            {t.inspector.dependencies.externalHeading}
          </span>
          <span>
            {interpolate(
              plural(count, t.inspector.dependencies.externalCount),
              { count }
            )}
          </span>
        </span>
      </th>
    </tr>
  );
}

export function LoadingTailRow({ index, measureRef }: MeasuredRowProps) {
  return (
    <tr aria-rowindex={index + 2} data-index={index} ref={measureRef}>
      <td className="py-2.5 text-muted-foreground text-xs">
        {t.inspector.dependencies.loadingMore}
      </td>
    </tr>
  );
}

export function ErrorTailRow({
  controlHandlers,
  index,
  measureRef,
  retryable,
  tabbable,
}: MeasuredRowProps & {
  readonly controlHandlers: RowControlHandlers;
  readonly retryable: boolean;
  readonly tabbable: boolean;
}) {
  return (
    <tr aria-rowindex={index + 2} data-index={index} ref={measureRef}>
      <td className="border-b py-2.5">
        <span className="flex items-center justify-between gap-2">
          <span className="text-muted-foreground text-xs">
            {t.inspector.dependencies.failedMore}
          </span>
          {retryable ? (
            <button
              className="shrink-0 rounded-sm font-medium text-foreground text-sm underline decoration-1 underline-offset-[3px]"
              tabIndex={tabbable ? 0 : -1}
              type="button"
              {...controlHandlers}
            >
              {t.graph.retry}
            </button>
          ) : null}
        </span>
      </td>
    </tr>
  );
}

export function SpacerRow({ height }: { readonly height: number }) {
  return (
    // biome-ignore lint/a11y/noAriaHiddenOnFocusable: o espaçador não recebe foco e precisa ficar fora da árvore acessível
    <tr aria-hidden="true">
      <td className="border-0 p-0" style={{ height }} />
    </tr>
  );
}

export interface RowContext {
  readonly controlHandlers: RowControlHandlers;
  readonly direction: DependencyDirection;
  readonly externalDescriptionId: string;
  readonly measureRef: (node: HTMLTableRowElement | null) => void;
  readonly packageId: string | null;
  readonly tabbableIndex: number | null;
}

export const renderDependencyRow = (
  item: VirtualItem,
  row: DependencyTableRow,
  context: RowContext
): ReactNode => {
  const { index, key } = item;
  switch (row.type) {
    case "row":
      return (
        <DependencyRow
          controlHandlers={context.controlHandlers}
          direction={context.direction}
          entry={row.entry}
          externalDescriptionId={context.externalDescriptionId}
          index={index}
          key={key}
          measureRef={context.measureRef}
          packageLine={packageLineOf(row.entry, context.packageId)}
          tabbable={index === context.tabbableIndex}
        />
      );
    case "externals-heading":
      return (
        <ExternalsHeadingRow
          count={row.count}
          index={index}
          key={key}
          measureRef={context.measureRef}
        />
      );
    case "tail-loading":
      return (
        <LoadingTailRow
          index={index}
          key={key}
          measureRef={context.measureRef}
        />
      );
    case "tail-error":
      return (
        <ErrorTailRow
          controlHandlers={context.controlHandlers}
          index={index}
          key={key}
          measureRef={context.measureRef}
          retryable={row.retryable}
          tabbable={index === context.tabbableIndex}
        />
      );
    default:
      return row satisfies never;
  }
};

export const withSpacers = (
  items: readonly VirtualItem[],
  totalSize: number,
  scrollMargin: number,
  render: (item: VirtualItem) => ReactNode
): ReactNode[] => {
  const children: ReactNode[] = [];
  let cursor = scrollMargin;
  for (const item of items) {
    const gap = item.start - cursor;
    if (gap > 0) {
      children.push(<SpacerRow height={gap} key={`gap:${String(item.key)}`} />);
    }
    children.push(render(item));
    cursor = item.end;
  }
  const rest = totalSize + scrollMargin - cursor;
  if (rest > 0) {
    children.push(<SpacerRow height={rest} key="gap:end" />);
  }
  return children;
};
