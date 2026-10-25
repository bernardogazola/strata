import cytoscape, {
  type ConcentricLayoutOptions,
  type Core,
  type NodeSingular,
} from "cytoscape";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { GRAPH_SLICE_BUDGETS, type GraphSlice } from "@/protocol/graph";
import { installCanvasStub } from "@/test/canvas";
import {
  PACKAGE_SLICE,
  packageIdByLabel,
  typeSliceOf,
} from "@/test/fixture-graph";
import { syntheticSlice } from "../../../../e2e/support/synthetic-slice";
import { toGraphElements } from "../adapter";
import {
  CONCENTRIC_RINGS,
  concentricLayout,
  FIT_PADDING_PX,
  MIN_NODE_SPACING_PX,
  pagerankRing,
  VISIBLE_EDGE_PX,
} from "../layout";
import { GRAPH_TOKENS, type GraphPalette } from "../palette";
import {
  buildStylesheet,
  GRAPH_STYLE,
  graphRuntimeForSlice,
  setGraphRuntime,
} from "../stylesheet";

const CONTAINER_WIDTH = 1280;
const CONTAINER_HEIGHT = 800;

interface ContainerSize {
  readonly height: number;
  readonly width: number;
}

const BENCHMARK_CONTAINER: ContainerSize = {
  height: CONTAINER_HEIGHT,
  width: CONTAINER_WIDTH,
};

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

const sliceWithPageranks = (pageranks: readonly number[]): GraphSlice => {
  const base = skewedSlice(pageranks.length);
  return {
    ...base,
    nodes: base.nodes.map((node, index) => ({
      ...node,
      pagerank: pageranks[index] ?? 0,
    })),
  };
};

const createContainer = (size: ContainerSize): HTMLDivElement => {
  const container = document.createElement("div");
  container.style.padding = "0";
  Object.defineProperty(container, "clientWidth", {
    configurable: true,
    value: size.width,
  });
  Object.defineProperty(container, "clientHeight", {
    configurable: true,
    value: size.height,
  });
  document.body.append(container);
  return container;
};

interface Point {
  readonly x: number;
  readonly y: number;
}

type Positions = ReadonlyArray<readonly [string, Point]>;

const runLayout = (
  slice: GraphSlice,
  options: ConcentricLayoutOptions = concentricLayout(slice),
  size: ContainerSize = BENCHMARK_CONTAINER
): Core => {
  const cy = cytoscape({
    container: createContainer(size),
    layout: { name: "preset" },
    pixelRatio: 1,
    style: buildStylesheet(PALETTE),
  });
  cy.add(toGraphElements(slice));
  setGraphRuntime(cy, graphRuntimeForSlice(slice));
  cy.layout(options).run();
  return cy;
};

const layoutPositions = (
  slice: GraphSlice,
  options: ConcentricLayoutOptions = concentricLayout(slice),
  size: ContainerSize = BENCHMARK_CONTAINER
): Positions => {
  const cy = runLayout(slice, options, size);
  const positions = cy
    .nodes()
    .map((node) => [node.id(), { ...node.position() }] as const);
  cy.destroy();
  return positions;
};

const withoutTransform = (slice: GraphSlice): ConcentricLayoutOptions => ({
  ...concentricLayout(slice),
  transform: undefined,
});

const levelsOf = (slice: GraphSlice): readonly (readonly string[])[] => {
  const max = Math.max(...slice.nodes.map((node) => node.pagerank));
  const ringOf = (pagerank: number): number => pagerankRing(pagerank, max);
  const rings = [...new Set(slice.nodes.map((node) => ringOf(node.pagerank)))];
  return rings
    .sort((left, right) => left - right)
    .map((ring) =>
      slice.nodes
        .filter((node) => ringOf(node.pagerank) === ring)
        .map((node) => node.id)
    );
};

const levelLabelsOf = (slice: GraphSlice): readonly (readonly string[])[] => {
  const labels = new Map(slice.nodes.map((node) => [node.id, node.label]));
  return levelsOf(slice).map((level) =>
    level.map((id) => labels.get(id) ?? id)
  );
};

const offsetsByLabel = (
  slice: GraphSlice
): Record<string, readonly [number, number]> => {
  const cy = runLayout(slice);
  const centre = { x: cy.width() / 2, y: cy.height() / 2 };
  const offsets = Object.fromEntries(
    slice.nodes.map((node) => {
      const { x, y } = cy.getElementById(node.id).position();
      return [node.label, [x - centre.x, y - centre.y] as const];
    })
  );
  cy.destroy();
  return offsets;
};

const SMALL_LEVEL_MAX_NODES = 2;

const QUARTERS_PER_TURN = 4;

const quarterTurns = (offset: Point, turns: number): Point => {
  let turned = offset;
  for (let turn = 0; turn < turns % QUARTERS_PER_TURN; turn += 1) {
    turned = { x: -turned.y, y: turned.x };
  }
  return turned;
};

const segmentMeetsBox = (
  from: Point,
  to: Point,
  box: {
    readonly x1: number;
    readonly x2: number;
    readonly y1: number;
    readonly y2: number;
  }
): boolean => {
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  const clips = [
    [-dx, from.x - box.x1],
    [dx, box.x2 - from.x],
    [-dy, from.y - box.y1],
    [dy, box.y2 - from.y],
  ] as const;
  let enter = 0;
  let leave = 1;
  for (const [direction, distance] of clips) {
    if (direction === 0) {
      if (distance < 0) {
        return false;
      }
      continue;
    }
    const t = distance / direction;
    if (direction < 0) {
      enter = Math.max(enter, t);
    } else {
      leave = Math.min(leave, t);
    }
  }
  return enter <= leave;
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

const FIXTURE_SLICES: [string, GraphSlice][] = [
  ["o nível 1", PACKAGE_SLICE],
  ...PACKAGE_SLICE.nodes.map((node): [string, GraphSlice] => [
    `os tipos de ${node.label}`,
    typeSliceOf(node.id),
  ]),
];

let restoreCanvas: () => void;

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
    const options = concentricLayout(PACKAGE_SLICE);

    expect(options).toMatchObject({
      animate: false,
      avoidOverlap: true,
      fit: true,
      minNodeSpacing: MIN_NODE_SPACING_PX,
      name: "concentric",
      nodeDimensionsIncludeLabels: false,
      padding: FIT_PADDING_PX,
    });
    expect(options.levelWidth?.(cytoscape().collection())).toBe(1);
  });

  test("um nó por anel não sai mais em coluna, e o vão entre dois vizinhos ainda cabe o rótulo e um trecho da aresta", () => {
    const column = sliceWithPageranks([0.7, 0.2, 0.1]);
    const { labelFontSize, labelHaloWidth, labelMarginY } = GRAPH_STYLE.node;
    const labelHeight = labelMarginY + labelFontSize + 2 * labelHaloWidth;

    const cy = runLayout(column);
    const nodes = cy.nodes().toArray();
    const gaps = nodes.slice(1).map((node, index) => {
      const previous = nodes[index];
      const distance = Math.hypot(
        node.position("x") - previous.position("x"),
        node.position("y") - previous.position("y")
      );
      return distance - (node.outerWidth() + previous.outerWidth()) / 2;
    });
    const inBetween = nodes.filter((node) => {
      const [from, to] = nodes.filter((other) => other !== node);
      const half = node.outerWidth() / 2;
      const { x, y } = node.position();
      return segmentMeetsBox(from.position(), to.position(), {
        x1: x - half,
        x2: x + half,
        y1: y - half,
        y2: y + half,
      });
    });
    const labels = inBetween.map((node) => node.data("label") as string);
    cy.destroy();

    expect(labels).toStrictEqual([]);
    expect(gaps).toHaveLength(2);
    for (const gap of gaps) {
      expect(gap).toBeGreaterThanOrEqual(labelHeight + VISIBLE_EDGE_PX);
    }
  });

  test("o valor concêntrico é o anel invertido: o mais exposto vale mais", () => {
    const options = concentricLayout(PACKAGE_SLICE);
    const ringValueOf = (id: string): number | undefined =>
      options.concentric?.({ id: () => id } as unknown as NodeSingular);

    expect(
      Object.fromEntries(
        PACKAGE_SLICE.nodes.map((node) => [node.label, ringValueOf(node.id)])
      )
    ).toStrictEqual({
      api: CONCENTRIC_RINGS - 3,
      core: CONCENTRIC_RINGS,
      "core.gen": CONCENTRIC_RINGS - 3,
      "core.spi": CONCENTRIC_RINGS,
    });
  });

  test("um nó fora da fatia vai para o anel externo, e não para o centro", () => {
    const options = concentricLayout(PACKAGE_SLICE);

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
    expect(distinctRadii(layoutPositions(PACKAGE_SLICE))).toBe(2);
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

describe("as voltas de um quarto dos níveis pequenos", () => {
  test("o nível 1 do fixture: core e core.spi no eixo vertical, api às 3 horas e core.gen às 9", () => {
    expect(levelLabelsOf(PACKAGE_SLICE)).toStrictEqual([
      ["core", "core.spi"],
      ["api", "core.gen"],
    ]);

    expect(offsetsByLabel(PACKAGE_SLICE)).toStrictEqual({
      api: [153, 0],
      core: [0, -51],
      "core.gen": [-153, 0],
      "core.spi": [0, 51],
    });
  });

  test("os tipos de core: CatalogEntry e Kind no eixo vertical, Audited às 3 horas e Catalog às 9", () => {
    const core = typeSliceOf(packageIdByLabel("core"));

    expect(levelLabelsOf(core)).toStrictEqual([
      ["CatalogEntry", "Kind"],
      ["Audited", "Catalog"],
    ]);

    expect(offsetsByLabel(core)).toStrictEqual({
      Audited: [153, 0],
      Catalog: [-153, 0],
      CatalogEntry: [0, -51],
      Kind: [0, 51],
    });
  });

  const MIXED_LEVELS = sliceWithPageranks([0.4, 0.2, 0.2, 0.2, 0.1, 0.1, 0.02]);

  const NEAR_ORIGIN: ContainerSize = { height: 2, width: 2 };

  test.each<[string, GraphSlice]>([
    ...FIXTURE_SLICES,
    ["uma fatia com níveis de 1, 3, 2 e 1 nós", MIXED_LEVELS],
  ])(
    "%s: cada nível de até 2 nós gira k quartos de volta exatos, os outros ficam onde o concêntrico os pôs",
    (_, slice) => {
      const centre = { x: NEAR_ORIGIN.width / 2, y: NEAR_ORIGIN.height / 2 };
      const before = new Map(
        layoutPositions(slice, withoutTransform(slice), NEAR_ORIGIN)
      );
      const expected = levelsOf(slice).flatMap((level, turns) =>
        level.map((id) => {
          const position = before.get(id) ?? { x: Number.NaN, y: Number.NaN };
          if (level.length > SMALL_LEVEL_MAX_NODES) {
            return [id, position] as const;
          }
          const turned = quarterTurns(
            { x: position.x - centre.x, y: position.y - centre.y },
            turns
          );
          return [
            id,
            { x: centre.x + turned.x, y: centre.y + turned.y },
          ] as const;
        })
      );

      const after = layoutPositions(
        slice,
        concentricLayout(slice),
        NEAR_ORIGIN
      );

      expect(Object.fromEntries(after)).toStrictEqual(
        Object.fromEntries(expected)
      );
    }
  );

  const BENCHMARK_SEED = 20;

  test.each(["PACKAGE", "TYPE"] as const)(
    "a fatia sintética %s do benchmark sai byte a byte como no concêntrico de antes",
    (level) => {
      const slice = syntheticSlice({
        ...GRAPH_SLICE_BUDGETS[level],
        level,
        seed: BENCHMARK_SEED,
      });
      const smallPastTheCentre = levelsOf(slice)
        .slice(1)
        .filter((nodes) => nodes.length <= SMALL_LEVEL_MAX_NODES);

      expect(smallPastTheCentre).toStrictEqual([]);
      expect(layoutPositions(slice)).toStrictEqual(
        layoutPositions(slice, withoutTransform(slice))
      );
    }
  );

  test.each(FIXTURE_SLICES)(
    "%s do fixture duas vezes dá posições idênticas, byte a byte",
    (_, slice) => {
      const first = JSON.stringify(layoutPositions(slice));
      const second = JSON.stringify(layoutPositions(slice));

      expect(first).toBe(second);
    }
  );
});
