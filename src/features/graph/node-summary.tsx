import { type ReactNode, useCallback, useId } from "react";
import { SECONDARY_ACTION_CLASS } from "@/components/action-styles";
import { Button } from "@/components/ui/button";
import { countPhrase } from "@/features/repository/count-phrase";
import { formatInteger } from "@/i18n/format";
import { graphNodeKindLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { DEFAULT_UNTRUSTED_MAX_CHARS } from "@/lib/untrusted";
import { cn } from "@/lib/utils";
import type { GraphNode } from "@/protocol/graph";
import { ElementKindIcon } from "./element-kind-icon";
import { ElementLabel } from "./element-label";

export type NotOnMapReason = keyof typeof t.inspector.notOnMap;

export interface NodeSummaryProps {
  readonly caveat?: ReactNode;

  readonly focus?: string | null;

  readonly node: GraphNode | null;

  readonly notOnMap?: NotOnMapReason;

  readonly onExpand: (elementId: string) => void;
}

const PACKAGE_ID_PREFIX = "pkg:";

interface MetricRow {
  readonly term: string;
  readonly value: string;
}

const metricRowsOf = (node: GraphNode): readonly MetricRow[] => {
  const neighbours =
    t.graph.canvas.nodes[node.kind === "TYPE" ? "TYPE" : "PACKAGE"];
  const fans: readonly MetricRow[] = [
    {
      term: t.inspector.dependsOn,
      value: countPhrase(node.fanOut, neighbours),
    },
    { term: t.inspector.usedBy, value: countPhrase(node.fanIn, neighbours) },
  ];
  switch (node.kind) {
    case "PACKAGE":
      return [
        { term: t.inspector.typeCount, value: formatInteger(node.typeCount) },
        ...fans,
      ];
    case "PACKAGE_PREFIX":
      return [
        {
          term: t.inspector.group.typeCount,
          value: formatInteger(node.typeCount),
        },
        { term: t.inspector.group.loc, value: formatInteger(node.loc) },
        ...fans,
      ];
    case "TYPE":
      return fans;
    default:
      return node.kind satisfies never;
  }
};

function Metric({
  term,
  value,
}: {
  readonly term: string;
  readonly value: string;
}) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b py-2.5">
      <dt className="text-muted-foreground first-letter:uppercase">{term}</dt>
      <dd className="font-medium tabular-nums">{value}</dd>
    </div>
  );
}

function ExpandButton({
  elementId,
  onExpand,
}: {
  readonly elementId: string;
  readonly onExpand: (elementId: string) => void;
}) {
  const expand = useCallback(() => onExpand(elementId), [elementId, onExpand]);
  return (
    <Button
      className={cn(SECONDARY_ACTION_CLASS, "w-full shadow-control")}
      onClick={expand}
      size="sm"
      variant="outline"
    >
      {t.inspector.expand}
    </Button>
  );
}

function SelectedNode({
  node,
  onExpand,
}: {
  readonly node: GraphNode;
  readonly onExpand: (elementId: string) => void;
}) {
  const titleId = useId();

  return (
    <section
      aria-labelledby={titleId}
      className="flex select-text flex-col gap-5"
    >
      <div className="flex flex-col gap-2">
        <dl className="flex text-muted-foreground text-xs">
          <dt className="sr-only">{t.inspector.kind}</dt>
          <dd className="flex items-center gap-1.5">
            <ElementKindIcon kind={node.kind} />
            <span className="first-letter:uppercase">
              {graphNodeKindLabels[node.kind]}
            </span>
          </dd>
        </dl>
        <h2 className="break-words font-medium text-lg" id={titleId}>
          <ElementLabel
            kind={node.kind}
            label={node.label}
            maxChars={DEFAULT_UNTRUSTED_MAX_CHARS}
          />
        </h2>
      </div>

      <dl className="flex flex-col border-t">
        {metricRowsOf(node).map(({ term, value }) => (
          <Metric key={term} term={term} value={value} />
        ))}
      </dl>

      {node.kind === "PACKAGE" ? (
        <ExpandButton elementId={node.id} onExpand={onExpand} />
      ) : null}
    </section>
  );
}

function EmptySummary({
  focus,
  notOnMap,
  onExpand,
}: {
  readonly focus: string | null;
  readonly notOnMap: NotOnMapReason | undefined;
  readonly onExpand: (elementId: string) => void;
}) {
  if (notOnMap !== undefined) {
    const expandableId =
      notOnMap === "aggregated" && focus?.startsWith(PACKAGE_ID_PREFIX)
        ? focus
        : null;
    return (
      <div className="flex flex-col gap-5">
        <p className="text-muted-foreground">
          {t.inspector.notOnMap[notOnMap]}
        </p>
        {expandableId === null ? null : (
          <ExpandButton elementId={expandableId} onExpand={onExpand} />
        )}
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      <p className="text-muted-foreground">{t.inspector.empty}</p>
      <p className="text-muted-foreground">{t.graph.legend.expandHint}</p>
    </div>
  );
}

export function NodeSummary({
  caveat,
  focus = null,
  node,
  notOnMap,
  onExpand,
}: NodeSummaryProps) {
  return (
    <div className="flex flex-1 flex-col gap-5">
      {node === null ? (
        <EmptySummary focus={focus} notOnMap={notOnMap} onExpand={onExpand} />
      ) : (
        <SelectedNode node={node} onExpand={onExpand} />
      )}
      {caveat === undefined || caveat === null ? null : (
        <div className="mt-auto">{caveat}</div>
      )}
    </div>
  );
}
