import type { DependencyTableRow } from "./use-dependencies";

export const rowIndexOf = (target: EventTarget | null): number | null => {
  if (!(target instanceof Element)) {
    return null;
  }
  const row = target.closest<HTMLElement>("tr[data-index]");
  return row === null ? null : Number(row.dataset.index);
};

export const highlightIdOf = (target: Element): string | null =>
  target.closest<HTMLElement>("[data-highlight-id]")?.dataset.highlightId ??
  null;

export const hasControl = (row: DependencyTableRow | undefined): boolean =>
  row?.type === "row" ||
  (row?.type === "tail" && (row.state === "loading" || row.retryable));

export const isFocusable = (row: DependencyTableRow | undefined): boolean =>
  row?.type === "row" ||
  (row?.type === "tail" && row.state === "failed" && row.retryable);

export type FocusTarget = { readonly key: string } | { readonly after: string };

export const resolveTarget = (
  target: FocusTarget,
  rows: readonly DependencyTableRow[],
  indexByKey: ReadonlyMap<string, number>
): number | null => {
  if ("key" in target) {
    return indexByKey.get(target.key) ?? null;
  }
  const before = indexByKey.get(target.after);
  if (before === undefined) {
    return null;
  }
  for (let at = before + 1; at < rows.length; at += 1) {
    const row = rows[at];
    if (row?.type === "row") {
      return at;
    }
    if (row?.type === "tail") {
      return hasControl(row) ? null : before;
    }
  }
  return before;
};

const lastRowIndexOf = (rows: readonly DependencyTableRow[]): number => {
  for (let at = rows.length - 1; at >= 0; at -= 1) {
    if (rows[at]?.type === "row") {
      return at;
    }
  }
  return -1;
};

export const keyTargetOf = (
  key: string,
  index: number,
  rows: readonly DependencyTableRow[]
): number | null | undefined => {
  const step = (from: number, delta: number): number | null => {
    for (let at = from + delta; at >= 0 && at < rows.length; at += delta) {
      if (isFocusable(rows[at])) {
        return at;
      }
    }
    return null;
  };
  switch (key) {
    case "ArrowDown":
      return step(index, 1);
    case "ArrowUp":
      return step(index, -1);
    case "Home":
      return rows.findIndex((row) => row.type === "row");
    case "End":
      return lastRowIndexOf(rows);
    default:
      return;
  }
};
