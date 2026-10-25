import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { render, waitFor } from "@testing-library/react";
import type { Core, CytoscapeOptions } from "cytoscape";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  test,
  vi,
} from "vitest";
import { type GraphSlice, graphSliceSchema } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { installCanvasStub } from "@/test/canvas";
import { syntheticSlice } from "../../../../e2e/support/synthetic-slice";
import { toGraphElements } from "../adapter";
import { ArchitectureGraph } from "../architecture-graph";
import { MAX_FIT_ZOOM } from "../layout";
import { readGraphRuntime } from "../stylesheet";

const instances = vi.hoisted(() => [] as Core[]);
vi.mock("cytoscape", async (importOriginal) => {
  const real = (
    (await importOriginal()) as { default: typeof import("cytoscape") }
  ).default;
  const recording = Object.assign((options?: CytoscapeOptions) => {
    const cy = real(options);
    instances.push(cy);
    return cy;
  }, real);
  return { default: recording };
});

const HERE = path.dirname(fileURLToPath(import.meta.url));
const GLOBAL_CSS = path.resolve(HERE, "../../../global.css");
const FIXTURES = path.resolve(HERE, "../../../../protocol/fixtures");
const VIEWPORT = { height: 800, width: 1280 } as const;
const WHEEL_WARNING = /custom wheel sensitivity/;

const readSliceFixture = async (stem: string): Promise<GraphSlice> => {
  const text = await readFile(path.join(FIXTURES, `${stem}.jsonl`), "utf8");
  const response = text
    .split("\n")
    .filter((line) => line.length > 0)
    .map((line) => JSON.parse(line) as { kind: string; payload: unknown })
    .find((frame) => frame.kind === "response");
  return graphSliceSchema.parse(response?.payload);
};

const graphTokenStyle = (css: string): string => {
  const light = css.slice(css.indexOf(":root"), css.indexOf("@theme"));
  const tokens = light
    .split("\n")
    .filter((line) => line.trimStart().startsWith("--graph-"))
    .join("\n");
  return `:root {\n${tokens}\n}`;
};

const sizeDescriptors = {
  clientHeight: Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    "clientHeight"
  ),
  clientWidth: Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    "clientWidth"
  ),
};

let packageSlice: GraphSlice;
let tokenStyle: HTMLStyleElement;
let restoreCanvas: () => void;

beforeAll(async () => {
  packageSlice = await readSliceFixture("graph.slice.package");
  tokenStyle = document.createElement("style");
  tokenStyle.textContent = graphTokenStyle(await readFile(GLOBAL_CSS, "utf8"));
});

beforeEach(() => {
  instances.length = 0;
  restoreCanvas = installCanvasStub();
  Object.defineProperty(HTMLElement.prototype, "clientWidth", {
    configurable: true,
    get: () => VIEWPORT.width,
  });
  Object.defineProperty(HTMLElement.prototype, "clientHeight", {
    configurable: true,
    get: () => VIEWPORT.height,
  });
  document.head.append(tokenStyle);
  useAppStore.setState({ activeElementId: null });
  const warn = console.warn.bind(console);
  vi.spyOn(console, "warn").mockImplementation((...args: unknown[]) => {
    if (!WHEEL_WARNING.test(String(args[0]))) {
      warn(...args);
    }
  });
});

afterEach(() => {
  for (const cy of instances) {
    if (!cy.destroyed()) {
      cy.destroy();
    }
  }
  tokenStyle.remove();
  restoreCanvas();
  for (const [name, descriptor] of Object.entries(sizeDescriptors)) {
    if (descriptor !== undefined) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor);
    }
  }
  vi.restoreAllMocks();
});

const noop = (): void => undefined;

const mount = async (slice: GraphSlice) => {
  const { container } = render(
    <ArchitectureGraph
      elements={toGraphElements(slice)}
      label="Mapa da arquitetura"
      onExpand={noop}
      onRenderError={noop}
      onSelect={noop}
      selectedElementId={null}
      slice={slice}
    />
  );
  const wrapper = container.querySelector<HTMLElement>("[data-graph-state]");
  await waitFor(() => expect(wrapper?.dataset.graphState).toBe("ready"));
  const [cy] = instances.filter((instance) => !instance.destroyed());
  const handle = window.__STRATA_GRAPH__;
  if (cy === undefined || handle === undefined) {
    throw new Error("o canvas não montou");
  }
  return { cy, handle };
};

interface Point {
  readonly x: number;
  readonly y: number;
}

const centreOf = (box: {
  readonly x1: number;
  readonly x2: number;
  readonly y1: number;
  readonly y2: number;
}): Point => ({ x: (box.x1 + box.x2) / 2, y: (box.y1 + box.y2) / 2 });

const distance = (a: Point, b: Point): number =>
  Math.hypot(a.x - b.x, a.y - b.y);

const offCentrePair = (cy: Core): readonly [string, string] => {
  const centre = centreOf(cy.elements().boundingBox());
  const nodes = cy.nodes().toArray();
  const pairs = nodes.flatMap((a, index) =>
    nodes.slice(index + 1).map((b) => [a, b] as const)
  );
  const offset = ([a, b]: (typeof pairs)[number]): number =>
    distance(centreOf(a.union(b).boundingBox()), centre);
  const [best] = [...pairs].sort(
    (first, second) => offset(second) - offset(first)
  );
  if (best === undefined) {
    throw new Error("a fatia tem menos de dois nós");
  }
  return [best[0].id(), best[1].id()];
};

describe("fitElements fecha pelo settleViewport, centrado no que pediu", () => {
  test("um par vizinho passa do teto: o zoom para nele, o par fica no meio do canvas e o traço segue", async () => {
    const { cy, handle } = await mount(packageSlice);
    const [source, target] = offCentrePair(cy);
    const pair = cy.getElementById(source).union(cy.getElementById(target));
    expect(
      distance(
        centreOf(pair.boundingBox()),
        centreOf(cy.elements().boundingBox())
      )
    ).toBeGreaterThan(10);

    handle.setZoom(0.5);
    handle.fitElements([source, target]);

    expect(handle.zoom()).toBe(MAX_FIT_ZOOM);
    const shown = centreOf(pair.renderedBoundingBox());
    expect(shown.x).toBeCloseTo(VIEWPORT.width / 2);
    expect(shown.y).toBeCloseTo(VIEWPORT.height / 2);
    expect(readGraphRuntime(cy).dashScale).toBe(1 / MAX_FIT_ZOOM);
  });

  test("abaixo do teto, o zoom é o do enquadramento, e o traço é reescalado por ele", async () => {
    const slice = syntheticSlice({
      edges: 1200,
      level: "PACKAGE",
      nodes: 300,
      seed: 1,
    });
    const { cy, handle } = await mount(slice);

    handle.setZoom(2);
    handle.fitElements(slice.nodes.map((node) => node.id));

    expect(handle.zoom()).toBeLessThan(MAX_FIT_ZOOM);
    expect(readGraphRuntime(cy).dashScale).toBe(1 / handle.zoom());
  });

  test("nenhum id presente no canvas deixa a vista como está", async () => {
    const { cy, handle } = await mount(packageSlice);
    handle.setZoom(0.5);
    const pan = { ...cy.pan() };

    handle.fitElements(["pkg:ausente"]);

    expect(handle.zoom()).toBe(0.5);
    expect(cy.pan()).toStrictEqual(pan);
  });

  test("fit, sem centreOn, centra a fatia inteira no teto, como antes", async () => {
    const { cy, handle } = await mount(packageSlice);

    handle.setZoom(0.5);
    handle.fit();

    const box = cy.elements().renderedBoundingBox();
    expect(handle.zoom()).toBe(MAX_FIT_ZOOM);
    expect((box.x1 + box.x2) / 2).toBeCloseTo(VIEWPORT.width / 2);
    expect((box.y1 + box.y2) / 2).toBeCloseTo(VIEWPORT.height / 2);
    expect(readGraphRuntime(cy).dashScale).toBe(1 / MAX_FIT_ZOOM);
  });
});
