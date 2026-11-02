import type {
  ConcentricLayoutOptions,
  NodeSingular,
  Position,
} from "cytoscape";
import type { GraphSlice } from "@/protocol/graph";
import { GRAPH_STYLE } from "./stylesheet";

export const CONCENTRIC_RINGS = 8;

export const FIT_PADDING_PX = 30;

export const MAX_FIT_ZOOM = 1.5;

export const VISIBLE_EDGE_PX = 16;

export const MIN_NODE_SPACING_PX =
  GRAPH_STYLE.node.labelMarginY +
  GRAPH_STYLE.node.labelFontSize +
  2 * GRAPH_STYLE.node.labelHaloWidth +
  VISIBLE_EDGE_PX;

export const SMALL_LEVEL_MAX_NODES = 2;

const QUARTERS_PER_TURN = 4;

const LAST_RING = CONCENTRIC_RINGS - 1;

export const pagerankRing = (pagerank: number, maxPagerank: number): number => {
  if (pagerank <= 0 || maxPagerank <= 0) {
    return LAST_RING;
  }

  // Metades sucessivas em vez de Math.log2: dividir por 2 é exato em IEEE 754, e o anel
  // não varia entre Chromium e WebKit.
  let ring = 0;
  let threshold = maxPagerank / 2;
  while (ring < LAST_RING && pagerank <= threshold) {
    ring += 1;
    threshold /= 2;
  }
  return ring;
};

const quarterTurnsByRing = (
  rings: ReadonlyMap<string, number>
): ReadonlyMap<number, number> => {
  const ringOfEachNode = [...rings.values()];
  const levels = [...new Set(ringOfEachNode)].sort(
    (left, right) => left - right
  );
  return new Map(
    levels.map((ring, index) => {
      const size = ringOfEachNode.filter((value) => value === ring).length;
      const turns =
        size <= SMALL_LEVEL_MAX_NODES ? index % QUARTERS_PER_TURN : 0;
      return [ring, turns];
    })
  );
};

const turnAbout = (
  centre: Position,
  position: Position,
  turns: number
): Position => {
  let dx = position.x - centre.x;
  let dy = position.y - centre.y;
  for (let turn = 0; turn < turns; turn += 1) {
    [dx, dy] = [-dy, dx];
  }
  return { x: centre.x + dx, y: centre.y + dy };
};

export const concentricLayout = (
  slice: GraphSlice
): ConcentricLayoutOptions => {
  const maxPagerank = slice.nodes.reduce(
    (max, node) => Math.max(max, node.pagerank),
    0
  );
  const rings = new Map(
    slice.nodes.map((node) => [
      node.id,
      pagerankRing(node.pagerank, maxPagerank),
    ])
  );
  const turnsByRing = quarterTurnsByRing(rings);
  const ringOf = (node: NodeSingular): number =>
    rings.get(node.id()) ?? LAST_RING;

  return {
    animate: false,
    avoidOverlap: true,
    concentric: (node: NodeSingular) => CONCENTRIC_RINGS - ringOf(node),
    fit: true,
    levelWidth: () => 1,
    minNodeSpacing: MIN_NODE_SPACING_PX,
    name: "concentric",
    nodeDimensionsIncludeLabels: false,
    padding: FIT_PADDING_PX,
    transform: (node: NodeSingular, position: Position): Position => {
      const turns = turnsByRing.get(ringOf(node)) ?? 0;
      if (turns === 0) {
        return position;
      }
      const centre = { x: node.cy().width() / 2, y: node.cy().height() / 2 };
      return turnAbout(centre, position, turns);
    },
  };
};
