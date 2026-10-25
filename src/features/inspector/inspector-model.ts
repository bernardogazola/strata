import type {
  DependencyDirection,
  DependencyType,
  ElementKind,
} from "@/protocol/enums";
import type { DependencyEntry, DependencyPage } from "@/protocol/graph";

export const isInspectableId = (
  id: string | null | undefined
): id is string => {
  throw new Error(`não implementado (${String(id)})`);
};

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

export const flattenDependencyPages = (
  pages: readonly DependencyPage[],
  direction: DependencyDirection
): DependencyRows => {
  throw new Error(`não implementado (${pages.length} pages, ${direction})`);
};

export const verbsOf = (
  types: DependencyEntry["types"],
  direction: DependencyDirection
): string => {
  throw new Error(
    `não implementado (${Object.keys(types).join(",")}, ${direction})`
  );
};

export type DependencyTypeOrder = readonly DependencyType[];

export const packageLineOf = (
  entry: DependencyEntry,
  inspectedPackageId: string | null
): string | null => {
  throw new Error(
    `não implementado (${entry.elementId}, ${String(inspectedPackageId)})`
  );
};

export type InspectedKind = Extract<ElementKind, "PACKAGE" | "TYPE">;
