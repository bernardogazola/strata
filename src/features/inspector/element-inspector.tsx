import type { ReactNode } from "react";
import type {
  InspectorTab,
  RevealTarget,
} from "@/features/shell/repository-search";
import type { DependencyDirection } from "@/protocol/enums";
import type { GraphViewParams } from "@/queries/options";

export interface ElementInspectorProps {
  readonly analysisId: string;

  readonly caveat?: ReactNode;

  readonly direction: DependencyDirection;

  readonly focus: string | null;

  readonly onExpand: (packageId: string) => void;

  readonly onReveal: (target: RevealTarget) => void;

  readonly onSelectDirection: (direction: DependencyDirection) => void;

  readonly onSelectTab: (tab: InspectorTab) => void;

  readonly tab: InspectorTab;

  readonly view: GraphViewParams;
}

export function ElementInspector(_props: ElementInspectorProps): ReactNode {
  return null;
}
