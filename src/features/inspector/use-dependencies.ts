import { useInfiniteQuery } from "@tanstack/react-query";
import { useCallback, useMemo } from "react";
import type { DependencyDirection } from "@/protocol/enums";
import { dependencyPagesQueryOptions } from "@/queries/options";
import {
  type DependencyListItem,
  type DependencyRows,
  dependencyFailureOf,
} from "./inspector-model";

export interface LoadingTailModel {
  readonly index: number;
  readonly state: "loading";
  readonly type: "tail";
}

export interface FailedTailModel {
  readonly index: number;
  readonly retryable: boolean;
  readonly state: "failed";
  readonly title: string | null;
  readonly type: "tail";
}

export type TailModel = FailedTailModel | LoadingTailModel;

export type DependencyTableRow = DependencyListItem | TailModel;

export const TAIL_ROW_KEY = "tail";

export const rowKeyOf = (row: DependencyTableRow): string => {
  switch (row.type) {
    case "row":
      return row.entry.elementId;
    case "externals-heading":
      return "externals:heading";
    case "tail":
      return TAIL_ROW_KEY;
    default:
      return row satisfies never;
  }
};

const NO_CANCEL = { cancelRefetch: false } as const;

export interface Dependencies {
  readonly busy: boolean;

  readonly canFetchMore: boolean;

  readonly error: Error | null;

  readonly fetchMore: () => void;

  readonly list: DependencyRows | undefined;

  readonly loaded: number;

  readonly refreshError: Error | null;

  readonly retryFirst: () => void;

  readonly retryMore: () => void;

  readonly rows: readonly DependencyTableRow[] | undefined;

  readonly status: "error" | "pending" | "success";
}

export const useDependencies = (
  analysisId: string,
  elementId: string,
  direction: DependencyDirection
): Dependencies => {
  const query = useInfiniteQuery(
    dependencyPagesQueryOptions(analysisId, elementId, direction)
  );
  const {
    data,
    error,
    fetchNextPage,
    hasNextPage,
    isError,
    isFetching,
    isFetchingNextPage,
    isRefetchError,
    refetch,
    status,
  } = query;

  const fetchMore = useCallback(() => {
    fetchNextPage(NO_CANCEL);
  }, [fetchNextPage]);

  const retryFirst = useCallback(() => {
    refetch();
  }, [refetch]);

  const retryMore = useCallback(() => {
    if (isRefetchError) {
      refetch();
      return;
    }
    fetchNextPage(NO_CANCEL);
  }, [fetchNextPage, isRefetchError, refetch]);

  const refreshFailed = isRefetchError && !hasNextPage;
  const rows = useMemo((): readonly DependencyTableRow[] | undefined => {
    if (data === undefined) {
      return;
    }
    const loading: LoadingTailModel = {
      index: data.items.length,
      state: "loading",
      type: "tail",
    };
    if (isFetchingNextPage) {
      return [...data.items, loading];
    }
    if (isError && error !== null && !refreshFailed) {
      const message = dependencyFailureOf(error);
      return [
        ...data.items,
        {
          index: data.items.length,
          retryable: message?.retryable ?? false,
          state: "failed",
          title: message?.title ?? null,
          type: "tail",
        },
      ];
    }
    return hasNextPage ? [...data.items, loading] : data.items;
  }, [data, error, hasNextPage, isError, isFetchingNextPage, refreshFailed]);

  return {
    busy: isFetching,
    canFetchMore: hasNextPage && !isFetching && !isError,
    error: data === undefined ? error : null,
    fetchMore,
    list: data,
    loaded: data?.items.length ?? 0,
    refreshError: data !== undefined && refreshFailed ? error : null,
    retryFirst,
    retryMore,
    rows,
    status,
  };
};
