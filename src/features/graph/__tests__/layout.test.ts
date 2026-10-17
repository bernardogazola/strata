import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import cytoscape, { type NodeSingular } from "cytoscape";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  test,
} from "vitest";
import {
  GRAPH_SLICE_BUDGETS,
  type GraphSlice,
  graphSliceSchema,
} from "@/protocol/graph";
import { installCanvasStub } from "@/test/canvas";
import { toGraphElements } from "../adapter";
import {
  CONCENTRIC_RINGS,
  concentricLayout,
  FIT_PADDING_PX,
  pagerankRing,
} from "../layout";
import { GRAPH_TOKENS, type GraphPalette } from "../palette";
import {
  buildStylesheet,
  graphRuntimeForSlice,
  setGraphRuntime,
} from "../stylesheet";

const FIXTURES = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../../protocol/fixtures"
);

const readSliceFixture = async (stem: string): Promise<GraphSlice> => {
  const text = await readFile(path.join(FIXTURES, `${stem}.jsonl`), "utf8");
  const response = text
    .split("\n")
    .filter((line) => line.length > 0)
    .map((line) => JSON.parse(line) as { kind: string; payload: unknown })
    .find((frame) => frame.kind === "response");
  return graphSliceSchema.parse(response?.payload);
};

const CONTAINER_WIDTH = 1280;
const CONTAINER_HEIGHT = 800;

const PALETTE = Object.fromEntries(
  GRAPH_TOKENS.map((token) => [token, "#808080"])
) as GraphPalette;

const skewedSlice = (count: number): GraphSlice => {
  const raw = Array.from({ length: count }, (_, index) => 1 / (index + 1) ** 2);
  const sum = raw.reduce((total, value) => total + value, 0);
  const nodes = raw.map((value, index) => ({
    fanIn: 0,
    fanOut: 0,
    id: `pkg:synthetic.p${String(index).padStart(4, "0")}`,
    kind: "PACKAGE" as const,
    label: `p${index}`,
    loc: 100 + index * 37,
    pagerank: value / sum,
    resolutionMix: { INFERRED: 0, RESOLVED: 1, UNRESOLVED_EXTERNAL: 0 },
    typeCount: 1,
  }));
  return {
    aggregated: false,
    budget: { ...GRAPH_SLICE_BUDGETS.PACKAGE },
    edges: [],
    level: "PACKAGE",
    nodes,
    parentId: null,
    totals: { edges: 0, nodes: count },
    truncated: false,
  };
};

const createContainer = (): HTMLDivElement => {
  const container = document.createElement("div");
  container.style.padding = "0";
  Object.defineProperty(container, "clientWidth", {
    configurable: true,
    value: CONTAINER_WIDTH,
  });
  Object.defineProperty(container, "clientHeight", {
    configurable: true,
    value: CONTAINER_HEIGHT,
  });
  document.body.append(container);
  return container;
};

type Positions = ReadonlyArray<readonly [string, { x: number; y: number }]>;

const layoutPositions = (slice: GraphSlice): Positions => {
  const cy = cytoscape({
    container: createContainer(),
    layout: { name: "preset" },
    pixelRatio: 1,
    style: buildStylesheet(PALETTE),
  });
  cy.add(toGraphElements(slice));
  setGraphRuntime(cy, graphRuntimeForSlice(slice));
  cy.layout(concentricLayout(slice)).run();
  const positions = cy
    .nodes()
    .map((node) => [node.id(), { ...node.position() }] as const);
  cy.destroy();
  return positions;
};

const distinctRadii = (positions: Positions): number =>
  new Set(
    positions.map(([, { x, y }]) =>
      Math.hypot(x - CONTAINER_WIDTH / 2, y - CONTAINER_HEIGHT / 2).toFixed(6)
    )
  ).size;

const distinctRings = (slice: GraphSlice): number => {
  const max = Math.max(...slice.nodes.map((node) => node.pagerank));
  return new Set(slice.nodes.map((node) => pagerankRing(node.pagerank, max)))
    .size;
};

let packageSlice: GraphSlice;
let restoreCanvas: () => void;

beforeAll(async () => {
  packageSlice = await readSliceFixture("graph.slice.package");
});

beforeEach(() => {
  restoreCanvas = installCanvasStub();
});

afterEach(() => {
  restoreCanvas();
  document.body.replaceChildren();
});

describe("pagerankRing", () => {
  test("o máximo da fatia e tudo acima da metade dele ficam no anel central", () => {
    expect(pagerankRing(0.4, 0.4)).toBe(0);
    expect(pagerankRing(0.200_001, 0.4)).toBe(0);
  });

  test("cada metade igual ou abaixo empurra um anel para fora", () => {
    expect(pagerankRing(0.2, 0.4)).toBe(1);
    expect(pagerankRing(0.1, 0.4)).toBe(2);
    expect(pagerankRing(0.050_001, 0.4)).toBe(2);
    expect(pagerankRing(0.05, 0.4)).toBe(3);
    expect(pagerankRing(0.4 / 2 ** 6, 0.4)).toBe(6);
  });

  test("o último anel recolhe a cauda, sem passar dele", () => {
    expect(pagerankRing(0.4 / 2 ** 7, 0.4)).toBe(CONCENTRIC_RINGS - 1);
    expect(pagerankRing(1e-12, 0.4)).toBe(CONCENTRIC_RINGS - 1);
  });

  test("um PageRank zero, ou uma fatia de máximo zero, vai para o último anel", () => {
    expect(pagerankRing(0, 0.4)).toBe(CONCENTRIC_RINGS - 1);
    expect(pagerankRing(0, 0)).toBe(CONCENTRIC_RINGS - 1);
    expect(pagerankRing(0.1, 0)).toBe(CONCENTRIC_RINGS - 1);
  });
});

describe("concentricLayout", () => {
  test("fixa as opções que o determinismo e o enquadramento pedem", () => {
    const options = concentricLayout(packageSlice);

    expect(options).toMatchObject({
      animate: false,
      avoidOverlap: true,
      fit: true,
      name: "concentric",
      nodeDimensionsIncludeLabels: false,
      padding: FIT_PADDING_PX,
    });
    expect(options.levelWidth?.(cytoscape().collection())).toBe(1);
  });

  test("o valor concêntrico é o anel invertido: o mais exposto vale mais", () => {
    const options = concentricLayout(packageSlice);
    const ringValueOf = (id: string): number | undefined =>
      options.concentric?.({ id: () => id } as unknown as NodeSingular);

    expect(ringValueOf("pkg:br.pucpr.shared")).toBe(CONCENTRIC_RINGS);
    expect(ringValueOf("pkg:br.pucpr.billing")).toBe(CONCENTRIC_RINGS);
    expect(ringValueOf("pkg:br.pucpr.orders")).toBe(CONCENTRIC_RINGS - 1);
  });

  test("um nó fora da fatia vai para o anel externo, e não para o centro", () => {
    const options = concentricLayout(packageSlice);

    expect(
      options.concentric?.({ id: () => "pkg:fora" } as unknown as NodeSingular)
    ).toBe(1);
  });
});

describe("concentricLayout no Cytoscape", () => {
  test("a mesma fatia duas vezes dá posições idênticas, byte a byte", () => {
    const slice = skewedSlice(60);

    const first = JSON.stringify(layoutPositions(slice));
    const second = JSON.stringify(layoutPositions(slice));

    expect(first).toBe(second);
  });

  test("uma fatia enviesada desenha mais de um anel, um por faixa de PageRank", () => {
    const slice = skewedSlice(60);

    const radii = distinctRadii(layoutPositions(slice));

    expect(radii).toBeGreaterThan(1);
    expect(radii).toBe(distinctRings(slice));
  });

  test("a fatia de pacotes do fixture já sai em dois anéis", () => {
    expect(distinctRadii(layoutPositions(packageSlice))).toBe(2);
  });

  test("dentro de um anel a ordem da fatia decide a posição, por isso o adaptador não reordena", () => {
    const slice = skewedSlice(60);
    const last = slice.nodes.length - 1;
    const swapped: GraphSlice = {
      ...slice,
      nodes: [
        ...slice.nodes.slice(0, last - 1),
        slice.nodes[last],
        slice.nodes[last - 1],
      ],
    };
    const [secondToLast, lastNode] = [slice.nodes[last - 1], slice.nodes[last]];

    const original = new Map(layoutPositions(slice));
    const reordered = new Map(layoutPositions(swapped));

    expect(reordered.get(lastNode.id)).toEqual(original.get(secondToLast.id));
    expect(reordered.get(secondToLast.id)).toEqual(original.get(lastNode.id));
  });
});
