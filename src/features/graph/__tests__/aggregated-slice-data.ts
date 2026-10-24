import type { GraphNode, GraphSlice } from "@/protocol/graph";

export const ALPHA = "pfx:org.acme.alpha";
export const BETA = "pfx:org.acme.beta";
export const GAMMA = "pkg:org.acme.gamma";

export const ALPHA_MEMBER = "pkg:org.acme.alpha.core";

export const AGGREGATED_TOTALS = { edges: 9120, nodes: 1842 } as const;

const MIX = { INFERRED: 0, RESOLVED: 1, UNRESOLVED_EXTERNAL: 0 } as const;

export const ALPHA_NODE: GraphNode = {
  fanIn: 3,
  fanOut: 7,
  id: ALPHA,
  kind: "PACKAGE_PREFIX",
  label: "alpha",
  loc: 5400,
  pagerank: 0.5,
  resolutionMix: MIX,
  typeCount: 120,
};

export const BETA_NODE: GraphNode = {
  fanIn: 1,
  fanOut: 2,
  id: BETA,
  kind: "PACKAGE_PREFIX",
  label: "beta",
  loc: 2100,
  pagerank: 0.3,
  resolutionMix: MIX,
  typeCount: 45,
};

export const GAMMA_NODE: GraphNode = {
  fanIn: 2,
  fanOut: 0,
  id: GAMMA,
  kind: "PACKAGE",
  label: "gamma",
  loc: 300,
  pagerank: 0.2,
  resolutionMix: MIX,
  typeCount: 4,
};

export const AGGREGATED_SLICE: GraphSlice = {
  aggregated: true,
  budget: { edges: 1200, nodes: 300 },
  edges: [
    {
      id: "e_86fb5b80faea8d0b",
      resolution: "RESOLVED",
      source: ALPHA,
      target: BETA,
      types: { IMPORTS: 9 },
      weight: 9,
    },
    {
      id: "e_775262e9c98a9662",
      resolution: "INFERRED",
      source: ALPHA,
      target: GAMMA,
      types: { CALLS: 2, IMPORTS: 1 },
      weight: 2,
    },
    {
      id: "e_b0fa5fc3faf4dd7e",
      resolution: "RESOLVED",
      source: BETA,
      target: GAMMA,
      types: { IMPORTS: 1 },
      weight: 1,
    },
  ],
  level: "PACKAGE",
  nodes: [ALPHA_NODE, BETA_NODE, GAMMA_NODE],
  parentId: null,
  totals: AGGREGATED_TOTALS,
  truncated: true,
};
