import { elementDisplayLabel } from "@/features/graph/adapter";
import { LOCALE } from "@/i18n/format";
import type { OutlineNodeRow, OutlineRow } from "./use-outline";

export type FocusTarget =
  | { readonly key: string }
  | { readonly after: string; readonly waitingOn: string };

export type KeyIntent =
  | { readonly type: "activate" }
  | { readonly type: "collapse" }
  | { readonly packageId: string; readonly type: "expand" }
  | { readonly index: number; readonly type: "move" };

export interface KeyPress {
  readonly altKey: boolean;
  readonly ctrlKey: boolean;
  readonly key: string;
  readonly metaKey: boolean;
}

export interface TypeaheadBuffer {
  readonly at: number;
  readonly text: string;
}

export const TYPEAHEAD_RESET_MS = 500;

export const EMPTY_TYPEAHEAD: TypeaheadBuffer = {
  at: Number.NEGATIVE_INFINITY,
  text: "",
};

export const isFocusable = (row: OutlineRow | undefined): boolean =>
  row !== undefined && row.type !== "empty" && row.type !== "loading";

const stepFocusable = (
  rows: readonly OutlineRow[],
  from: number,
  step: 1 | -1
): number | null => {
  for (
    let index = from + step;
    index >= 0 && index < rows.length;
    index += step
  ) {
    if (isFocusable(rows[index])) {
      return index;
    }
  }
  return null;
};

export const firstFocusable = (rows: readonly OutlineRow[]): number | null =>
  stepFocusable(rows, -1, 1);

const moveTo = (index: number | null): KeyIntent | null =>
  index === null ? null : { index, type: "move" };

const intentRight = (
  rows: readonly OutlineRow[],
  index: number,
  expandedId: string | null
): KeyIntent | null => {
  const row = rows[index];
  if (row?.type !== "node" || row.level !== 1) {
    return null;
  }
  if (row.node.id === expandedId) {
    const child = rows[index + 1];
    return child?.level === 2 && isFocusable(child) ? moveTo(index + 1) : null;
  }
  return row.expanded === false
    ? { packageId: row.node.id, type: "expand" }
    : null;
};

const intentLeft = (
  rows: readonly OutlineRow[],
  index: number,
  expandedId: string | null
): KeyIntent | null => {
  const row = rows[index];
  if (row?.level === 2) {
    for (let parent = index - 1; parent >= 0; parent -= 1) {
      if (rows[parent]?.key === row.parentId) {
        return moveTo(parent);
      }
    }
    return null;
  }
  return row?.type === "node" && row.node.id === expandedId
    ? { type: "collapse" }
    : null;
};

export const intentOf = (
  key: string,
  rows: readonly OutlineRow[],
  index: number,
  expandedId: string | null
): KeyIntent | null => {
  switch (key) {
    case "ArrowDown":
      return moveTo(stepFocusable(rows, index, 1));
    case "ArrowUp":
      return moveTo(stepFocusable(rows, index, -1));
    case "Home":
      return moveTo(firstFocusable(rows));
    case "End":
      return moveTo(stepFocusable(rows, rows.length, -1));
    case "ArrowRight":
      return intentRight(rows, index, expandedId);
    case "ArrowLeft":
      return intentLeft(rows, index, expandedId);
    case "Enter":
    case " ":
      return { type: "activate" };
    default:
      return null;
  }
};

export const toggleIntent = (
  row: OutlineNodeRow,
  expandedId: string | null
): KeyIntent | null => {
  if (row.node.id === expandedId) {
    return { type: "collapse" };
  }
  return row.expanded === false
    ? { packageId: row.node.id, type: "expand" }
    : null;
};

export const isTypeaheadKey = (press: KeyPress): boolean =>
  press.key.length === 1 &&
  press.key !== " " &&
  press.key !== "*" &&
  !(press.altKey || press.ctrlKey || press.metaKey);

export const extendTypeahead = (
  previous: TypeaheadBuffer,
  key: string,
  now: number
): TypeaheadBuffer => {
  const kept = now - previous.at > TYPEAHEAD_RESET_MS ? "" : previous.text;
  return { at: now, text: `${kept}${key.toLocaleLowerCase(LOCALE)}` };
};

export const typeaheadIntent = (
  rows: readonly OutlineRow[],
  index: number,
  buffer: TypeaheadBuffer
): KeyIntent | null => {
  const from = buffer.text.length === 1 ? index + 1 : index;
  for (let offset = 0; offset < rows.length; offset += 1) {
    const candidate = (from + offset) % rows.length;
    const row = rows[candidate];
    if (
      row?.type === "node" &&
      elementDisplayLabel(row.node.kind, row.node.label)
        .toLocaleLowerCase(LOCALE)
        .startsWith(buffer.text)
    ) {
      return moveTo(candidate);
    }
  }
  return null;
};

export const resolveTabbable = (
  rows: readonly OutlineRow[],
  indexByKey: ReadonlyMap<string, number>,
  candidates: readonly (string | null)[]
): number | null => {
  for (const key of candidates) {
    const index = key === null ? undefined : indexByKey.get(key);
    if (index !== undefined && isFocusable(rows[index])) {
      return index;
    }
  }
  return firstFocusable(rows);
};

export const resolveTarget = (
  target: FocusTarget,
  rows: readonly OutlineRow[],
  indexByKey: ReadonlyMap<string, number>
): number | null => {
  if ("key" in target) {
    return indexByKey.get(target.key) ?? null;
  }
  const before = indexByKey.get(target.after);
  if (before === undefined) {
    return null;
  }
  const next = rows[before + 1];
  if (next === undefined) {
    return indexByKey.has(target.waitingOn) ? null : before;
  }
  return next.key === target.waitingOn || !isFocusable(next)
    ? null
    : before + 1;
};

export const pinIndexes = (
  indexes: readonly number[],
  pinned: readonly (number | null)[]
): number[] => {
  const extra = pinned.filter(
    (index): index is number => index !== null && !indexes.includes(index)
  );
  if (extra.length === 0) {
    return [...indexes];
  }
  return [...new Set([...indexes, ...extra])].sort((a, b) => a - b);
};
