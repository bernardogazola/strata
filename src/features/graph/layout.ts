import type { ConcentricLayoutOptions, NodeSingular } from "cytoscape";
import type { GraphSlice } from "@/protocol/graph";

export const CONCENTRIC_RINGS = 8;

export const FIT_PADDING_PX = 30;

export const MAX_FIT_ZOOM = 1.5;

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

  return {
    animate: false,
    avoidOverlap: true,
    concentric: (node: NodeSingular) =>
      CONCENTRIC_RINGS - (rings.get(node.id()) ?? LAST_RING),
    fit: true,
    levelWidth: () => 1,
    name: "concentric",
    nodeDimensionsIncludeLabels: false,
    padding: FIT_PADDING_PX,
  };
};
