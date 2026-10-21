import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import cytoscape, {
  type Core,
  type EdgeSingular,
  type StylesheetStyle,
} from "cytoscape";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  type MockInstance,
  test,
  vi,
} from "vitest";
import { type GraphSlice, graphSliceSchema } from "@/protocol/graph";
import { installCanvasStub } from "@/test/canvas";
import { toGraphElements } from "../adapter";
import { GRAPH_TOKENS, type GraphPalette, readGraphPalette } from "../palette";
import {
  ACTIVE_CLASS,
  buildStylesheet,
  DEFAULT_GRAPH_RUNTIME,
  EDGE_LINES,
  edgeWidth,
  GRAPH_SCRATCH,
  GRAPH_STYLE,
  graphRuntimeForSlice,
  LABEL_FONT_FAMILY,
  nodeDiameter,
  readGraphRuntime,
  SELECTED_CLASS,
  setGraphRuntime,
} from "../stylesheet";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const GLOBAL_CSS = path.resolve(ROOT, "../../global.css");
const FIXTURES = path.resolve(ROOT, "../../../protocol/fixtures");

const PARSER_WARNING = /is invalid|may not return/;
const WHEEL_SENSITIVITY_WARNING = /wheel sensitivity/;

const readSliceFixture = async (stem: string): Promise<GraphSlice> => {
  const text = await readFile(path.join(FIXTURES, `${stem}.jsonl`), "utf8");
  const response = text
    .split("\n")
    .filter((line) => line.length > 0)
    .map((line) => JSON.parse(line) as { kind: string; payload: unknown })
    .find((frame) => frame.kind === "response");
  return graphSliceSchema.parse(response?.payload);
};

const tokenBlock = (css: string, selector: string): string => {
  const start = css.indexOf(`\n${selector} {`);
  return css.slice(start, css.indexOf("\n}", start) + 2);
};

const parsedColor = (hex: string): string => {
  const channels = [1, 3, 5].map((start) =>
    Number.parseInt(hex.slice(start, start + 2), 16)
  );
  return `rgb(${channels.join(",")})`;
};

const nextFrames = (): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, 50));

let packageSlice: GraphSlice;
let typeSlice: GraphSlice;
let palettes: Record<"dark" | "light", GraphPalette>;
let restoreCanvas: () => void;
let warn: MockInstance<typeof console.warn>;
let instances: Core[] = [];

beforeAll(async () => {
  packageSlice = await readSliceFixture("graph.slice.package");
  typeSlice = await readSliceFixture("graph.slice.type");

  const css = await readFile(GLOBAL_CSS, "utf8");
  const style = document.createElement("style");
  style.textContent = `${tokenBlock(css, ":root")}\n${tokenBlock(css, ".dark")}`;
  document.head.append(style);
  const light = readGraphPalette();
  document.documentElement.classList.add("dark");
  const dark = readGraphPalette();
  document.documentElement.classList.remove("dark");
  style.remove();
  palettes = { dark, light };
});

beforeEach(() => {
  restoreCanvas = installCanvasStub();
  warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
});

afterEach(() => {
  for (const cy of instances) {
    cy.destroy();
  }
  instances = [];
  restoreCanvas();
  vi.restoreAllMocks();
  document.body.replaceChildren();
});

const sliceWithEveryQuality = (): GraphSlice => ({
  ...packageSlice,
  edges: packageSlice.edges.map((edge, index) =>
    index === 2 ? { ...edge, resolution: "UNRESOLVED_EXTERNAL" } : edge
  ),
});

const createGraph = (palette: GraphPalette, slice: GraphSlice): Core => {
  const container = document.createElement("div");
  container.style.padding = "0";
  document.body.append(container);
  const cy = cytoscape({
    autounselectify: true,
    container,
    layout: { name: "preset" },
    pixelRatio: 1,
    style: buildStylesheet(palette),
    wheelSensitivity: 0.2,
  });
  instances.push(cy);
  cy.add(toGraphElements(slice));
  setGraphRuntime(cy, graphRuntimeForSlice(slice));
  return cy;
};

const parserWarnings = (): string[] =>
  warn.mock.calls
    .map((call) => String(call[0]))
    .filter((message) => !WHEEL_SENSITIVITY_WARNING.test(message));

const edgeOf = (cy: Core, index: number): EdgeSingular =>
  cy.getElementById(packageSlice.edges[index].id) as EdgeSingular;

describe("buildStylesheet pelo parser do Cytoscape", () => {
  test.each(["light", "dark"] as const)(
    "a paleta %s passa sem nenhum aviso de valor inválido, nos dois níveis",
    async (theme) => {
      const palette = palettes[theme];
      const packages = createGraph(palette, sliceWithEveryQuality());
      const types = createGraph(palette, typeSlice);
      const [first] = packageSlice.nodes;

      packages
        .getElementById(first.id)
        .addClass([SELECTED_CLASS, ACTIVE_CLASS]);
      packages.getElementById(first.id).connectedEdges().addClass(ACTIVE_CLASS);
      setGraphRuntime(packages, {
        dashScale: 0.25,
        hotspotIds: new Set([first.id]),
      });
      setGraphRuntime(types, { dashScale: 4 });
      await nextFrames();

      expect(
        parserWarnings().filter((message) => PARSER_WARNING.test(message))
      ).toEqual([]);
      expect(parserWarnings()).toEqual([]);
      expect(packages.nodes()).toHaveLength(packageSlice.nodes.length);
      expect(types.nodes()).toHaveLength(typeSlice.nodes.length);
    }
  );

  test("o contrato de wheelSensitivity avisa uma vez por instância, e só ele", () => {
    createGraph(palettes.light, packageSlice);

    const wheel = warn.mock.calls.filter((call) =>
      WHEEL_SENSITIVITY_WARNING.test(String(call[0]))
    );

    expect(wheel).toHaveLength(1);
  });

  test("as cores lidas de volta são as da paleta do tema", () => {
    for (const theme of ["light", "dark"] as const) {
      const palette = palettes[theme];
      const cy = createGraph(palette, packageSlice);
      const node = cy.getElementById(packageSlice.nodes[0].id);

      expect(node.style("background-color")).toBe(
        parsedColor(palette["--graph-node-package"])
      );
      expect(node.style("color")).toBe(
        parsedColor(palette["--graph-node-label"])
      );
      expect(edgeOf(cy, 0).style("line-color")).toBe(
        parsedColor(palette["--graph-edge-structural"])
      );
    }
  });

  test("o rótulo do nó é a Inter da interface, em 500, no tamanho da constante", () => {
    const cy = createGraph(palettes.light, packageSlice);
    const node = cy.getElementById(packageSlice.nodes[0].id);

    expect(node.style("font-family")).toBe(LABEL_FONT_FAMILY);
    expect(node.style("font-weight")).toBe(
      String(GRAPH_STYLE.node.labelFontWeight)
    );
    expect(node.style("font-size")).toBe(`${GRAPH_STYLE.node.labelFontSize}px`);
  });

  test("toda cor da folha vem da paleta: nenhuma literal", () => {
    const palette = Object.fromEntries(
      GRAPH_TOKENS.map((token, index) => [
        token,
        `#0000${index.toString(16).padStart(2, "0")}`,
      ])
    ) as GraphPalette;
    const allowed = new Set(Object.values(palette));

    const colours = buildStylesheet(palette).flatMap((block) =>
      Object.entries((block as StylesheetStyle).style)
        .filter(([property]) => property.endsWith("color"))
        .map(([, value]) => value)
        .filter((value) => typeof value === "string")
    );

    expect(colours.length).toBeGreaterThan(0);
    for (const colour of colours) {
      expect(allowed).toContain(colour);
    }
  });

  test("não usa dotted, opacidade nem as opções de viewport que o Cytoscape já aposentou", () => {
    const serialised = JSON.stringify(buildStylesheet(palettes.light));

    expect(serialised).not.toContain("dotted");
    expect(serialised).not.toContain("opacity");
    expect(serialised).not.toContain("evolutionary");
  });
});

describe("cor nunca é o único canal", () => {
  test("as três qualidades de resolução dão três traços distintos", () => {
    const cy = createGraph(palettes.light, sliceWithEveryQuality());

    const lines = [0, 1, 2].map((index) => {
      const edge = edgeOf(cy, index);
      return {
        cap: edge.style("line-cap"),
        pattern: edge.numericStyle("line-dash-pattern") as number[],
        quality: edge.data("resolution"),
        style: edge.style("line-style"),
      };
    });

    expect(lines).toEqual([
      {
        cap: "butt",
        pattern: expect.any(Array),
        quality: "RESOLVED",
        style: "solid",
      },
      { cap: "butt", pattern: [6, 3], quality: "INFERRED", style: "dashed" },
      {
        cap: "round",
        pattern: [1.5, 3],
        quality: "UNRESOLVED_EXTERNAL",
        style: "dashed",
      },
    ]);
  });

  test("o tipo do elemento vira forma: pacote retângulo arredondado, tipo elipse", () => {
    const packages = createGraph(palettes.light, packageSlice);
    const types = createGraph(palettes.light, typeSlice);

    expect(
      packages.getElementById(packageSlice.nodes[0].id).style("shape")
    ).toBe("round-rectangle");
    expect(types.getElementById(typeSlice.nodes[0].id).style("shape")).toBe(
      "ellipse"
    );
    expect(
      types.getElementById(typeSlice.nodes[0].id).style("background-color")
    ).toBe(parsedColor(palettes.light["--graph-node"]));
  });

  test("a seleção alarga a borda de 1 para 4 e usa a cor de seleção", () => {
    const cy = createGraph(palettes.light, packageSlice);
    const node = cy.getElementById(packageSlice.nodes[0].id);
    const before = node.style("border-width");

    node.addClass(SELECTED_CLASS);

    expect(before).toBe("1px");
    expect(node.style("border-width")).toBe("4px");
    expect(node.style("border-color")).toBe(
      parsedColor(palettes.light["--graph-node-selected"])
    );
  });

  test("o destaque cruzado engrossa o nó e as arestas dele, e a seleção prevalece", () => {
    const cy = createGraph(palettes.light, packageSlice);
    const node = cy.getElementById(packageSlice.nodes[0].id);
    const edge = edgeOf(cy, 0);
    const width = edge.numericStyle("width") as number;

    node.addClass(ACTIVE_CLASS);
    edge.addClass(ACTIVE_CLASS);

    expect(node.style("border-width")).toBe("3px");
    expect(edge.style("line-color")).toBe(
      parsedColor(palettes.light["--graph-edge-highlighted"])
    );
    expect(edge.numericStyle("width")).toBeCloseTo(
      width + GRAPH_STYLE.edge.activeExtraWidth
    );
    expect(edge.style("z-index")).toBe(String(GRAPH_STYLE.edge.activeZIndex));

    node.addClass(SELECTED_CLASS);

    expect(node.style("border-width")).toBe("4px");
  });

  test("a largura da aresta é 1 + ln(1 + peso)", () => {
    const cy = createGraph(palettes.light, packageSlice);

    expect(edgeOf(cy, 0).numericStyle("width")).toBeCloseTo(1 + Math.log(57));
  });
});

describe("o estado de execução no scratch reestiliza a instância", () => {
  test("dashScale escala o traço: INFERRED [6, 3] vira [60, 30]", () => {
    const cy = createGraph(palettes.light, sliceWithEveryQuality());

    setGraphRuntime(cy, { dashScale: 10 });

    expect(edgeOf(cy, 1).numericStyle("line-dash-pattern")).toEqual([60, 30]);
    expect(edgeOf(cy, 1).style("line-dash-pattern")).toBe("60 30");
    expect(edgeOf(cy, 2).numericStyle("line-dash-pattern")).toEqual([15, 30]);
  });

  test("um hotspot cresce 1,5 vez e ganha a cor de hotspot", () => {
    const cy = createGraph(palettes.light, packageSlice);
    const node = cy.getElementById(packageSlice.nodes[0].id);
    const width = node.width();

    setGraphRuntime(cy, { hotspotIds: new Set([node.id()]) });

    expect(node.width()).toBeCloseTo(width * GRAPH_STYLE.node.hotspotScale);
    expect(node.height()).toBeCloseTo(width * GRAPH_STYLE.node.hotspotScale);
    expect(node.style("background-color")).toBe(
      parsedColor(palettes.light["--graph-node-hotspot"])
    );
  });

  test("maxLoc redimensiona os nós: o maior da fatia vai ao diâmetro máximo", () => {
    const cy = createGraph(palettes.light, packageSlice);
    const largest = cy.getElementById("pkg:br.pucpr.orders");
    const smaller = cy.getElementById("pkg:br.pucpr.shared");

    expect(largest.width()).toBeCloseTo(GRAPH_STYLE.node.maxDiameter);
    expect(smaller.width()).toBeCloseTo(nodeDiameter(620, 4210));

    setGraphRuntime(cy, { maxLoc: 0 });

    expect(largest.width()).toBe(GRAPH_STYLE.node.minDiameter);
  });
});

describe("estado de execução", () => {
  test("antes da primeira escrita vale o padrão", () => {
    const cy = cytoscape({ headless: true });

    expect(readGraphRuntime(cy)).toBe(DEFAULT_GRAPH_RUNTIME);
  });

  test("setGraphRuntime grava um objeto novo e preserva os campos que não mudaram", () => {
    const cy = cytoscape({ headless: true });
    const hotspotIds = new Set(["pkg:a"]);
    setGraphRuntime(cy, { hotspotIds });
    const first = cy.scratch(GRAPH_SCRATCH);

    setGraphRuntime(cy, { dashScale: 3 });

    expect(cy.scratch(GRAPH_SCRATCH)).not.toBe(first);
    expect(readGraphRuntime(cy)).toEqual({
      dashScale: 3,
      hotspotIds,
      maxLoc: 0,
    });
  });

  test("graphRuntimeForSlice leva o maior loc da fatia, e zero em uma fatia vazia", () => {
    expect(graphRuntimeForSlice(packageSlice)).toEqual({ maxLoc: 4210 });
    expect(graphRuntimeForSlice({ ...packageSlice, nodes: [] })).toEqual({
      maxLoc: 0,
    });
  });

  test("o traço não muda de identidade enquanto a escala não muda", () => {
    const block = buildStylesheet(palettes.light).find(
      (candidate) => candidate.selector === 'edge[resolution = "INFERRED"]'
    ) as StylesheetStyle;
    const pattern = (block.style as Record<string, unknown>)[
      "line-dash-pattern"
    ] as (edge: EdgeSingular) => number[];
    const cy = cytoscape({ headless: true });
    const edge = { cy: () => cy } as unknown as EdgeSingular;

    const atOne = pattern(edge);
    setGraphRuntime(cy, { dashScale: 2 });
    const first = pattern(edge);
    const second = pattern(edge);

    expect(atOne).toEqual([...EDGE_LINES.INFERRED.dashPattern]);
    expect(first).toEqual([12, 6]);
    expect(second).toBe(first);
  });
});

describe("nodeDiameter e edgeWidth", () => {
  test("o diâmetro segue ln(1 + loc) / ln(1 + maxLoc) entre o mínimo e o máximo", () => {
    const { maxDiameter, minDiameter } = GRAPH_STYLE.node;

    expect(nodeDiameter(0, 4210)).toBe(minDiameter);
    expect(nodeDiameter(4210, 4210)).toBe(maxDiameter);
    expect(nodeDiameter(1840, 4210)).toBeCloseTo(
      minDiameter +
        ((maxDiameter - minDiameter) * Math.log(1841)) / Math.log(4211)
    );
  });

  test("sem maxLoc todo nó tem o diâmetro mínimo, e um maxLoc velho não estoura o teto", () => {
    expect(nodeDiameter(500, 0)).toBe(GRAPH_STYLE.node.minDiameter);
    expect(nodeDiameter(9000, 100)).toBe(GRAPH_STYLE.node.maxDiameter);
  });

  test("a largura da aresta tem teto", () => {
    expect(edgeWidth(0)).toBe(GRAPH_STYLE.edge.baseWidth);
    expect(edgeWidth(1_000_000)).toBe(GRAPH_STYLE.edge.maxWidth);
  });
});
