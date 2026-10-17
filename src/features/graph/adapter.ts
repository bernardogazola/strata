import type { ElementsDefinition } from "cytoscape";
import type { GraphSlice } from "@/protocol/graph";

export type GraphSliceViolation =
  | "BUDGET_OVER_LEVEL_CEILING"
  | "COUNTS_BELOW_TOTALS"
  | "COUNTS_OVER_TOTALS"
  | "DANGLING_ENDPOINT"
  | "DUPLICATE_ID"
  | "EDGES_OVER_BUDGET"
  | "NODES_OVER_BUDGET"
  | "PARENT_MISMATCH";

export class GraphSliceViolationError extends Error {
  readonly violation: GraphSliceViolation;

  constructor(violation: GraphSliceViolation, message: string) {
    super(message);
    this.name = "GraphSliceViolationError";
    this.violation = violation;
  }
}

export const GRAPH_LABEL_MAX_CHARS = 80;

export interface GraphView {
  readonly elements: ElementsDefinition;
  readonly slice: GraphSlice;
}

export const toGraphElements = (_slice: GraphSlice): ElementsDefinition => {
  throw new Error("toGraphElements: ainda não implementado");
};

export const toGraphView = (slice: GraphSlice): GraphView => ({
  elements: toGraphElements(slice),
  slice,
});
