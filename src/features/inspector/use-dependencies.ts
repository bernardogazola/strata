import { useInfiniteQuery } from "@tanstack/react-query";
import { useCallback, useMemo } from "react";
import type { DependencyDirection } from "@/protocol/enums";
import { dependencyPagesQueryOptions } from "@/queries/options";
import {
  type DependencyListItem,
  DependencyPageContractError,
  type DependencyRows,
} from "./inspector-model";

export interface LoadingTailModel {
  readonly index: number;
  readonly type: "tail-loading";
}

export interface ErrorTailModel {
  readonly index: number;
  readonly retryable: boolean;
  readonly type: "tail-error";
}

export type DependencyTableRow =
  | DependencyListItem
  | ErrorTailModel
  | LoadingTailModel;

export const rowKeyOf = (row: DependencyTableRow): string => {
  switch (row.type) {
    case "row":
      return row.entry.elementId;
    case "externals-heading":
      return "externals:heading";
    case "tail-loading":
      return "tail:loading";
    case "tail-error":
      return "tail:error";
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

  const contractBroken = error instanceof DependencyPageContractError;
  const rows = useMemo((): readonly DependencyTableRow[] | undefined => {
    if (data === undefined) {
      return;
    }
    const index = data.items.length;
    if (isFetchingNextPage) {
      return [...data.items, { index, type: "tail-loading" }];
    }
    if (isError) {
      return [
        ...data.items,
        { index, retryable: !contractBroken, type: "tail-error" },
      ];
    }
    return hasNextPage
      ? [...data.items, { index, type: "tail-loading" }]
      : data.items;
  }, [contractBroken, data, hasNextPage, isError, isFetchingNextPage]);

  return {
    busy: isFetching,
    canFetchMore: hasNextPage && !isFetching && !isError,
    error: data === undefined ? error : null,
    fetchMore,
    list: data,
    loaded: data?.items.length ?? 0,
    retryFirst,
    retryMore,
    rows,
    status,
  };
};
