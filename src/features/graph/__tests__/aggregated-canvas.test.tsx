import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { render } from "@testing-library/react";
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
import { useAppStore } from "@/state/app-store";
import { installCanvasStub } from "@/test/canvas";
import { toGraphElements } from "../adapter";
import {
  ArchitectureGraph,
  type ArchitectureGraphProps,
} from "../architecture-graph";
import { AGGREGATED_SLICE, ALPHA, GAMMA } from "./aggregated-slice-data";

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

const GLOBAL_CSS = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../global.css"
);

const WHEEL_WARNING = /custom wheel sensitivity/;

const graphTokenStyle = (css: string): string => {
  const light = css.slice(css.indexOf(":root"), css.indexOf("@theme"));
  const dark = css.slice(css.indexOf(".dark {"));
  const tokens = (block: string): string =>
    block
      .split("\n")
      .filter((line) => line.trimStart().startsWith("--graph-"))
      .join("\n");
  return `:root {\n${tokens(light)}\n}\n.dark {\n${tokens(dark)}\n}`;
};

const VIEWPORT = { height: 800, width: 1280 } as const;
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

const GROUP = ALPHA;
const PACKAGE = GAMMA;

let tokenStyle: HTMLStyleElement;
let restoreCanvas: () => void;

beforeAll(async () => {
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
  document.documentElement.classList.remove("dark");
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

const propsFor = (
  overrides: Partial<ArchitectureGraphProps> = {}
): ArchitectureGraphProps => ({
  elements: toGraphElements(AGGREGATED_SLICE),
  label: "Mapa da arquitetura",
  onExpand: noop,
  onRenderError: noop,
  onSelect: noop,
  selectedElementId: null,
  slice: AGGREGATED_SLICE,
  ...overrides,
});

const onlyLiveInstance = (): Core => {
  const live = instances.filter((instance) => !instance.destroyed());
  const [cy] = live;
  if (cy === undefined || live.length !== 1) {
    throw new Error(
      `esperava exatamente uma instância viva, há ${live.length}`
    );
  }
  return cy;
};

describe("ArchitectureGraph: o grupo de pacotes", () => {
  test("o grupo tem forma própria: nem o retângulo arredondado do pacote, nem a elipse do tipo", () => {
    render(<ArchitectureGraph {...propsFor()} />);
    const cy = onlyLiveInstance();

    const groupShape = cy.getElementById(GROUP).style("shape");
    const packageShape = cy.getElementById(PACKAGE).style("shape");

    expect(packageShape).toBe("round-rectangle");
    expect(groupShape).not.toBe(packageShape);
    expect(groupShape).not.toBe("ellipse");
  });

  test("o duplo clique no grupo não desce; no pacote ao lado, desce", () => {
    const onExpand = vi.fn<(elementId: string) => void>();
    render(<ArchitectureGraph {...propsFor({ onExpand })} />);
    const cy = onlyLiveInstance();

    cy.getElementById(GROUP).emit("dbltap");
    expect(onExpand).not.toHaveBeenCalled();

    cy.getElementById(PACKAGE).emit("dbltap");
    expect(onExpand.mock.calls).toStrictEqual([[PACKAGE]]);
  });
});
