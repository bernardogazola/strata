import type { DependencyType, ResolutionQuality } from "@/protocol/enums";
import {
  GRAPH_SLICE_BUDGETS,
  type GraphEdge,
  type GraphNode,
  type GraphSlice,
} from "@/protocol/graph";

export interface SyntheticSliceOptions {
  readonly edges: number;
  readonly level: "PACKAGE" | "TYPE";

  readonly nodes: number;

  readonly seed: number;
}

export const SYNTHETIC_TYPE_PARENT_ID = "pkg:synthetic.core";

const ID_DIGITS = 4;
const PAGERANK_DAMPING = 0.85;
const PAGERANK_ITERATIONS = 60;
const PAGERANK_SCALE = 1_000_000;
const LOC_MIN = 20;
const LOC_SPAN = 4001;
const PACKAGE_TYPE_COUNT_MAX = 40;
const TYPE_INCLUSION_PROBABILITY = 0.25;
const OCCURRENCES_MAX = 24;
const ATTEMPTS_PER_EDGE = 50;

const RESOLUTION_CYCLE = 20;
const RESOLVED_SLOTS = 15;
const INFERRED_SLOTS = 4;

const TYPE_WEIGHTS = [
  ["IMPORTS", 1],
  ["EXTENDS", 4],
  ["IMPLEMENTS", 4],
  ["CALLS", 2],
  ["FIELD_TYPE", 3],
  ["PARAMETER_TYPE", 1],
  ["RETURN_TYPE", 2],
  ["ANNOTATION_REFERENCE", 1],
] as const satisfies readonly (readonly [
  Exclude<DependencyType, "MODULE_DEPENDENCY">,
  number,
])[];

const FNV_OFFSET_BASIS = 0xcb_f2_9c_e4_84_22_23_25n;
const FNV_PRIME = 0x1_00_00_00_01_b3n;
const HASH_BITS = 64;
const HEX_DIGITS = 16;
const UTF8 = new TextEncoder();

export const SYNTHETIC_SLICE_GENERATOR = {
  edgeId:
    "e_ + FNV-1a-64 hex of source \\0 target \\0 <first type in enum order>",
  edges:
    "source uniform, target = floor(U² · n) (hub-skewed); no self-loops, no duplicate (source, target)",
  loc: `${LOC_MIN}..${LOC_MIN + LOC_SPAN - 1}, ${LOC_MIN} + floor(U³ · ${LOC_SPAN})`,
  pagerank: `power iteration, d = ${PAGERANK_DAMPING}, ${PAGERANK_ITERATIONS} iterations, dangling mass spread uniformly, mass flows from dependent to dependency; rounded to 6 decimals by largest remainder so that Σ = 1`,
  prng: "mulberry32",
  resolution:
    "exactly 75 % RESOLVED, 20 % INFERRED, 5 % UNRESOLVED_EXTERNAL, by edge position",
  resolutionMix: "Σ weight of the node's outgoing edges, per resolution",
  source: "e2e/support/synthetic-slice.ts",
  typeCount: `1 for a type; 1..${PACKAGE_TYPE_COUNT_MAX} for a package`,
  types: `each of the 8 type-graph dependency types with p = ${TYPE_INCLUSION_PROBABILITY} (at least one), 1..${OCCURRENCES_MAX} occurrences; weight = Σ dependency-type weight × occurrences`,
} as const;

// biome-ignore-start lint/suspicious/noBitwiseOperators: mulberry32 e FNV-1a operam sobre bits

export const mulberry32 = (seed: number): (() => number) => {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d_2b_79_f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4_294_967_296;
  };
};

const fnv1a64Hex = (text: string): string => {
  let hash = FNV_OFFSET_BASIS;
  for (const byte of UTF8.encode(text)) {
    hash = BigInt.asUintN(HASH_BITS, (hash ^ BigInt(byte)) * FNV_PRIME);
  }
  return hash.toString(16).padStart(HEX_DIGITS, "0");
};

// biome-ignore-end lint/suspicious/noBitwiseOperators: fim do mulberry32 e do FNV-1a

const simpleName = (level: SyntheticSliceOptions["level"], index: number) =>
  `${level === "PACKAGE" ? "p" : "T"}${String(index + 1).padStart(ID_DIGITS, "0")}`;

const elementId = (level: SyntheticSliceOptions["level"], index: number) =>
  level === "PACKAGE"
    ? `pkg:synthetic.${simpleName(level, index)}`
    : `type:synthetic.core.${simpleName(level, index)}`;

const resolutionAt = (position: number): ResolutionQuality => {
  const slot = position % RESOLUTION_CYCLE;
  if (slot < RESOLVED_SLOTS) {
    return "RESOLVED";
  }
  return slot < RESOLVED_SLOTS + INFERRED_SLOTS
    ? "INFERRED"
    : "UNRESOLVED_EXTERNAL";
};

interface RawEdge {
  readonly resolution: ResolutionQuality;
  readonly source: number;
  readonly target: number;
  readonly types: GraphEdge["types"];
  readonly weight: number;
}

const drawTypes = (
  random: () => number
): { readonly types: GraphEdge["types"]; readonly weight: number } => {
  const included = TYPE_WEIGHTS.filter(
    () => random() < TYPE_INCLUSION_PROBABILITY
  );
  const chosen =
    included.length > 0
      ? included
      : [TYPE_WEIGHTS[Math.floor(random() * TYPE_WEIGHTS.length)]];
  const counted = chosen.map(
    ([type, typeWeight]) =>
      [
        type,
        typeWeight,
        1 + Math.floor(random() ** 3 * OCCURRENCES_MAX),
      ] as const
  );
  return {
    types: Object.fromEntries(
      counted.map(([type, , occurrences]) => [type, occurrences])
    ),
    weight: counted.reduce(
      (total, [, typeWeight, occurrences]) => total + typeWeight * occurrences,
      0
    ),
  };
};

const drawEdges = (
  random: () => number,
  nodeCount: number,
  edgeCount: number
): RawEdge[] => {
  const seen = new Set<number>();
  const edges: RawEdge[] = [];
  for (
    let attempt = 0;
    edges.length < edgeCount && attempt < edgeCount * ATTEMPTS_PER_EDGE;
    attempt += 1
  ) {
    const source = Math.floor(random() * nodeCount);
    const target = Math.floor(random() ** 2 * nodeCount);
    const pair = source * nodeCount + target;
    if (source === target || seen.has(pair)) {
      continue;
    }
    seen.add(pair);
    edges.push({
      resolution: resolutionAt(edges.length),
      source,
      target,
      ...drawTypes(random),
    });
  }
  if (edges.length < edgeCount) {
    throw new RangeError(
      `syntheticSlice sorteou ${edges.length} de ${edgeCount} arestas distintas sobre ${nodeCount} nós`
    );
  }
  return edges;
};

const powerIteration = (
  nodeCount: number,
  edges: readonly RawEdge[]
): number[] => {
  const outDegree = new Array<number>(nodeCount).fill(0);
  for (const edge of edges) {
    outDegree[edge.source] += 1;
  }
  let rank = new Array<number>(nodeCount).fill(1 / nodeCount);
  for (let iteration = 0; iteration < PAGERANK_ITERATIONS; iteration += 1) {
    const current = rank;
    const dangling = current.reduce(
      (mass, value, index) => (outDegree[index] === 0 ? mass + value : mass),
      0
    );
    const next = new Array<number>(nodeCount).fill(
      (1 - PAGERANK_DAMPING + PAGERANK_DAMPING * dangling) / nodeCount
    );
    for (const edge of edges) {
      next[edge.target] +=
        (PAGERANK_DAMPING * current[edge.source]) / outDegree[edge.source];
    }
    rank = next;
  }
  return rank;
};

const roundToUnitSum = (rank: readonly number[]): number[] => {
  const total = rank.reduce((sum, value) => sum + value, 0);
  const scaled = rank.map((value) => (value / total) * PAGERANK_SCALE);
  const floors = scaled.map(Math.floor);
  const missing =
    PAGERANK_SCALE - floors.reduce((sum, units) => sum + units, 0);
  const bonus = new Set(
    scaled
      .map((value, index) => ({ index, remainder: value - floors[index] }))
      .sort((a, b) => b.remainder - a.remainder || a.index - b.index)
      .slice(0, missing)
      .map(({ index }) => index)
  );
  return floors.map(
    (units, index) => (units + (bonus.has(index) ? 1 : 0)) / PAGERANK_SCALE
  );
};

const toGraphEdge = (edge: RawEdge, ids: readonly string[]): GraphEdge => {
  const source = ids[edge.source];
  const target = ids[edge.target];
  const [firstType] = Object.keys(edge.types);
  return {
    id: `e_${fnv1a64Hex(`${source}\0${target}\0${firstType}`)}`,
    resolution: edge.resolution,
    source,
    target,
    types: edge.types,
    weight: edge.weight,
  };
};

const resolutionMixOf = (
  outgoing: readonly RawEdge[]
): GraphNode["resolutionMix"] => ({
  INFERRED: outgoing
    .filter((edge) => edge.resolution === "INFERRED")
    .reduce((sum, edge) => sum + edge.weight, 0),
  RESOLVED: outgoing
    .filter((edge) => edge.resolution === "RESOLVED")
    .reduce((sum, edge) => sum + edge.weight, 0),
  UNRESOLVED_EXTERNAL: outgoing
    .filter((edge) => edge.resolution === "UNRESOLVED_EXTERNAL")
    .reduce((sum, edge) => sum + edge.weight, 0),
});

const assertWithinBudget = (options: SyntheticSliceOptions): void => {
  const budget = GRAPH_SLICE_BUDGETS[options.level];
  const fitsBudget =
    Number.isInteger(options.nodes) &&
    Number.isInteger(options.edges) &&
    options.nodes >= 2 &&
    options.nodes <= budget.nodes &&
    options.edges >= 0 &&
    options.edges <= budget.edges &&
    options.edges <= options.nodes * (options.nodes - 1);
  if (!fitsBudget) {
    throw new RangeError(
      `syntheticSlice não consegue montar ${options.nodes} nós / ${options.edges} arestas em ${options.level} (orçamento ${budget.nodes} / ${budget.edges})`
    );
  }
};

export const syntheticSlice = (options: SyntheticSliceOptions): GraphSlice => {
  assertWithinBudget(options);
  const { level, nodes: nodeCount, edges: edgeCount, seed } = options;
  const random = mulberry32(seed);
  const rawEdges = drawEdges(random, nodeCount, edgeCount);
  const pagerank = roundToUnitSum(powerIteration(nodeCount, rawEdges));
  const ids = Array.from({ length: nodeCount }, (_, index) =>
    elementId(level, index)
  );

  const outgoing = ids.map(() => [] as RawEdge[]);
  const fanIn = new Array<number>(nodeCount).fill(0);
  for (const edge of rawEdges) {
    outgoing[edge.source].push(edge);
    fanIn[edge.target] += 1;
  }

  const nodes = ids.map((id, index): GraphNode => {
    const loc = LOC_MIN + Math.floor(random() ** 3 * LOC_SPAN);
    const typeCount =
      level === "PACKAGE"
        ? 1 + Math.floor(random() ** 2 * PACKAGE_TYPE_COUNT_MAX)
        : 1;
    return {
      fanIn: fanIn[index],
      fanOut: outgoing[index].length,
      id,
      kind: level,
      label: simpleName(level, index),
      loc,
      pagerank: pagerank[index],
      resolutionMix: resolutionMixOf(outgoing[index]),
      typeCount,
    };
  });

  return {
    aggregated: false,
    budget: { ...GRAPH_SLICE_BUDGETS[level] },
    edges: rawEdges.map((edge) => toGraphEdge(edge, ids)),
    level,
    nodes,
    parentId: level === "PACKAGE" ? null : SYNTHETIC_TYPE_PARENT_ID,
    totals: { edges: edgeCount, nodes: nodeCount },
    truncated: false,
  };
};
