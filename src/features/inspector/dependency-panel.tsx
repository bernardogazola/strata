import { Radio } from "@base-ui/react/radio";
import { RadioGroup } from "@base-ui/react/radio-group";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useState } from "react";
import { countPhrase } from "@/features/repository/count-phrase";
import type { RevealTarget } from "@/features/shell/repository-search";
import { interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import {
  type DependencyDirection,
  dependencyDirectionSchema,
} from "@/protocol/enums";
import type { DependencyPage, ElementDetail } from "@/protocol/graph";
import { dependencyPagesQueryOptions } from "@/queries/options";
import { DependencyTable, type InspectedElement } from "./dependency-table";
import { SummarySkeleton } from "./inspector-states";

const DIRECTIONS = [
  { label: t.inspector.dependsOn, value: "OUT" },
  { label: t.inspector.usedBy, value: "IN" },
] as const satisfies readonly {
  readonly label: string;
  readonly value: DependencyDirection;
}[];

const totalsOf = (data: {
  readonly pages: readonly DependencyPage[];
}): DependencyPage["totals"] | null => data.pages[0]?.totals ?? null;

interface DirectionPage {
  readonly external: number | undefined;

  readonly pending: boolean;
}

const useDirectionPage = (
  analysisId: string,
  element: InspectedElement,
  direction: DependencyDirection
): DirectionPage => {
  const counted = direction === "OUT" && element.kind === "TYPE";
  const { data, isPending } = useInfiniteQuery({
    ...dependencyPagesQueryOptions(analysisId, element.elementId, direction),
    select: totalsOf,
  });
  if (!counted) {
    return { external: 0, pending: isPending };
  }
  return {
    external:
      data === undefined || data === null ? undefined : (data.outExternal ?? 0),
    pending: isPending,
  };
};

const useLoadingAnnouncement = (pending: boolean): string => {
  const [announcement, setAnnouncement] = useState("");
  useEffect(() => {
    setAnnouncement(pending ? t.inspector.dependencies.loading : "");
  }, [pending]);
  return announcement;
};

const summaryOf = (
  internal: number,
  external: number | undefined,
  kind: InspectedElement["kind"]
): string | null => {
  if (external === undefined || internal + external === 0) {
    return null;
  }
  const { summary } = t.inspector.dependencies;
  const internalPhrase = countPhrase(internal, summary.internal[kind]);
  return external === 0
    ? internalPhrase
    : interpolate(summary.withExternal, {
        external: countPhrase(external, t.inspector.dependencies.externalCount),
        internal: internalPhrase,
      });
};

export interface DependencyPanelProps {
  readonly analysisId: string;
  readonly direction: DependencyDirection;
  readonly element: InspectedElement;

  readonly graph: Pick<ElementDetail["graph"], "fanIn" | "fanOut">;
  readonly onReveal: (target: RevealTarget) => void;
  readonly onSelectDirection: (direction: DependencyDirection) => void;
}

export function DependencyPanel({
  analysisId,
  direction,
  element,
  graph,
  onReveal,
  onSelectDirection,
}: DependencyPanelProps) {
  const { external, pending } = useDirectionPage(
    analysisId,
    element,
    direction
  );
  const announcement = useLoadingAnnouncement(pending);
  const summary = summaryOf(
    direction === "OUT" ? graph.fanOut : graph.fanIn,
    external,
    element.kind
  );

  const handleChange = useCallback(
    (value: unknown) => {
      const parsed = dependencyDirectionSchema.safeParse(value);
      if (parsed.success) {
        onSelectDirection(parsed.data);
      }
    },
    [onSelectDirection]
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3 pt-4">
      <RadioGroup
        aria-label={t.inspector.dependencies.direction}
        className="flex gap-0.5 rounded-lg bg-muted p-0.5"
        onValueChange={handleChange}
        value={direction}
      >
        {DIRECTIONS.map(({ label, value }) => (
          <Radio.Root
            className="flex h-7 flex-1 basis-0 cursor-default items-center justify-center whitespace-nowrap rounded-md px-2 text-muted-foreground text-sm transition-[background-color,box-shadow,color] duration-150 ease-out-quart hover:text-foreground data-checked:bg-raised data-checked:font-medium data-checked:text-foreground data-checked:shadow-raised motion-reduce:transition-none"
            key={value}
            value={value}
          >
            {label}
          </Radio.Root>
        ))}
      </RadioGroup>
      <p className="sr-only" role="status">
        {announcement}
      </p>
      {summary === null ? null : (
        <p className="text-muted-foreground text-xs">{summary}</p>
      )}
      {pending && external === undefined ? <SummarySkeleton /> : null}
      <DependencyTable
        analysisId={analysisId}
        direction={direction}
        element={element}
        key={`${element.elementId}:${direction}`}
        onReveal={onReveal}
      />
    </div>
  );
}
