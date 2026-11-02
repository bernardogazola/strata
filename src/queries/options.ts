import {
  infiniteQueryOptions,
  keepPreviousData,
  mutationOptions,
  queryOptions,
} from "@tanstack/react-query";
import {
  analyzerRequest,
  type MethodInput,
  type MethodResult,
  type PlainMethod,
  type RepositoryMethod,
  repositoryRequest,
} from "@/bridge/analyzer";
import { toGraphView } from "@/features/graph/adapter";
import { GRAPH_PERF } from "@/features/graph/perf";
import {
  type DependencyRows,
  flattenDependencyPages,
} from "@/features/inspector/inspector-model";
import type {
  DependencyDirection,
  ElementKind,
  GraphLevel,
} from "@/protocol/enums";
import type {
  DependencyPage,
  OutlineNode,
  OutlinePage,
} from "@/protocol/graph";
import { queryKeys } from "./keys";

export const analyzerQueryOptions = <M extends PlainMethod>(
  queryKey: readonly unknown[],
  method: M,
  payload: MethodInput<M>
) =>
  queryOptions<MethodResult<M>>({
    queryFn: () => analyzerRequest(method, payload),
    queryKey,
  });

export const repositoryQueryOptions = <M extends RepositoryMethod>(
  queryKey: readonly unknown[],
  method: M,
  payload: MethodInput<M>,
  handle: string
) =>
  queryOptions<MethodResult<M>>({
    queryFn: () => repositoryRequest(method, payload, handle),
    queryKey,
  });

export const repositoryDescribeQueryOptions = (handle: string) =>
  repositoryQueryOptions(
    queryKeys.repository.describe(handle),
    "repository.describe",
    {},
    handle
  );

export const analysisStatusQueryOptions = (analysisId: string) =>
  analyzerQueryOptions(
    queryKeys.analysisStatus(analysisId),
    "analysis.status",
    {
      analysisId,
    }
  );

export const analysisStartMutationOptions = (handle: string) =>
  mutationOptions({
    mutationFn: (config: MethodInput<"analysis.start">) =>
      repositoryRequest("analysis.start", config, handle),
  });

export interface GraphViewParams {
  readonly level: GraphLevel;

  readonly parentId?: string;
}

export const graphSliceQueryOptions = (
  analysisId: string,
  { level, parentId }: GraphViewParams
) => {
  const params = parentId === undefined ? { level } : { level, parentId };
  return queryOptions({
    queryFn: () => {
      if (import.meta.env.DEV) {
        performance.mark(GRAPH_PERF.request);
      }
      return analyzerRequest("graph.slice", { analysisId, ...params });
    },
    queryKey: queryKeys.structure.slice(analysisId, params),
    select: toGraphView,
  });
};

export const OUTLINE_PAGE_LIMIT = 500;

interface OutlinePageParam {
  readonly pageParam: string | null;
}

const fetchOutlinePage =
  (analysisId: string, parentId: string | null) =>
  ({ pageParam }: OutlinePageParam): Promise<OutlinePage> =>
    analyzerRequest("outline.children", {
      analysisId,
      cursor: pageParam ?? undefined,
      limit: OUTLINE_PAGE_LIMIT,
      parentId,
    });

const flattenOutlinePages = (data: {
  readonly pages: readonly OutlinePage[];
}): OutlineNode[] => data.pages.flatMap((page) => page.items);

export const outlineChildrenQueryOptions = (
  analysisId: string,
  parentId: string | null
) =>
  infiniteQueryOptions({
    getNextPageParam: (last: OutlinePage) => last.nextCursor,
    initialPageParam: null as string | null,
    queryFn: fetchOutlinePage(analysisId, parentId),
    queryKey: queryKeys.structure.outline(analysisId, parentId),
    select: flattenOutlinePages,
  });

export const SEARCH_KINDS: readonly ElementKind[] = ["PACKAGE", "TYPE"];

export const graphSearchQueryOptions = (analysisId: string, query: string) =>
  queryOptions({
    enabled: query !== "",
    placeholderData: keepPreviousData,
    queryFn: () =>
      analyzerRequest("graph.search", {
        analysisId,
        kinds: [...SEARCH_KINDS],
        query,
      }),
    queryKey: queryKeys.structure.search(analysisId, query),
  });

export const DEPENDENCY_PAGE_LIMIT = 500;

export const elementDetailQueryOptions = (
  analysisId: string,
  elementId: string
) =>
  analyzerQueryOptions(
    queryKeys.structure.element(analysisId, elementId),
    "element.describe",
    { analysisId, elementId }
  );

interface DependencyPageParam {
  readonly pageParam: string | null;
}

const fetchDependencyPage =
  (analysisId: string, elementId: string, direction: DependencyDirection) =>
  ({ pageParam }: DependencyPageParam): Promise<DependencyPage> =>
    analyzerRequest("element.dependencies", {
      analysisId,
      cursor: pageParam ?? undefined,
      direction,
      elementId,
      limit: DEPENDENCY_PAGE_LIMIT,
    });

interface DependencyPages {
  readonly pages: readonly DependencyPage[];
}

const SELECT_DEPENDENCY_ROWS = {
  IN: (data: DependencyPages): DependencyRows =>
    flattenDependencyPages(data.pages, "IN"),
  OUT: (data: DependencyPages): DependencyRows =>
    flattenDependencyPages(data.pages, "OUT"),
} as const satisfies Record<
  DependencyDirection,
  (data: DependencyPages) => DependencyRows
>;

export const dependencyPagesQueryOptions = (
  analysisId: string,
  elementId: string,
  direction: DependencyDirection
) =>
  infiniteQueryOptions({
    getNextPageParam: (last: DependencyPage) => last.nextCursor,
    initialPageParam: null as string | null,
    queryFn: fetchDependencyPage(analysisId, elementId, direction),
    queryKey: queryKeys.structure.dependencies(
      analysisId,
      elementId,
      direction,
      DEPENDENCY_PAGE_LIMIT
    ),
    select: SELECT_DEPENDENCY_ROWS[direction],
  });
