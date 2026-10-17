import type {
  EdgeDefinition,
  ElementsDefinition,
  NodeDefinition,
} from "cytoscape";
import { t } from "@/i18n/messages";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";
import {
  GRAPH_SLICE_BUDGETS,
  type GraphEdge,
  type GraphNode,
  type GraphSlice,
} from "@/protocol/graph";

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

const fail = (violation: GraphSliceViolation, message: string): never => {
  throw new GraphSliceViolationError(violation, message);
};

const assertBudget = ({ budget, edges, level, nodes }: GraphSlice): void => {
  if (nodes.length > budget.nodes) {
    fail(
      "NODES_OVER_BUDGET",
      `${nodes.length} nós passam do orçamento de ${budget.nodes} da fatia`
    );
  }
  if (edges.length > budget.edges) {
    fail(
      "EDGES_OVER_BUDGET",
      `${edges.length} arestas passam do orçamento de ${budget.edges} da fatia`
    );
  }

  const ceiling = GRAPH_SLICE_BUDGETS[level];
  if (budget.nodes > ceiling.nodes || budget.edges > ceiling.edges) {
    fail(
      "BUDGET_OVER_LEVEL_CEILING",
      `o orçamento ${budget.nodes}/${budget.edges} da fatia passa do teto ${ceiling.nodes}/${ceiling.edges} do nível ${level}`
    );
  }
};

const assertTotals = ({
  aggregated,
  edges,
  nodes,
  totals,
  truncated,
}: GraphSlice): void => {
  if (nodes.length > totals.nodes || edges.length > totals.edges) {
    fail(
      "COUNTS_OVER_TOTALS",
      `${nodes.length}/${edges.length} elementos passam dos totais declarados de ${totals.nodes}/${totals.edges}`
    );
  }

  const isComplete = !(truncated || aggregated);
  const matchesTotals =
    nodes.length === totals.nodes && edges.length === totals.edges;
  if (isComplete && !matchesTotals) {
    fail(
      "COUNTS_BELOW_TOTALS",
      `${nodes.length}/${edges.length} elementos ficam abaixo dos totais de ${totals.nodes}/${totals.edges} sem truncated nem aggregated`
    );
  }
};

const assertParent = ({ level, parentId }: GraphSlice): void => {
  if ((level === "PACKAGE") !== (parentId === null)) {
    fail(
      "PARENT_MISMATCH",
      `uma fatia de nível ${level} ${parentId === null ? "não tem" : "tem"} pai`
    );
  }
};

const assertIdentity = ({ edges, nodes }: GraphSlice): void => {
  const nodeIds = new Set<string>();
  for (const [index, node] of nodes.entries()) {
    if (nodeIds.has(node.id)) {
      fail("DUPLICATE_ID", `o nó ${index} repete um id que já está na fatia`);
    }
    nodeIds.add(node.id);
  }

  const edgeIds = new Set<string>();
  for (const [index, edge] of edges.entries()) {
    if (nodeIds.has(edge.id) || edgeIds.has(edge.id)) {
      fail(
        "DUPLICATE_ID",
        `a aresta ${index} repete um id que já está na fatia`
      );
    }
    edgeIds.add(edge.id);
  }

  for (const [index, edge] of edges.entries()) {
    if (!(nodeIds.has(edge.source) && nodeIds.has(edge.target))) {
      fail(
        "DANGLING_ENDPOINT",
        `a aresta ${index} tem uma ponta fora da fatia`
      );
    }
  }
};

const toNodeLabel = (node: GraphNode): string => {
  const label = clampText(sanitizeUntrusted(node.label), GRAPH_LABEL_MAX_CHARS);
  return label === "" && node.kind === "PACKAGE"
    ? t.graph.defaultPackage
    : label;
};

const toNodeDefinition = (node: GraphNode): NodeDefinition => {
  const data: GraphNode = {
    fanIn: node.fanIn,
    fanOut: node.fanOut,
    id: node.id,
    kind: node.kind,
    label: toNodeLabel(node),
    loc: node.loc,
    pagerank: node.pagerank,
    resolutionMix: {
      INFERRED: node.resolutionMix.INFERRED,
      RESOLVED: node.resolutionMix.RESOLVED,
      UNRESOLVED_EXTERNAL: node.resolutionMix.UNRESOLVED_EXTERNAL,
    },
    typeCount: node.typeCount,
  };
  return { data, group: "nodes" };
};

const toEdgeDefinition = (edge: GraphEdge): EdgeDefinition => {
  const data: GraphEdge = {
    id: edge.id,
    resolution: edge.resolution,
    source: edge.source,
    target: edge.target,
    types: { ...edge.types },
    weight: edge.weight,
  };
  return { data, group: "edges" };
};

export const toGraphElements = (slice: GraphSlice): ElementsDefinition => {
  assertBudget(slice);
  assertTotals(slice);
  assertParent(slice);
  assertIdentity(slice);

  return {
    edges: slice.edges.map(toEdgeDefinition),
    nodes: slice.nodes.map(toNodeDefinition),
  };
};

export const toGraphView = (slice: GraphSlice): GraphView => ({
  elements: toGraphElements(slice),
  slice,
});
