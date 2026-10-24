import {
  type InfiniteData,
  useInfiniteQuery,
  useQuery,
} from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import {
  type ReactNode,
  type RefObject,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { SECONDARY_ACTION_CLASS } from "@/components/action-styles";
import { buttonVariants } from "@/components/ui/button";
import { ErrorSurface } from "@/features/errors/error-surface";
import { elementDisplayLabel } from "@/features/graph/adapter";
import { ElementLabel } from "@/features/graph/element-label";
import { GraphPanel } from "@/features/graph/graph-panel";
import {
  NodeSummary,
  type NotOnMapReason,
} from "@/features/graph/node-summary";
import {
  ArchitectureOutline,
  type OutlineHandle,
} from "@/features/outline/architecture-outline";
import { NodeSearch } from "@/features/outline/node-search";
import {
  AnalysisScreen,
  type AnalysisWorkspaceProps,
} from "@/features/progress/analysis-screen";
import { CompletenessCaveat } from "@/features/progress/completeness-caveat";
import { PrepareScreen } from "@/features/repository/prepare-screen";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import type { ElementKind } from "@/protocol/enums";
import type { ProtocolError } from "@/protocol/errors";
import type {
  GraphNode,
  GraphSlice,
  OutlinePage,
  SearchHit,
} from "@/protocol/graph";
import {
  type GraphViewParams,
  graphSliceQueryOptions,
  outlineChildrenQueryOptions,
} from "@/queries/options";
import { AppHeader } from "./app-header";
import { AppShell } from "./app-shell";
import { useFocusTitleOnScreenChange } from "./focus-title";
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

  readonly notOnMap: NotOnMapReason | undefined;
}

const describeFocus = (
  slice: GraphSlice,
  elementId: string | null
): FocusedNode => {
  if (elementId === null) {
    return { node: null, notOnMap: undefined };
  }
  const node = slice.nodes.find((candidate) => candidate.id === elementId);
  if (node !== undefined) {
    return { node, notOnMap: undefined };
  }
  if (slice.aggregated) {
    return { node: null, notOnMap: "aggregated" };
  }
  return { node: null, notOnMap: slice.truncated ? "truncated" : undefined };
};

const LEVEL_ONE: GraphViewParams = { level: "PACKAGE" };

const useParentLabel = (
  analysisId: string,
  parentId: string | undefined
): string | undefined => {
  const findInSlice = useCallback(
    (slice: GraphSlice) =>
      slice.nodes.find((node) => node.id === parentId)?.label ?? null,
    [parentId]
  );
  const findInOutline = useCallback(
    (data: InfiniteData<OutlinePage>) =>
      data.pages
        .flatMap((page) => page.items)
        .find((item) => item.id === parentId)?.label ?? null,
    [parentId]
  );
  const { data: sliceLabel } = useQuery({
    ...graphSliceQueryOptions(analysisId, LEVEL_ONE),
    enabled: false,
    select: findInSlice,
  });
  const { data: outlineLabel } = useInfiniteQuery({
    ...outlineChildrenQueryOptions(analysisId, null),
    enabled: false,
    select: findInOutline,
  });
  return sliceLabel ?? outlineLabel ?? undefined;
};

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

const CURRENT_CRUMB_CLASS = "rounded-sm text-muted-foreground";

function ParentCrumb({
  analysisId,
  currentRef,
  parentId,
}: {
  readonly analysisId: string;
  readonly currentRef: RefObject<HTMLSpanElement | null>;
  readonly parentId: string | undefined;
}) {
  const label = useParentLabel(analysisId, parentId);
  return (
    <span
      aria-current="page"
      className={cn(CURRENT_CRUMB_CLASS, "min-w-0 truncate")}
      ref={currentRef}
      tabIndex={-1}
      title={
        label === undefined
          ? t.shell.breadcrumb.selectedPackage
          : elementDisplayLabel("PACKAGE", label)
      }
    >
      {label === undefined ? (
        t.shell.breadcrumb.selectedPackage
      ) : (
        <ElementLabel kind="PACKAGE" label={label} />
      )}
    </span>
  );
}

function MapBreadcrumb({
  analysisId,
  view,
}: {
  readonly analysisId: string;
  readonly view: GraphViewParams;
}) {
  const atRoot = view.level === "PACKAGE";
  const currentRef = useFocusOnViewChange(
    `${view.level}:${view.parentId ?? ""}`
  );

  return (
    <nav aria-label={t.shell.breadcrumb.label} className="min-w-0">
      <ol className="flex min-w-0 items-center gap-2 whitespace-nowrap text-sm">
        <li className="shrink-0">
          {atRoot ? (
            <span
              aria-current="page"
              className={CURRENT_CRUMB_CLASS}
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
          <li className="flex min-w-0 items-center gap-2">
            <span aria-hidden="true" className="shrink-0 text-muted-foreground">
              /
            </span>
            <ParentCrumb
              analysisId={analysisId}
              currentRef={currentRef}
              parentId={view.parentId}
            />
          </li>
        )}
      </ol>
    </nav>
  );
}

function FocusedNodeSummary({
  analysisId,
  caveat,
  focus,
  onExpand,
  view,
}: {
  readonly analysisId: string;
  readonly caveat: ReactNode;
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
      caveat={caveat}
      focus={focus}
      level={view.level}
      node={data?.node ?? null}
      notOnMap={data?.notOnMap}
      onExpand={onExpand}
    />
  );
}

function RepositoryWorkspace({ header, report }: AnalysisWorkspaceProps) {
  const search = routeApi.useSearch();
  const navigate = routeApi.useNavigate();
  const view = resolveGraphView(search);
  const focus = search.focus ?? null;
  const { analysisId } = report;
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
        <AppHeader
          {...header}
          trail={<MapBreadcrumb analysisId={analysisId} view={view} />}
        />
      }
      inspector={
        <FocusedNodeSummary
          analysisId={analysisId}
          caveat={<CompletenessCaveat compact report={report} />}
          focus={focus}
          onExpand={onExpand}
          view={view}
        />
      }
      main={
        <>
          <SkipToOutline onSkip={onSkip} />
          <h1 className="sr-only" tabIndex={-1}>
            {t.shell.regions.canvas}
          </h1>
          <GraphPanel
            analysisId={analysisId}
            focus={focus}
            onExpand={onExpand}
            onSelect={onSelect}
            view={view}
          />
        </>
      }
      sidebar={
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
      }
    />
  );
}

const UNKNOWN_HANDLE: ProtocolError = {
  code: "REPO_HANDLE_UNKNOWN",
  origin: "frontend",
};

export function UnknownRepositoryScreen() {
  return (
    <div className="flex min-h-svh flex-col bg-background">
      <AppHeader />
      <main
        aria-label={t.shell.regions.repository}
        className="flex grow items-center justify-center px-4 pt-8 pb-12"
      >
        <div className="flex w-full max-w-[520px] flex-col gap-6">
          <ErrorSurface
            actions={
              <Link
                className={cn(
                  buttonVariants({ size: "sm", variant: "outline" }),
                  SECONDARY_ACTION_CLASS
                )}
                to="/"
              >
                {t.repository.backToStart}
              </Link>
            }
            error={UNKNOWN_HANDLE}
            variant="page"
          />
        </div>
      </main>
    </div>
  );
}

export function RepositoryScreen() {
  const { handle } = routeApi.useParams();
  const { analysis } = routeApi.useSearch();
  const screenKey = analysis === undefined ? "prepare" : `analysis:${analysis}`;
  useFocusTitleOnScreenChange(screenKey);

  const [firstKey] = useState(screenKey);
  const [navigated, setNavigated] = useState(false);
  if (!navigated && screenKey !== firstKey) {
    setNavigated(true);
  }

  if (analysis === undefined) {
    return <PrepareScreen handle={handle} />;
  }
  return (
    <AnalysisScreen
      analysisId={analysis}
      arrivedByNavigation={navigated}
      handle={handle}
      key={screenKey}
      workspace={RepositoryWorkspace}
    />
  );
}
