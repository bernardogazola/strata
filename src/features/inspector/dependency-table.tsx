import type { ReactNode } from "react";
import type { RevealTarget } from "@/features/shell/repository-search";
import type { DependencyDirection } from "@/protocol/enums";
import type { InspectedKind } from "./inspector-model";

export interface InspectedElement {
  readonly elementId: string;
  readonly kind: InspectedKind;
  readonly packageId: string | null;
}

export interface DependencyTableProps {
  readonly analysisId: string;
  readonly direction: DependencyDirection;
  readonly element: InspectedElement;
  readonly onReveal: (target: RevealTarget) => void;
}

export function DependencyTable(_props: DependencyTableProps): ReactNode {
  return null;
}
