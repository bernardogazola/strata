import { type ReactNode, useCallback, useId } from "react";
import { SECONDARY_ACTION_CLASS } from "@/components/action-styles";
import { Button } from "@/components/ui/button";
import { countPhrase } from "@/features/repository/count-phrase";
import { formatInteger } from "@/i18n/format";
import { elementKindLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { DEFAULT_UNTRUSTED_MAX_CHARS } from "@/lib/untrusted";
import { cn } from "@/lib/utils";
import type { GraphNode } from "@/protocol/graph";
import { ElementKindIcon } from "./element-kind-icon";
import { ElementLabel } from "./element-label";

export type NotOnMapReason = keyof typeof t.inspector.notOnMap;

export interface NodeSummaryProps {
  readonly caveat?: ReactNode;

  readonly node: GraphNode | null;

  readonly notOnMap?: NotOnMapReason;

  readonly onExpand: (elementId: string) => void;
}

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
  const isPackage = node.kind === "PACKAGE";
  const neighbours = t.graph.canvas.nodes[isPackage ? "PACKAGE" : "TYPE"];

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
            <span className="capitalize">{elementKindLabels[node.kind]}</span>
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
        {isPackage ? (
          <Metric
            term={t.inspector.typeCount}
            value={formatInteger(node.typeCount)}
          />
        ) : null}
        <Metric
          term={t.inspector.dependsOn}
          value={countPhrase(node.fanOut, neighbours)}
        />
        <Metric
          term={t.inspector.usedBy}
          value={countPhrase(node.fanIn, neighbours)}
        />
      </dl>

      {isPackage ? (
        <ExpandButton elementId={node.id} onExpand={onExpand} />
      ) : null}
    </section>
  );
}

function EmptySummary({
  notOnMap,
}: {
  readonly notOnMap: NotOnMapReason | undefined;
}) {
  if (notOnMap !== undefined) {
    return (
      <p className="text-muted-foreground">{t.inspector.notOnMap[notOnMap]}</p>
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
  node,
  notOnMap,
  onExpand,
}: NodeSummaryProps) {
  return (
    <div className="flex flex-1 flex-col gap-5">
      {node === null ? (
        <EmptySummary notOnMap={notOnMap} />
      ) : (
        <SelectedNode node={node} onExpand={onExpand} />
      )}
      {caveat === undefined || caveat === null ? null : (
        <div className="mt-auto">{caveat}</div>
      )}
    </div>
  );
}
