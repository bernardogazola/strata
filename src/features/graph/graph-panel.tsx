import { useQuery } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { BridgeError } from "@/bridge/analyzer";
import { Button } from "@/components/ui/button";
import { formatProtocolError } from "@/i18n/errors";
import { interpolate, plural } from "@/i18n/format";
import { graphLevelLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import type { ProtocolError } from "@/protocol/errors";
import type { GraphSlice } from "@/protocol/graph";
import {
  type GraphViewParams,
  graphSliceQueryOptions,
} from "@/queries/options";
import type { GraphView } from "./adapter";
import { ArchitectureGraph } from "./architecture-graph";

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

const protocolErrorOf = (error: Error): ProtocolError =>
  error instanceof BridgeError
    ? error.protocolError
    : { code: "INTERNAL_UNEXPECTED", origin: "frontend" };

const countPhrase = (
  count: number,
  forms: { readonly one: string; readonly other: string }
): string => interpolate(plural(count, forms), { count });

const canvasLabel = ({ edges, level, nodes }: GraphSlice): string =>
  interpolate(t.graph.canvas.label, {
    edges: countPhrase(edges.length, t.graph.canvas.edges),
    level: graphLevelLabels[level],
    nodes: countPhrase(nodes.length, t.graph.canvas.nodes[level]),
  });

const sliceNotices = ({
  aggregated,
  edges,
  level,
  nodes,
  totals,
  truncated,
}: GraphSlice): readonly string[] => [
  ...(aggregated
    ? [interpolate(t.graph.notice.aggregated, { total: totals.nodes })]
    : []),
  ...(truncated
    ? [
        interpolate(t.graph.notice.truncated, {
          edges: countPhrase(totals.edges, t.graph.canvas.edges),
          edgesShown: edges.length,
          nodes: countPhrase(totals.nodes, t.graph.canvas.nodes[level]),
          nodesShown: nodes.length,
        }),
      ]
    : []),
];

function FailureState({
  error,
  onRetry,
}: {
  readonly error: Error;
  readonly onRetry: () => void;
}) {
  const message = formatProtocolError(protocolErrorOf(error));
  return (
    <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-6 text-center">
      <div className="flex max-w-sm flex-col gap-1" role="alert">
        <p className="font-medium">{message.title}</p>
        {message.hint === undefined ? null : (
          <p className="text-muted-foreground text-sm">{message.hint}</p>
        )}
      </div>
      {message.retryable ? (
        <Button onClick={onRetry} size="sm" variant="outline">
          {t.graph.retry}
        </Button>
      ) : null}
    </div>
  );
}

function CenteredStatus({ text }: { readonly text: string }) {
  return (
    <div className="absolute inset-0 flex items-center justify-center p-6">
      <p className="text-muted-foreground text-sm" role="status">
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
      className="flex flex-col gap-1 border-b bg-muted px-4 py-2 text-sm"
      role="status"
    >
      {notices.map((notice) => (
        <p key={notice}>{notice}</p>
      ))}
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

  return (
    <div aria-busy={isPending} className="flex h-full flex-col">
      {drawable === null ? null : (
        <NoticeBar notices={sliceNotices(drawable.slice)} />
      )}
      <div className="relative min-h-0 flex-1">
        <ArchitectureGraph
          elements={shown?.elements ?? null}
          label={
            drawable === null
              ? t.shell.regions.canvas
              : canvasLabel(drawable.slice)
          }
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
    </div>
  );
}
