import { useInfiniteQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo } from "react";
import type { OutlineNode } from "@/protocol/graph";
import {
  type GraphViewParams,
  outlineChildrenQueryOptions,
} from "@/queries/options";

export interface OutlineLevel {
  readonly failed: boolean;
  readonly hasNextPage: boolean;

  readonly nodes: readonly OutlineNode[] | undefined;
}

export interface ExpandedOutlineLevel extends OutlineLevel {
  readonly parentId: string;
}

interface OutlineRowBase {
  readonly key: string;
  readonly level: 1 | 2;

  readonly parentId: string | null;
}

export interface OutlineNodeRow extends OutlineRowBase {
  readonly expanded: boolean | undefined;
  readonly node: OutlineNode;

  readonly posInSet: number;

  readonly setSize: number;
  readonly type: "node";
}

export interface OutlineMoreRow extends OutlineRowBase {
  readonly posInSet: number;
  readonly setSize: number;

  readonly shown: number;

  readonly total: number | null;
  readonly type: "more";
}

export interface OutlineErrorRow extends OutlineRowBase {
  readonly type: "error";
}

export interface OutlineStatusRow extends OutlineRowBase {
  readonly type: "empty" | "loading";
}

export type OutlineRow =
  | OutlineErrorRow
  | OutlineMoreRow
  | OutlineNodeRow
  | OutlineStatusRow;

const ROOT_OWNER = "root";

const UNKNOWN_SET_SIZE = -1;

const levelTail = (
  level: OutlineLevel,
  rowLevel: 1 | 2,
  parentId: string | null,
  setSize: number,
  total: number | null
): OutlineRow[] => {
  const owner = parentId ?? ROOT_OWNER;
  if (level.failed) {
    return [
      { key: `error:${owner}`, level: rowLevel, parentId, type: "error" },
    ];
  }
  if (!level.hasNextPage) {
    return [];
  }
  const shown = level.nodes?.length ?? 0;
  return [
    {
      key: `more:${owner}`,
      level: rowLevel,
      parentId,
      posInSet: shown + 1,
      setSize,
      shown,
      total,
      type: "more",
    },
  ];
};

const childRows = (
  level: ExpandedOutlineLevel,
  childCount: number
): OutlineRow[] => {
  const { nodes, parentId } = level;
  if (nodes === undefined) {
    const type = level.failed ? "error" : "loading";
    return [{ key: `${type}:${parentId}`, level: 2, parentId, type }];
  }
  const setSize = level.hasNextPage ? childCount : nodes.length;
  const rows = nodes.map(
    (node, index): OutlineNodeRow => ({
      expanded: undefined,
      key: node.id,
      level: 2,
      node,
      parentId,
      posInSet: index + 1,
      setSize,
      type: "node",
    })
  );
  const settledEmpty =
    nodes.length === 0 && !level.hasNextPage && !level.failed;
  return [
    ...rows,
    ...(settledEmpty
      ? [
          {
            key: `empty:${parentId}`,
            level: 2,
            parentId,
            type: "empty",
          } as const,
        ]
      : []),
    ...levelTail(level, 2, parentId, setSize, childCount),
  ];
};

export const flattenOutline = (
  root: OutlineLevel,
  expanded: ExpandedOutlineLevel | null
): readonly OutlineRow[] => {
  const { nodes } = root;
  if (nodes === undefined) {
    return [];
  }
  const setSize = root.hasNextPage ? UNKNOWN_SET_SIZE : nodes.length;
  const rows = nodes.flatMap((node, index): OutlineRow[] => {
    const open = expanded !== null && node.id === expanded.parentId;
    const row: OutlineNodeRow = {
      expanded: node.childCount > 0 ? open : undefined,
      key: node.id,
      level: 1,
      node,
      parentId: null,
      posInSet: index + 1,
      setSize,
      type: "node",
    };
    return expanded !== null && open
      ? [row, ...childRows(expanded, node.childCount)]
      : [row];
  });
  return [...rows, ...levelTail(root, 1, null, setSize, null)];
};

export interface OutlineModel {
  readonly busy: boolean;

  readonly expandedId: string | null;

  readonly loadMore: (parentId: string | null) => void;

  readonly retry: (parentId: string | null) => void;

  readonly rootError: Error | null;
  readonly rootStatus: "error" | "pending" | "success";
  readonly rows: readonly OutlineRow[];
}

interface LevelQuery {
  readonly data: readonly OutlineNode[] | undefined;
  readonly fetchNextPage: () => unknown;
  readonly hasNextPage: boolean;
  readonly isError: boolean;
  readonly isFetching: boolean;
  readonly isFetchNextPageError: boolean;
  readonly refetch: () => unknown;
}

const useFetchUntilFound = (level: LevelQuery, sought: string | null) => {
  const { data, fetchNextPage, hasNextPage, isError, isFetching } = level;
  const missing =
    sought !== null &&
    data !== undefined &&
    !data.some((node) => node.id === sought);
  useEffect(() => {
    if (missing && hasNextPage && !isFetching && !isError) {
      fetchNextPage();
    }
  }, [fetchNextPage, hasNextPage, isError, isFetching, missing]);
};

interface LevelActions {
  readonly loadMore: () => void;
  readonly retry: () => void;
}

const useLevelActions = ({
  fetchNextPage,
  isFetching,
  isFetchNextPageError,
  refetch,
}: LevelQuery): LevelActions => {
  const loadMore = useCallback(() => {
    if (!isFetching) {
      fetchNextPage();
    }
  }, [fetchNextPage, isFetching]);
  const retry = useCallback(() => {
    if (isFetchNextPageError) {
      loadMore();
      return;
    }
    refetch();
  }, [isFetchNextPageError, loadMore, refetch]);
  return { loadMore, retry };
};

export function useOutline(
  analysisId: string,
  view: GraphViewParams,
  focus: string | null
): OutlineModel {
  const expandedId = view.level === "TYPE" ? (view.parentId ?? null) : null;
  const root = useInfiniteQuery(outlineChildrenQueryOptions(analysisId, null));
  const children = useInfiniteQuery({
    ...outlineChildrenQueryOptions(analysisId, expandedId ?? ""),
    enabled: expandedId !== null,
  });

  useFetchUntilFound(root, expandedId ?? focus);
  useFetchUntilFound(children, expandedId === null ? null : focus);

  const { loadMore: loadMoreRoot, retry: retryRoot } = useLevelActions(root);
  const { loadMore: loadMoreChildren, retry: retryChildren } =
    useLevelActions(children);
  const loadMore = useCallback(
    (parentId: string | null) =>
      parentId === null ? loadMoreRoot() : loadMoreChildren(),
    [loadMoreChildren, loadMoreRoot]
  );
  const retry = useCallback(
    (parentId: string | null) =>
      parentId === null ? retryRoot() : retryChildren(),
    [retryChildren, retryRoot]
  );

  const {
    data: rootNodes,
    hasNextPage: rootHasMore,
    isError: rootFailed,
  } = root;
  const {
    data: childNodes,
    hasNextPage: childrenHaveMore,
    isError: childrenFailed,
  } = children;
  const rows = useMemo(
    () =>
      flattenOutline(
        { failed: rootFailed, hasNextPage: rootHasMore, nodes: rootNodes },
        expandedId === null
          ? null
          : {
              failed: childrenFailed,
              hasNextPage: childrenHaveMore,
              nodes: childNodes,
              parentId: expandedId,
            }
      ),
    [
      childNodes,
      childrenFailed,
      childrenHaveMore,
      expandedId,
      rootFailed,
      rootHasMore,
      rootNodes,
    ]
  );

  return {
    busy: root.isFetching || children.isFetching,
    expandedId,
    loadMore,
    retry,
    rootError: root.error,
    rootStatus: root.status,
    rows,
  };
}
