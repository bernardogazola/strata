import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { act, render, waitFor } from "@testing-library/react";
import type { Core, CytoscapeOptions } from "cytoscape";
import { StrictMode } from "react";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  test,
  vi,
} from "vitest";
import { notifyResolvedThemeApplied } from "@/lib/theme";
import { type GraphSlice, graphSliceSchema } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { installCanvasStub } from "@/test/canvas";
import { toGraphElements } from "../adapter";
import {
  ArchitectureGraph,
  type ArchitectureGraphProps,
} from "../architecture-graph";
import { ACTIVE_CLASS, SELECTED_CLASS } from "../stylesheet";

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
const FIXTURES = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../../protocol/fixtures"
);

const WHEEL_WARNING = /custom wheel sensitivity/;
const PLACEHOLDER_ID = "__________cytoscape_stylesheet";

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
  const dark = css.slice(css.indexOf(".dark {"));
  const tokens = (block: string): string =>
    block
      .split("\n")
      .filter((line) => line.trimStart().startsWith("--graph-"))
      .join("\n");
  return `:root {\n${tokens(light)}\n}\n.dark {\n${tokens(dark)}\n}`;
};

let packageSlice: GraphSlice;
let typeSlice: GraphSlice;
let tokenStyle: HTMLStyleElement;
let restoreCanvas: () => void;

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

beforeAll(async () => {
  packageSlice = await readSliceFixture("graph.slice.package");
  typeSlice = await readSliceFixture("graph.slice.type");
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
  slice: GraphSlice | null,
  overrides: Partial<ArchitectureGraphProps> = {}
): ArchitectureGraphProps => ({
  elements: slice === null ? null : toGraphElements(slice),
  label: "Mapa da arquitetura",
  onExpand: noop,
  onRenderError: noop,
  onSelect: noop,
  selectedElementId: null,
  slice,
  ...overrides,
});

const liveInstances = (): Core[] => instances.filter((cy) => !cy.destroyed());

const onlyLiveInstance = (): Core => {
  const [cy, ...others] = liveInstances();
  if (cy === undefined || others.length > 0) {
    throw new Error(
      `esperava exatamente uma instância viva, há ${liveInstances().length}`
    );
  }
  return cy;
};

const cytoscapeContainer = (root: HTMLElement): HTMLElement => {
  const container = root.querySelector<HTMLElement>('[role="img"]');
  if (container === null) {
    throw new Error("falta o contêiner do Cytoscape");
  }
  return container;
};

describe("ArchitectureGraph: uma instância, e só uma", () => {
  test("StrictMode monta uma instância viva, com os mesmos canvas de uma montagem simples, e desmontar a destrói", () => {
    const plain = render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    const plainCanvases = cytoscapeContainer(plain.container).querySelectorAll(
      "canvas"
    ).length;
    plain.unmount();
    instances.length = 0;

    const strict = render(
      <StrictMode>
        <ArchitectureGraph {...propsFor(packageSlice)} />
      </StrictMode>
    );
    const container = cytoscapeContainer(strict.container);
    const strictCanvases = container.querySelectorAll("canvas").length;

    expect(strictCanvases).toBeGreaterThan(0);
    expect(strictCanvases).toBe(plainCanvases);
    expect(container.children).toHaveLength(1);
    expect(liveInstances()).toHaveLength(1);

    strict.unmount();

    expect(liveInstances()).toHaveLength(0);
  });

  test("o contêiner do Cytoscape é uma folha com papel e nome acessível; o estado mora no invólucro", () => {
    const { container } = render(
      <ArchitectureGraph {...propsFor(packageSlice, { label: "Mapa X" })} />
    );
    const cyContainer = cytoscapeContainer(container);

    expect(cyContainer.getAttribute("aria-label")).toBe("Mapa X");
    expect(cyContainer.parentElement?.hasAttribute("data-graph-state")).toBe(
      true
    );
  });

  test("o placeholder do <style> existe e o Cytoscape não injeta o dele, que a CSP de produção bloqueia", () => {
    render(<ArchitectureGraph {...propsFor(packageSlice)} />);

    expect(document.getElementById(PLACEHOLDER_ID)?.tagName).toBe("META");
    expect(document.querySelector(`style#${PLACEHOLDER_ID}`)).toBeNull();
  });
});

describe("ArchitectureGraph: elementos e intenção", () => {
  test("os elementos da fatia são os que a instância tem, sem perda", () => {
    render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    const cy = onlyLiveInstance();

    expect(
      cy
        .nodes()
        .map((node) => node.id())
        .sort()
    ).toEqual(packageSlice.nodes.map((node) => node.id).sort());
    expect(cy.edges()).toHaveLength(packageSlice.edges.length);
  });

  test("trocar a fatia troca os elementos sem recriar a instância", () => {
    const { rerender } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const first = onlyLiveInstance();

    rerender(<ArchitectureGraph {...propsFor(typeSlice)} />);

    expect(onlyLiveInstance()).toBe(first);
    expect(
      first
        .nodes()
        .map((node) => node.id())
        .sort()
    ).toEqual(typeSlice.nodes.map((node) => node.id).sort());
  });

  test("elements null limpa o canvas sem destruir a instância", () => {
    const { rerender } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const cy = onlyLiveInstance();

    rerender(<ArchitectureGraph {...propsFor(null)} />);

    expect(cy.destroyed()).toBe(false);
    expect(cy.elements()).toHaveLength(0);
  });

  test("tocar um nó seleciona pelo id; tocar o fundo limpa", () => {
    const onSelect = vi.fn();
    render(<ArchitectureGraph {...propsFor(packageSlice, { onSelect })} />);
    const cy = onlyLiveInstance();
    const id = packageSlice.nodes[0]?.id ?? "";

    cy.getElementById(id).emit("tap");
    cy.emit("tap");

    expect(onSelect).toHaveBeenNthCalledWith(1, id);
    expect(onSelect).toHaveBeenNthCalledWith(2, null);
  });

  test("duplo toque em um pacote expande; em um tipo não", () => {
    const onExpand = vi.fn();
    const { rerender } = render(
      <ArchitectureGraph {...propsFor(packageSlice, { onExpand })} />
    );
    const packageId = packageSlice.nodes[0]?.id ?? "";
    onlyLiveInstance().getElementById(packageId).emit("dbltap");

    rerender(<ArchitectureGraph {...propsFor(typeSlice, { onExpand })} />);
    onlyLiveInstance()
      .getElementById(typeSlice.nodes[0]?.id ?? "")
      .emit("dbltap");

    expect(onExpand).toHaveBeenCalledTimes(1);
    expect(onExpand).toHaveBeenCalledWith(packageId);
  });

  test("o focus da URL vira a classe de seleção, e só em um nó", () => {
    const id = packageSlice.nodes[1]?.id ?? "";
    render(
      <ArchitectureGraph
        {...propsFor(packageSlice, { selectedElementId: id })}
      />
    );
    const cy = onlyLiveInstance();

    expect(cy.getElementById(id).hasClass(SELECTED_CLASS)).toBe(true);
    expect(cy.$(`.${SELECTED_CLASS}`)).toHaveLength(1);
  });

  test("activeElementId escrito na store destaca o nó e as arestas dele, sem re-render", () => {
    const renders = vi.fn();
    const Probe = (props: ArchitectureGraphProps) => {
      renders();
      return <ArchitectureGraph {...props} />;
    };
    render(<Probe {...propsFor(packageSlice)} />);
    const cy = onlyLiveInstance();
    const id = packageSlice.nodes[0]?.id ?? "";
    const before = renders.mock.calls.length;

    act(() => {
      useAppStore.getState().setActiveElementId(id);
    });

    const node = cy.getElementById(id);
    expect(node.hasClass(ACTIVE_CLASS)).toBe(true);
    expect(node.connectedEdges().length).toBeGreaterThan(0);
    expect(node.connectedEdges().not(`.${ACTIVE_CLASS}`)).toHaveLength(0);
    expect(renders.mock.calls.length).toBe(before);

    act(() => {
      useAppStore.getState().setActiveElementId(null);
    });

    expect(cy.$(`.${ACTIVE_CLASS}`)).toHaveLength(0);
  });
});

describe("ArchitectureGraph: tema e ciclo de vida", () => {
  test("o tema aplicado reconstrói a folha a partir da paleta escura", () => {
    render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    const cy = onlyLiveInstance();
    const node = cy.getElementById(packageSlice.nodes[0]?.id ?? "");
    const light = node.style("color");

    act(() => {
      document.documentElement.classList.add("dark");
      notifyResolvedThemeApplied("dark");
    });

    expect(node.style("color")).not.toBe(light);
  });

  test("desmontar cancela a escuta do tema: notificar depois não lança", () => {
    const { unmount } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    unmount();

    expect(() => notifyResolvedThemeApplied("dark")).not.toThrow();
  });

  test("o layout termina com o invólucro em ready e as contagens da fatia", async () => {
    const { container } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const wrapper = cytoscapeContainer(container).parentElement;

    await waitFor(() => expect(wrapper?.dataset.graphState).toBe("ready"));
    expect(wrapper?.dataset.nodeCount).toBe(String(packageSlice.nodes.length));
    expect(wrapper?.dataset.edgeCount).toBe(String(packageSlice.edges.length));
  });

  test("um quadro de layout que chega depois da desmontagem não toca a instância destruída", async () => {
    const { unmount } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    unmount();

    await new Promise((resolve) => setTimeout(resolve, 80));

    expect(liveInstances()).toHaveLength(0);
  });
});
