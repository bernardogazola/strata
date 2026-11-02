import { Tabs } from "@base-ui/react/tabs";
import { useQuery } from "@tanstack/react-query";
import { type ReactNode, type RefObject, useCallback, useRef } from "react";
import {
  ExpandButton,
  NodeSummary,
  type NotOnMapReason,
} from "@/features/graph/node-summary";
import type {
  InspectorTab,
  RevealTarget,
} from "@/features/shell/repository-search";
import { t } from "@/i18n/messages";
import type { DependencyDirection } from "@/protocol/enums";
import type { GraphNode, GraphSlice } from "@/protocol/graph";
import { packageIdSchema } from "@/protocol/primitives";
import {
  elementDetailQueryOptions,
  type GraphViewParams,
  graphSliceQueryOptions,
} from "@/queries/options";
import { DependencyPanel } from "./dependency-panel";
import { ElementHeader, type FocusHandoff } from "./element-header";
import { ElementOverview } from "./element-overview";
import {
  INSPECTOR_TAB_LABELS,
  SHOWN_TABS,
  shownTabOf,
} from "./inspector-labels";
import { type InspectedKind, isInspectableId } from "./inspector-model";
import {
  DescribeFailure,
  LoadingDetail,
  NotOnMapNote,
} from "./inspector-states";

export interface ElementInspectorProps {
  readonly analysisId: string;

  readonly caveat?: ReactNode;

  readonly direction: DependencyDirection;

  readonly focus: string | null;

  readonly onExpand: (packageId: string) => void;

  readonly onReveal: (target: RevealTarget) => void;

  readonly onSelectDirection: (direction: DependencyDirection) => void;

  readonly onSelectTab: (tab: InspectorTab) => void;

  readonly tab: InspectorTab;

  readonly view: GraphViewParams;
}

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

const TAB_CLASS =
  "-mb-px border-transparent border-b-2 pt-2 pb-2 text-muted-foreground text-sm transition-[color,border-color] duration-150 ease-out-quart hover:text-foreground data-active:border-foreground data-active:font-medium data-active:text-foreground motion-reduce:transition-none";

const columnClass = (shown: InspectorTab): string =>
  shown === "dependencies"
    ? "flex min-h-0 flex-1 flex-col"
    : "flex flex-1 flex-col";

interface DescribedElementProps
  extends Omit<ElementInspectorProps, "caveat" | "focus" | "view"> {
  readonly elementId: string;
  readonly handoff: RefObject<FocusHandoff | null>;
  readonly inspectorRef: RefObject<HTMLElement | null>;
  readonly node: GraphNode | null;
}

function DescribedElement({
  analysisId,
  direction,
  elementId,
  handoff,
  inspectorRef,
  node,
  onExpand,
  onReveal,
  onSelectDirection,
  onSelectTab,
  tab,
}: DescribedElementProps) {
  const kind: InspectedKind = packageIdSchema.safeParse(elementId).success
    ? "PACKAGE"
    : "TYPE";
  const {
    data: detail,
    error,
    refetch,
  } = useQuery(elementDetailQueryOptions(analysisId, elementId));
  const dependenciesTabRef = useRef<HTMLButtonElement>(null);
  const label = detail?.label ?? node?.label;
  const shownTab = shownTabOf(tab);

  const retry = useCallback(() => {
    refetch();
  }, [refetch]);

  const handleTabChange = useCallback(
    (value: unknown) => {
      const chosen = SHOWN_TABS.find((candidate) => candidate === value);
      if (chosen !== undefined) {
        onSelectTab(chosen);
      }
    },
    [onSelectTab]
  );

  const openDependencies = useCallback(
    (chosen: DependencyDirection) => {
      dependenciesTabRef.current?.focus();
      if (chosen !== direction) {
        onSelectDirection(chosen);
      }
      onSelectTab("dependencies");
    },
    [direction, onSelectDirection, onSelectTab]
  );

  const expand =
    kind === "PACKAGE" ? (
      <ExpandButton elementId={elementId} onExpand={onExpand} />
    ) : null;

  const header =
    label === undefined ? null : (
      <ElementHeader
        detail={detail}
        elementId={elementId}
        handoff={handoff}
        inspectorRef={inspectorRef}
        kind={kind}
        label={label}
        settled={detail !== undefined || error !== null}
      />
    );

  const unresolved =
    error === null ? (
      <LoadingDetail />
    ) : (
      <DescribeFailure error={error} onRetry={retry} />
    );

  const dependencyPanel =
    detail === undefined ? (
      unresolved
    ) : (
      <DependencyPanel
        analysisId={analysisId}
        direction={direction}
        element={{ elementId, kind, packageId: detail.packageId }}
        graph={detail.graph}
        onReveal={onReveal}
        onSelectDirection={onSelectDirection}
      />
    );

  return (
    <>
      {header}
      <p className="sr-only" role="status">
        {detail === undefined && error === null ? t.inspector.loading : ""}
      </p>
      <Tabs.Root
        className={columnClass(shownTab)}
        onValueChange={handleTabChange}
        value={shownTab}
      >
        <Tabs.List
          aria-label={t.inspector.tabs.label}
          className="mt-4 flex gap-5 border-b"
        >
          {SHOWN_TABS.map((value) => (
            <Tabs.Tab
              className={TAB_CLASS}
              key={value}
              ref={value === "dependencies" ? dependenciesTabRef : undefined}
              value={value}
            >
              {INSPECTOR_TAB_LABELS[value]}
            </Tabs.Tab>
          ))}
        </Tabs.List>
        <Tabs.Panel value="overview">
          {detail === undefined ? (
            unresolved
          ) : (
            <ElementOverview
              detail={detail}
              kind={kind}
              onOpenDependencies={openDependencies}
              typeCount={node?.kind === "PACKAGE" ? node.typeCount : null}
            />
          )}
          {expand === null ? null : <div className="pt-6">{expand}</div>}
        </Tabs.Panel>
        <Tabs.Panel
          className="flex min-h-0 flex-1 flex-col"
          tabIndex={-1}
          value="dependencies"
        >
          {shownTab === "dependencies" ? dependencyPanel : null}
        </Tabs.Panel>
      </Tabs.Root>
    </>
  );
}

export function ElementInspector({
  analysisId,
  caveat,
  direction,
  focus,
  onExpand,
  onReveal,
  onSelectDirection,
  onSelectTab,
  tab,
  view,
}: ElementInspectorProps): ReactNode {
  const selectFocus = useCallback(
    (slice: GraphSlice) => describeFocus(slice, focus),
    [focus]
  );
  const { data } = useQuery({
    ...graphSliceQueryOptions(analysisId, view),
    select: selectFocus,
  });
  const node = data?.node ?? null;
  const notOnMap = data?.notOnMap;

  const handoff = useRef<FocusHandoff | null>(null);
  const inspectorRef = useRef<HTMLDivElement>(null);
  const reveal = useCallback(
    (target: RevealTarget) => {
      if (focus !== null) {
        handoff.current = { from: focus, to: target.elementId };
      }
      onReveal(target);
    },
    [focus, onReveal]
  );

  const described =
    isInspectableId(focus) && (node !== null || notOnMap !== undefined);
  if (!described) {
    return (
      <NodeSummary
        caveat={caveat}
        focus={focus}
        level={view.level}
        node={node}
        notOnMap={notOnMap}
        onExpand={onExpand}
      />
    );
  }

  return (
    <div
      className={`@container ${columnClass(shownTabOf(tab))}`}
      ref={inspectorRef}
    >
      {notOnMap === undefined ? null : <NotOnMapNote reason={notOnMap} />}
      <DescribedElement
        analysisId={analysisId}
        direction={direction}
        elementId={focus}
        handoff={handoff}
        inspectorRef={inspectorRef}
        node={node}
        onExpand={onExpand}
        onReveal={reveal}
        onSelectDirection={onSelectDirection}
        onSelectTab={onSelectTab}
        tab={tab}
      />
      {caveat === undefined || caveat === null ? null : (
        <div className="mt-auto pt-5">{caveat}</div>
      )}
    </div>
  );
}
