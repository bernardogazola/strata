import { IconInfoCircle } from "@tabler/icons-react";
import { useQuery } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { protocolErrorOf } from "@/bridge/analyzer";
import { SECONDARY_ACTION_CLASS } from "@/components/action-styles";
import { Button } from "@/components/ui/button";
import { formatProtocolError } from "@/i18n/errors";
import { interpolate, plural } from "@/i18n/format";
import { graphLevelLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import type { GraphNode, GraphSlice } from "@/protocol/graph";
import {
  type GraphViewParams,
  graphSliceQueryOptions,
} from "@/queries/options";
import { GraphSliceViolationError, type GraphView } from "./adapter";
import { ArchitectureGraph } from "./architecture-graph";
import { GraphLegend } from "./graph-legend";

export interface GraphPanelProps {
  readonly analysisId: string;

  readonly focus: string | null;
  readonly onExpand: (elementId: string) => void;
  readonly onSelect: (elementId: string | null) => void;
  readonly view: GraphViewParams;
}

interface RenderFailure {
  readonly error: Error;
  readonly view: GraphView;
}

interface CountForms {
  readonly one: string;
  readonly other: string;
  readonly zero?: string;
}

const countPhrase = (count: number, forms: CountForms): string =>
  interpolate(plural(count, forms), { count });

const totalPhrase = (count: number, { one, other }: CountForms): string =>
  countPhrase(count, { one, other });

const isGroup = (node: GraphNode): boolean => node.kind === "PACKAGE_PREFIX";

const canvasLabel = ({
  aggregated,
  edges,
  level,
  nodes,
}: GraphSlice): string => {
  const edgesPhrase = countPhrase(edges.length, t.graph.canvas.edges);
  const levelName = graphLevelLabels[level];
  if (!aggregated) {
    return interpolate(t.graph.canvas.label, {
      edges: edgesPhrase,
      level: levelName,
      nodes: countPhrase(nodes.length, t.graph.canvas.nodes[level]),
    });
  }
  const groups = nodes.filter(isGroup).length;
  return interpolate(t.graph.canvas.labelAggregated, {
    edges: edgesPhrase,
    groups: countPhrase(groups, t.graph.canvas.groups),
    level: levelName,
    nodes: countPhrase(nodes.length - groups, t.graph.canvas.nodes[level]),
  });
};

const truncationNotice = ({
  aggregated,
  edges,
  level,
  nodes,
  totals,
}: GraphSlice): string => {
  if (aggregated) {
    const { shown, template } = t.graph.notice.truncatedEdges;
    return interpolate(template, { shown: countPhrase(edges.length, shown) });
  }
  return interpolate(t.graph.notice.truncated, {
    edges: totalPhrase(totals.edges, t.graph.canvas.edges),
    edgesShown: edges.length,
    nodes: totalPhrase(totals.nodes, t.graph.canvas.nodes[level]),
    nodesShown: nodes.length,
  });
};

const sliceNotices = (slice: GraphSlice): readonly string[] => [
  ...(slice.aggregated
    ? [interpolate(t.graph.notice.aggregated, { total: slice.totals.nodes })]
    : []),
  ...(slice.truncated ? [truncationNotice(slice)] : []),
];

function FailureState({
  error,
  onRetry,
}: {
  readonly error: Error;
  readonly onRetry: () => void;
}) {
  const message = formatProtocolError(protocolErrorOf(error));
  const retryable =
    message.retryable && !(error instanceof GraphSliceViolationError);
  return (
    <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-6 text-center">
      <div className="flex max-w-sm flex-col gap-1" role="alert">
        <p className="font-medium text-base">{message.title}</p>
        {message.hint === undefined ? null : (
          <p className="text-muted-foreground">{message.hint}</p>
        )}
      </div>
      {retryable ? (
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

function CenteredStatus({ text }: { readonly text: string }) {
  return (
    <div className="absolute inset-0 flex items-center justify-center p-6">
      <p className="text-muted-foreground" role="status">
        {text}
      </p>
    </div>
  );
}

function NoticeBar({ notices }: { readonly notices: readonly string[] }) {
  if (notices.length === 0) {
    return null;
  }
  return (
    <div
      className="flex items-start gap-2 border-b bg-muted/50 px-4 py-2 text-muted-foreground"
      role="status"
    >
      <IconInfoCircle
        aria-hidden="true"
        className="mt-0.5 size-3.5 shrink-0 text-subtle-foreground"
        stroke={1.5}
      />
      <div className="flex min-w-0 flex-col gap-1">
        {notices.map((notice) => (
          <p key={notice}>{notice}</p>
        ))}
      </div>
    </div>
  );
}

export function GraphPanel({
  analysisId,
  focus,
  onExpand,
  onSelect,
  view,
}: GraphPanelProps) {
  const { data, error, isPending, refetch } = useQuery(
    graphSliceQueryOptions(analysisId, view)
  );
  const [renderFailure, setRenderFailure] = useState<RenderFailure | null>(
    null
  );

  const onRenderError = useCallback(
    (cause: Error) => {
      if (data !== undefined) {
        setRenderFailure({ error: cause, view: data });
      }
    },
    [data]
  );

  const onRetry = useCallback(() => {
    setRenderFailure(null);
    refetch();
  }, [refetch]);

  const renderError =
    renderFailure !== null && renderFailure.view === data
      ? renderFailure.error
      : null;
  const failure = error ?? renderError;
  const drawable = failure === null && data !== undefined ? data : null;
  const emptyText =
    drawable !== null && drawable.slice.nodes.length === 0
      ? t.graph.empty[drawable.slice.level]
      : null;
  const shown = emptyText === null ? drawable : null;
  const viewLabel = drawable === null ? null : canvasLabel(drawable.slice);

  return (
    <div aria-busy={isPending} className="flex h-full flex-col">
      <p aria-live="polite" className="sr-only">
        {viewLabel}
      </p>
      {drawable === null ? null : (
        <NoticeBar notices={sliceNotices(drawable.slice)} />
      )}
      <div className="relative min-h-0 flex-1">
        <ArchitectureGraph
          elements={shown?.elements ?? null}
          label={viewLabel ?? t.shell.regions.canvas}
          onExpand={onExpand}
          onRenderError={onRenderError}
          onSelect={onSelect}
          selectedElementId={focus}
          slice={shown?.slice ?? null}
        />
        {isPending ? <CenteredStatus text={t.graph.loading} /> : null}
        {failure === null ? null : (
          <FailureState error={failure} onRetry={onRetry} />
        )}
        {emptyText === null ? null : <CenteredStatus text={emptyText} />}
      </div>
      <GraphLegend drawsGroups={drawable?.slice.nodes.some(isGroup) ?? false} />
    </div>
  );
}
