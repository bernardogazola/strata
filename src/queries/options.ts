import { queryOptions } from "@tanstack/react-query";
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
import type { GraphLevel } from "@/protocol/enums";
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
