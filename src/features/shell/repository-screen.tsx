import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useCallback, useEffect, useRef } from "react";
import { ModeToggle } from "@/components/mode-toggle";
import { buttonVariants } from "@/components/ui/button";
import { AnalyzerStatus } from "@/features/backend/analyzer-status";
import { GRAPH_LABEL_MAX_CHARS } from "@/features/graph/adapter";
import { GraphLegend } from "@/features/graph/graph-legend";
import { GraphPanel } from "@/features/graph/graph-panel";
import { NodeSummary } from "@/features/graph/node-summary";
import { t } from "@/i18n/messages";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";
import type { GraphNode, GraphSlice } from "@/protocol/graph";
import {
  type GraphViewParams,
  graphSliceQueryOptions,
} from "@/queries/options";
import { AppShell } from "./app-shell";
import { type RepositorySearch, resolveGraphView } from "./repository-search";

const ROUTE_ID = "/repository/$handle";
const routeApi = getRouteApi(ROUTE_ID);

const findNode = (
  slice: GraphSlice,
  elementId: string | null
): GraphNode | null =>
  elementId === null
    ? null
    : (slice.nodes.find((node) => node.id === elementId) ?? null);

function ParentCrumb({
  analysisId,
  parentId,
}: {
  readonly analysisId: string | undefined;
  readonly parentId: string | undefined;
}) {
  const queryClient = useQueryClient();
  const levelOne =
    analysisId === undefined
      ? undefined
      : queryClient.getQueryData(
          graphSliceQueryOptions(analysisId, { level: "PACKAGE" }).queryKey
        );
  const label = levelOne?.nodes.find((node) => node.id === parentId)?.label;

  if (label === undefined) {
    return <>{t.shell.breadcrumb.selectedPackage}</>;
  }
  const clean = clampText(sanitizeUntrusted(label), GRAPH_LABEL_MAX_CHARS);
  if (clean === "") {
    return <>{t.graph.defaultPackage}</>;
  }
  return (
    <bdi className="font-mono" lang="" translate="no">
      {clean}
    </bdi>
  );
}

const backToLevelOne = (prev: RepositorySearch): RepositorySearch => ({
  ...prev,
  focus: prev.parent,
  level: "PACKAGE",
  parent: undefined,
});

const useFocusOnViewChange = (viewKey: string) => {
  const targetRef = useRef<HTMLSpanElement>(null);
  const shownKey = useRef(viewKey);

  useEffect(() => {
    if (shownKey.current === viewKey) {
      return;
    }
    shownKey.current = viewKey;
    if (document.activeElement === document.body) {
      targetRef.current?.focus();
    }
  }, [viewKey]);

  return targetRef;
};

const CRUMB_FOCUS_CLASS =
  "rounded-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50";

function MapBreadcrumb({
  analysisId,
  view,
}: {
  readonly analysisId: string | undefined;
  readonly view: GraphViewParams;
}) {
  const atRoot = view.level === "PACKAGE";
  const currentRef = useFocusOnViewChange(
    `${view.level}:${view.parentId ?? ""}`
  );

  return (
    <nav aria-label={t.shell.breadcrumb.label}>
      <ol className="flex items-center gap-2 text-sm">
        <li>
          {atRoot ? (
            <span
              aria-current="page"
              className={CRUMB_FOCUS_CLASS}
              ref={currentRef}
              tabIndex={-1}
            >
              {t.shell.breadcrumb.root}
            </span>
          ) : (
            <Link
              className="text-muted-foreground underline-offset-4 hover:underline"
              from={ROUTE_ID}
              search={backToLevelOne}
              to="."
            >
              {t.shell.breadcrumb.root}
            </Link>
          )}
        </li>
        {atRoot ? null : (
          <li className="flex items-center gap-2">
            <span aria-hidden="true" className="text-muted-foreground">
              /
            </span>
            <span
              aria-current="page"
              className={CRUMB_FOCUS_CLASS}
              ref={currentRef}
              tabIndex={-1}
            >
              <ParentCrumb analysisId={analysisId} parentId={view.parentId} />
            </span>
          </li>
        )}
      </ol>
    </nav>
  );
}

function NoAnalysis() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
      <h2 className="font-medium">{t.graph.noAnalysis.title}</h2>
      <p className="max-w-sm text-muted-foreground text-sm">
        {t.graph.noAnalysis.body}
      </p>
      <Link
        className={buttonVariants({ size: "sm", variant: "outline" })}
        to="/"
      >
        {t.graph.noAnalysis.action}
      </Link>
    </div>
  );
}

function FocusedNodeSummary({
  analysisId,
  focus,
  onExpand,
  view,
}: {
  readonly analysisId: string;
  readonly focus: string | null;
  readonly onExpand: (elementId: string) => void;
  readonly view: GraphViewParams;
}) {
  const selectNode = useCallback(
    (slice: GraphSlice) => findNode(slice, focus),
    [focus]
  );
  const { data } = useQuery({
    ...graphSliceQueryOptions(analysisId, view),
    select: selectNode,
  });
  return <NodeSummary node={data ?? null} onExpand={onExpand} />;
}

export function RepositoryScreen() {
  const search = routeApi.useSearch();
  const navigate = routeApi.useNavigate();
  const view = resolveGraphView(search);
  const focus = search.focus ?? null;
  const analysisId = search.analysis;

  const onSelect = useCallback(
    (elementId: string | null) => {
      navigate({
        replace: true,
        search: (prev) => ({ ...prev, focus: elementId ?? undefined }),
      });
    },
    [navigate]
  );

  const onExpand = useCallback(
    (elementId: string) => {
      navigate({
        search: (prev) => ({
          ...prev,
          focus: undefined,
          level: "TYPE",
          parent: elementId,
        }),
      });
    },
    [navigate]
  );

  return (
    <AppShell
      header={
        <>
          <h1 className="font-semibold">{t.app.name}</h1>
          <MapBreadcrumb analysisId={analysisId} view={view} />
          <div className="ml-auto flex items-center gap-3">
            <AnalyzerStatus />
            <ModeToggle />
          </div>
        </>
      }
      inspector={
        analysisId === undefined ? (
          <NodeSummary node={null} onExpand={onExpand} />
        ) : (
          <FocusedNodeSummary
            analysisId={analysisId}
            focus={focus}
            onExpand={onExpand}
            view={view}
          />
        )
      }
      main={
        analysisId === undefined ? (
          <NoAnalysis />
        ) : (
          <GraphPanel
            analysisId={analysisId}
            focus={focus}
            onExpand={onExpand}
            onSelect={onSelect}
            view={view}
          />
        )
      }
      sidebar={<GraphLegend />}
    />
  );
}
