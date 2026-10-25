import cytoscape, {
  type ConcentricLayoutOptions,
  type Core,
  type NodeSingular,
} from "cytoscape";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import type { GraphSlice } from "@/protocol/graph";
import { installCanvasStub } from "@/test/canvas";
import {
  PACKAGE_SLICE,
  packageIdByLabel,
  typeSliceOf,
} from "@/test/fixture-graph";
import { toGraphElements } from "../adapter";
import { concentricLayout } from "../layout";
import { GRAPH_TOKENS, type GraphPalette } from "../palette";
import {
  buildStylesheet,
  graphRuntimeForSlice,
  setGraphRuntime,
} from "../stylesheet";

const CONTAINER_WIDTH = 1280;
const CONTAINER_HEIGHT = 800;

const PALETTE = Object.fromEntries(
  GRAPH_TOKENS.map((token) => [token, "#808080"])
) as GraphPalette;

interface Point {
  readonly x: number;
  readonly y: number;
}

interface Box {
  readonly x1: number;
  readonly x2: number;
  readonly y1: number;
  readonly y2: number;
}

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

const runLayout = (
  slice: GraphSlice,
  options: ConcentricLayoutOptions
): Core => {
  const cy = cytoscape({
    container: createContainer(),
    layout: { name: "preset" },
    pixelRatio: 1,
    style: buildStylesheet(PALETTE),
  });
  cy.add(toGraphElements(slice));
  setGraphRuntime(cy, graphRuntimeForSlice(slice));
  cy.layout(options).run();
  return cy;
};

const segmentMeetsBox = (from: Point, to: Point, box: Box): boolean => {
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

const bodyOf = (node: NodeSingular): Box => {
  const { x, y } = node.position();
  const halfWidth = node.outerWidth() / 2;
  const halfHeight = node.outerHeight() / 2;
  return {
    x1: x - halfWidth,
    x2: x + halfWidth,
    y1: y - halfHeight,
    y2: y + halfHeight,
  };
};

const occludedEdges = (
  slice: GraphSlice,
  options: ConcentricLayoutOptions = concentricLayout(slice)
): string[] => {
  const cy = runLayout(slice, options);
  const labelOf = (node: NodeSingular): string => node.data("label") as string;
  const nodes = cy.nodes().toArray();
  const occluded = cy
    .edges()
    .toArray()
    .flatMap((edge) => {
      const [source, target] = [edge.source(), edge.target()];
      const ends = new Set([source.id(), target.id()]);
      return nodes
        .filter((node) => !ends.has(node.id()))
        .filter((node) =>
          segmentMeetsBox(source.position(), target.position(), bodyOf(node))
        )
        .map(
          (node) =>
            `${labelOf(source)} -> ${labelOf(target)} atrás de ${labelOf(node)}`
        );
    });
  cy.destroy();
  return occluded;
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

describe("nenhuma aresta passa por trás de um terceiro nó", () => {
  test.each(FIXTURE_SLICES)(
    "%s do fixture: zero arestas ocluídas",
    (_, slice) => {
      expect(occludedEdges(slice)).toStrictEqual([]);
    }
  );
});

describe("a conta enxerga a coluna (o controle positivo)", () => {
  const withoutTransform = (slice: GraphSlice): ConcentricLayoutOptions => ({
    ...concentricLayout(slice),
    transform: undefined,
  });

  test("no nível 1 em coluna, api -> core.spi passa por trás de core", () => {
    expect(
      occludedEdges(PACKAGE_SLICE, withoutTransform(PACKAGE_SLICE))
    ).toStrictEqual(["api -> core.spi atrás de core"]);
  });

  test("nos tipos de core em coluna, Catalog -> Audited passa por trás de CatalogEntry e de Kind, e Catalog -> CatalogEntry por trás de Kind", () => {
    const core = typeSliceOf(packageIdByLabel("core"));

    expect(occludedEdges(core, withoutTransform(core)).sort()).toStrictEqual([
      "Catalog -> Audited atrás de CatalogEntry",
      "Catalog -> Audited atrás de Kind",
      "Catalog -> CatalogEntry atrás de Kind",
    ]);
  });
});
