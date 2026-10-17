import type {
  Core,
  Css,
  EdgeSingular,
  NodeSingular,
  StylesheetJson,
  StylesheetStyle,
} from "cytoscape";
import type { ElementKind, ResolutionQuality } from "@/protocol/enums";
import type { GraphEdge, GraphNode, GraphSlice } from "@/protocol/graph";
import type { GraphPalette, GraphToken } from "./palette";

export const GRAPH_SCRATCH = "strata";

export interface GraphRuntime {
  readonly dashScale: number;

  readonly hotspotIds: ReadonlySet<string>;

  readonly maxLoc: number;
}

export const DEFAULT_GRAPH_RUNTIME: GraphRuntime = Object.freeze({
  dashScale: 1,
  hotspotIds: new Set<string>(),
  maxLoc: 0,
});

export const SELECTED_CLASS = "strata-selected";

export const ACTIVE_CLASS = "strata-active";

export const GRAPH_STYLE = {
  edge: {
    activeExtraWidth: 1,
    activeZIndex: 10,
    arrowScale: 0.8,
    baseWidth: 1,
    maxWidth: 8,
  },
  node: {
    activeBorderWidth: 3,
    borderWidth: 1,
    hotspotScale: 1.5,
    labelFontSize: 11,
    labelMarginY: 4,
    labelMaxWidth: 160,
    labelMinZoomedFontSize: 8,
    maxDiameter: 64,
    minDiameter: 18,
    selectedBorderWidth: 4,
  },
} as const;

export interface EdgeLine {
  readonly cap: "butt" | "round";

  readonly dashPattern: readonly number[] | null;
}

export const EDGE_LINES = {
  INFERRED: { cap: "butt", dashPattern: [6, 3] },
  RESOLVED: { cap: "butt", dashPattern: null },
  UNRESOLVED_EXTERNAL: { cap: "round", dashPattern: [1.5, 3] },
} as const satisfies Record<ResolutionQuality, EdgeLine>;

export const NODE_SHAPES = {
  FILE: "ellipse",
  METHOD: "ellipse",
  PACKAGE: "round-rectangle",
  TYPE: "ellipse",
} as const satisfies Record<ElementKind, Css.NodeShape>;

const NODE_FILLS = {
  FILE: "--graph-node",
  METHOD: "--graph-node",
  PACKAGE: "--graph-node-package",
  TYPE: "--graph-node",
} as const satisfies Record<ElementKind, GraphToken>;

export const readGraphRuntime = (cy: Core): GraphRuntime =>
  (cy.scratch(GRAPH_SCRATCH) as GraphRuntime | undefined) ??
  DEFAULT_GRAPH_RUNTIME;

export const setGraphRuntime = (
  cy: Core,
  patch: Partial<GraphRuntime>
): void => {
  cy.scratch(GRAPH_SCRATCH, { ...readGraphRuntime(cy), ...patch });
};

export const graphRuntimeForSlice = (
  slice: GraphSlice
): Pick<GraphRuntime, "maxLoc"> => ({
  maxLoc: slice.nodes.reduce((max, node) => Math.max(max, node.loc), 0),
});

export const nodeDiameter = (loc: number, maxLoc: number): number => {
  const { maxDiameter, minDiameter } = GRAPH_STYLE.node;
  if (maxLoc <= 0) {
    return minDiameter;
  }
  const ratio = Math.min(1, Math.log1p(loc) / Math.log1p(maxLoc));
  return minDiameter + (maxDiameter - minDiameter) * ratio;
};

export const edgeWidth = (weight: number): number =>
  Math.min(
    GRAPH_STYLE.edge.maxWidth,
    GRAPH_STYLE.edge.baseWidth + Math.log1p(weight)
  );

const nodeData = (node: NodeSingular): GraphNode => node.data() as GraphNode;
const edgeData = (edge: EdgeSingular): GraphEdge => edge.data() as GraphEdge;

const isHotspot = (node: NodeSingular): boolean =>
  readGraphRuntime(node.cy()).hotspotIds.has(node.id());

const sizedNode = (node: NodeSingular): number => {
  const diameter = nodeDiameter(
    nodeData(node).loc,
    readGraphRuntime(node.cy()).maxLoc
  );
  return isHotspot(node) ? diameter * GRAPH_STYLE.node.hotspotScale : diameter;
};

// O Cytoscape só pula a reaplicação quando o mapper devolve o mesmo valor (===); por isso
// o array fica em cache por escala.
const scaledDashPattern = (
  base: readonly number[]
): ((edge: EdgeSingular) => number[]) => {
  let cached = { pattern: [...base], scale: 1 };
  return (edge) => {
    const scale = readGraphRuntime(edge.cy()).dashScale;
    if (cached.scale !== scale) {
      cached = { pattern: base.map((length) => length * scale), scale };
    }
    return cached.pattern;
  };
};

const nodeRules = (palette: GraphPalette): StylesheetStyle[] => [
  {
    selector: "node",
    style: {
      "background-color": (node: NodeSingular) =>
        isHotspot(node)
          ? palette["--graph-node-hotspot"]
          : palette[NODE_FILLS[nodeData(node).kind]],
      "border-color": palette["--graph-node-border"],
      "border-width": GRAPH_STYLE.node.borderWidth,
      color: palette["--graph-node-label"],
      "font-size": GRAPH_STYLE.node.labelFontSize,
      height: sizedNode,
      label: "data(label)",
      "min-zoomed-font-size": GRAPH_STYLE.node.labelMinZoomedFontSize,
      "text-margin-y": GRAPH_STYLE.node.labelMarginY,
      "text-max-width": `${GRAPH_STYLE.node.labelMaxWidth}px`,
      "text-valign": "bottom",
      "text-wrap": "ellipsis",
      width: sizedNode,
    },
  },
  ...Object.entries(NODE_SHAPES).map(([kind, shape]) => ({
    selector: `node[kind = "${kind}"]`,
    style: { shape },
  })),
];

const edgeRules = (palette: GraphPalette): StylesheetStyle[] => [
  {
    selector: "edge",
    style: {
      "arrow-scale": GRAPH_STYLE.edge.arrowScale,
      "curve-style": "straight",
      "line-color": palette["--graph-edge-structural"],
      "target-arrow-color": palette["--graph-edge-structural"],
      "target-arrow-shape": "triangle",
      width: (edge: EdgeSingular) => edgeWidth(edgeData(edge).weight),
    },
  },
  ...Object.entries(EDGE_LINES).map(([quality, line]) => ({
    selector: `edge[resolution = "${quality}"]`,
    style:
      line.dashPattern === null
        ? { "line-cap": line.cap, "line-style": "solid" as const }
        : {
            "line-cap": line.cap,
            "line-dash-pattern": scaledDashPattern(
              line.dashPattern
            ) as unknown as number[],
            "line-style": "dashed" as const,
          },
  })),
];

const stateRules = (palette: GraphPalette): StylesheetStyle[] => [
  {
    selector: `node.${ACTIVE_CLASS}`,
    style: {
      "border-color": palette["--graph-edge-highlighted"],
      "border-width": GRAPH_STYLE.node.activeBorderWidth,
    },
  },
  {
    selector: `edge.${ACTIVE_CLASS}`,
    style: {
      "line-color": palette["--graph-edge-highlighted"],
      "target-arrow-color": palette["--graph-edge-highlighted"],
      width: (edge: EdgeSingular) =>
        edgeWidth(edgeData(edge).weight) + GRAPH_STYLE.edge.activeExtraWidth,
      "z-index": GRAPH_STYLE.edge.activeZIndex,
    },
  },
  {
    selector: `node.${SELECTED_CLASS}`,
    style: {
      "border-color": palette["--graph-node-selected"],
      "border-width": GRAPH_STYLE.node.selectedBorderWidth,
    },
  },
];

export const buildStylesheet = (palette: GraphPalette): StylesheetJson => [
  ...nodeRules(palette),
  ...edgeRules(palette),
  ...stateRules(palette),
];
