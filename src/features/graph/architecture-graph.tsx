import type { ElementsDefinition } from "cytoscape";
import type { GraphSlice } from "@/protocol/graph";

export interface ArchitectureGraphProps {
  readonly elements: ElementsDefinition | null;
  readonly label: string;
  readonly onExpand: (elementId: string) => void;
  readonly onRenderError: (error: Error) => void;
  readonly onSelect: (elementId: string | null) => void;
  readonly selectedElementId: string | null;
  readonly slice: GraphSlice | null;
}

export const GRAPH_PERF = {
  ready: "strata:graph:ready",
  render: "strata:graph:render",
  request: "strata:graph:request",
} as const;

export function ArchitectureGraph({ label }: ArchitectureGraphProps) {
  return (
    <div className="relative h-full w-full" data-graph-state="idle">
      <div
        aria-label={label}
        className="absolute inset-0"
        role="img"
        style={{ padding: 0 }}
      />
    </div>
  );
}
