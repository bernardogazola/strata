import { queryOptions } from "@tanstack/react-query";
import {
  analyzerRequest,
  type MethodInput,
  type MethodResult,
  type PlainMethod,
  type RepositoryMethod,
  repositoryRequest,
} from "@/bridge/analyzer";

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
