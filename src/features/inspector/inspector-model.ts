import {
  dependencyTypeLabels,
  dependencyTypePassiveLabels,
} from "@/i18n/labels";
import {
  type DependencyDirection,
  type DependencyType,
  dependencyTypeSchema,
  type ElementKind,
} from "@/protocol/enums";
import type { DependencyEntry, DependencyPage } from "@/protocol/graph";
import { packageOrTypeIdSchema } from "@/protocol/primitives";

const PACKAGE_ID_PREFIX = "pkg:";

export const isInspectableId = (id: string | null | undefined): id is string =>
  typeof id === "string" && packageOrTypeIdSchema.safeParse(id).success;

export interface DependencyRowModel {
  readonly entry: DependencyEntry;
  readonly index: number;
  readonly type: "row";
}

export interface ExternalsHeadingModel {
  readonly count: number;
  readonly index: number;
  readonly type: "externals-heading";
}

export type DependencyListItem = DependencyRowModel | ExternalsHeadingModel;

export interface DependencyRows {
  readonly items: readonly DependencyListItem[];
  readonly reachable: number;
  readonly totals: DependencyPage["totals"];
}

export class DependencyPageContractError extends Error {
  readonly violation:
    | "EMPTY_PAGE_WITH_CURSOR"
    | "INTERNAL_AFTER_EXTERNAL"
    | "TOTALS_CHANGED";

  constructor(violation: DependencyPageContractError["violation"]) {
    super(`contrato da página de dependências: ${violation}`);
    this.name = "DependencyPageContractError";
    this.violation = violation;
  }
}

const NO_TOTALS: DependencyPage["totals"] = {
  in: 0,
  out: 0,
  outExternal: null,
};

const sameTotals = (
  a: DependencyPage["totals"],
  b: DependencyPage["totals"]
): boolean =>
  a.in === b.in && a.out === b.out && a.outExternal === b.outExternal;

const assertPage = (
  page: DependencyPage,
  totals: DependencyPage["totals"]
): void => {
  if (!sameTotals(page.totals, totals)) {
    throw new DependencyPageContractError("TOTALS_CHANGED");
  }
  if (page.items.length === 0 && page.nextCursor !== null) {
    throw new DependencyPageContractError("EMPTY_PAGE_WITH_CURSOR");
  }
};

const reachableOf = (
  totals: DependencyPage["totals"],
  direction: DependencyDirection
): number => {
  if (direction === "IN") {
    return totals.in;
  }
  const external = totals.outExternal ?? 0;
  return totals.out + external + (external > 0 ? 1 : 0);
};

export const flattenDependencyPages = (
  pages: readonly DependencyPage[],
  direction: DependencyDirection
): DependencyRows => {
  const [first] = pages;
  if (first === undefined) {
    return { items: [], reachable: 0, totals: NO_TOTALS };
  }
  const { totals } = first;
  const externalCount = totals.outExternal ?? 0;
  const items: DependencyListItem[] = [];
  let seenExternal = false;
  for (const page of pages) {
    assertPage(page, totals);
    for (const entry of page.items) {
      if (entry.isExternal && !seenExternal) {
        seenExternal = true;
        items.push({
          count: externalCount,
          index: items.length,
          type: "externals-heading",
        });
      } else if (!entry.isExternal && seenExternal) {
        throw new DependencyPageContractError("INTERNAL_AFTER_EXTERNAL");
      }
      items.push({ entry, index: items.length, type: "row" });
    }
  }
  return { items, reachable: reachableOf(totals, direction), totals };
};

export const verbsOf = (
  types: DependencyEntry["types"],
  direction: DependencyDirection
): string => {
  const labels =
    direction === "OUT" ? dependencyTypePassiveLabels : dependencyTypeLabels;
  return dependencyTypeSchema.options
    .filter((type) => types[type] !== undefined)
    .map((type) => labels[type])
    .join(", ");
};

export type DependencyTypeOrder = readonly DependencyType[];

export const packageLineOf = (
  entry: DependencyEntry,
  inspectedPackageId: string | null
): string | null => {
  const { isExternal, kind, packageId } = entry;
  if (
    isExternal ||
    kind !== "TYPE" ||
    packageId === null ||
    packageId === inspectedPackageId
  ) {
    return null;
  }
  return packageId.slice(PACKAGE_ID_PREFIX.length);
};

export type InspectedKind = Extract<ElementKind, "PACKAGE" | "TYPE">;
