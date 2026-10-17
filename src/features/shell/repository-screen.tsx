import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useCallback, useEffect, useRef } from "react";
import { ModeToggle } from "@/components/mode-toggle";
import { buttonVariants } from "@/components/ui/button";
import { AnalyzerStatus } from "@/features/backend/analyzer-status";
import { ElementLabel } from "@/features/graph/element-label";
import { GraphPanel } from "@/features/graph/graph-panel";
import { NodeSummary } from "@/features/graph/node-summary";
import {
  ArchitectureOutline,
  type OutlineHandle,
} from "@/features/outline/architecture-outline";
import { NodeSearch } from "@/features/outline/node-search";
import { t } from "@/i18n/messages";
import type { ElementKind } from "@/protocol/enums";
import type { GraphNode, GraphSlice, SearchHit } from "@/protocol/graph";
import {
  type GraphViewParams,
  graphSliceQueryOptions,
} from "@/queries/options";
import { AppShell } from "./app-shell";
import {
  backToLevelOne,
  changesView,
  expandPackage,
  resolveGraphView,
  revealSearchHit,
  type SearchTransition,
  selectElement,
  selectWhereDrawn,
} from "./repository-search";
import { SkipToOutline } from "./skip-to-outline";

const ROUTE_ID = "/repository/$handle";
const routeApi = getRouteApi(ROUTE_ID);

interface FocusedNode {
  readonly node: GraphNode | null;

  readonly notOnMap: boolean;
}

const describeFocus = (
  slice: GraphSlice,
  elementId: string | null
): FocusedNode => {
  if (elementId === null) {
    return { node: null, notOnMap: false };
  }
  const node = slice.nodes.find((candidate) => candidate.id === elementId);
  return node === undefined
    ? { node: null, notOnMap: slice.truncated }
    : { node, notOnMap: false };
};

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
  return <ElementLabel className="font-mono" kind="PACKAGE" label={label} />;
}

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
  const selectFocus = useCallback(
    (slice: GraphSlice) => describeFocus(slice, focus),
    [focus]
  );
  const { data } = useQuery({
    ...graphSliceQueryOptions(analysisId, view),
    select: selectFocus,
  });
  return (
    <NodeSummary
      node={data?.node ?? null}
      notOnMap={data?.notOnMap ?? false}
      onExpand={onExpand}
    />
  );
}

export function RepositoryScreen() {
  const search = routeApi.useSearch();
  const navigate = routeApi.useNavigate();
  const view = resolveGraphView(search);
  const focus = search.focus ?? null;
  const analysisId = search.analysis;
  const outlineRef = useRef<OutlineHandle>(null);

  const go = useCallback(
    (transition: SearchTransition) => {
      navigate({
        replace: !changesView(search, transition(search)),
        search: transition,
      });
    },
    [navigate, search]
  );

  const onSelect = useCallback(
    (elementId: string | null) => go(selectElement(elementId)),
    [go]
  );
  const onExpand = useCallback(
    (packageId: string) => go(expandPackage(packageId)),
    [go]
  );
  const onCollapse = useCallback(() => go(backToLevelOne), [go]);
  const onOutlineSelect = useCallback(
    (elementId: string, kind: ElementKind) =>
      go(selectWhereDrawn(elementId, kind)),
    [go]
  );
  const onReveal = useCallback(
    (hit: SearchHit) => go(revealSearchHit(hit)),
    [go]
  );
  const onSkip = useCallback(() => outlineRef.current?.focus(), []);

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
          <>
            <SkipToOutline onSkip={onSkip} />
            <GraphPanel
              analysisId={analysisId}
              focus={focus}
              onExpand={onExpand}
              onSelect={onSelect}
              view={view}
            />
          </>
        )
      }
      sidebar={
        analysisId === undefined ? null : (
          <div className="flex h-full min-h-0 flex-col gap-4">
            <NodeSearch
              analysisId={analysisId}
              focus={focus}
              onReveal={onReveal}
            />
            <ArchitectureOutline
              analysisId={analysisId}
              focus={focus}
              onCollapse={onCollapse}
              onExpand={onExpand}
              onSelect={onOutlineSelect}
              ref={outlineRef}
              view={view}
            />
          </div>
        )
      }
    />
  );
}
