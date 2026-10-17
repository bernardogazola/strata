import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
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
import { ThemeProvider } from "@/components/theme-provider";
import { notifyResolvedThemeApplied } from "@/lib/theme";
import { type GraphSlice, graphSliceSchema } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { installCanvasStub } from "@/test/canvas";
import { syntheticSlice } from "../../../../e2e/support/synthetic-slice";
import { toGraphElements } from "../adapter";
import {
  ArchitectureGraph,
  type ArchitectureGraphProps,
} from "../architecture-graph";
import { MAX_FIT_ZOOM } from "../layout";
import { UnsupportedGraphColorError } from "../palette";
import { GRAPH_PERF } from "../perf";
import { ACTIVE_CLASS, readGraphRuntime, SELECTED_CLASS } from "../stylesheet";

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
const THEME_TEST_STORAGE_KEY = "strata-test-architecture-graph-theme";

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
    expect(onSelect).toHaveBeenCalledTimes(1);
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

  test("desmontar cancela a escuta do tema: notificar depois não reestiliza a instância destruída", () => {
    const { unmount } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const cy = onlyLiveInstance();
    unmount();
    const style = vi.spyOn(cy, "style");

    expect(() => notifyResolvedThemeApplied("dark")).not.toThrow();
    expect(style).not.toHaveBeenCalled();
  });

  test("desmontar cancela a assinatura da store: um destaque escrito depois não toca a instância destruída", () => {
    const { unmount } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const cy = onlyLiveInstance();
    unmount();
    const startBatch = vi.spyOn(cy, "startBatch");

    act(() => {
      useAppStore
        .getState()
        .setActiveElementId(packageSlice.nodes[0]?.id ?? "");
    });

    expect(startBatch).not.toHaveBeenCalled();
  });

  test("uma cor do tema que o Cytoscape descartaria vira onRenderError, e não um throw no efeito do provedor", () => {
    const onRenderError = vi.fn();
    const regressed = document.createElement("style");
    regressed.textContent = ".dark { --graph-node: oklch(0.7 0.1 250); }";
    document.head.append(regressed);
    try {
      render(
        <ThemeProvider defaultTheme="dark" storageKey={THEME_TEST_STORAGE_KEY}>
          <ArchitectureGraph {...propsFor(packageSlice, { onRenderError })} />
        </ThemeProvider>
      );
    } finally {
      regressed.remove();
    }
    const error: unknown = onRenderError.mock.calls[0]?.[0];

    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(onRenderError).toHaveBeenCalledTimes(1);
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).cause).toBeInstanceOf(UnsupportedGraphColorError);
    expect(liveInstances()).toHaveLength(1);
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

const FRAMES_SETTLED_MS = 80;
const settleFrames = (): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, FRAMES_SETTLED_MS));

const wrapperOf = (root: HTMLElement): HTMLElement => {
  const wrapper = cytoscapeContainer(root).parentElement;
  if (wrapper === null) {
    throw new Error("falta o wrapper do contêiner");
  }
  return wrapper;
};

const waitForReady = async (wrapper: HTMLElement): Promise<void> => {
  await waitFor(() => expect(wrapper.dataset.graphState).toBe("ready"));
};

const readyMarks = (): number =>
  performance.getEntriesByName(GRAPH_PERF.ready, "mark").length;

const isBatching = (cy: Core): boolean =>
  (cy as Core & { readonly batching: () => boolean }).batching();

const sortedIds = (items: readonly { readonly id: string }[]): string[] =>
  items.map((item) => item.id).sort();

describe("ArchitectureGraph: layout, enquadramento e medição", () => {
  test("wheelSensitivity avisa uma vez por instância, e nenhuma cor é descartada", async () => {
    const { container } = render(
      <StrictMode>
        <ArchitectureGraph {...propsFor(packageSlice)} />
      </StrictMode>
    );
    await waitForReady(wrapperOf(container));

    const messages = vi
      .mocked(console.warn)
      .mock.calls.map(([message]) => String(message));
    expect(
      messages.filter((message) => WHEEL_WARNING.test(message))
    ).toHaveLength(instances.length);
    expect(messages.filter((message) => !WHEEL_WARNING.test(message))).toEqual(
      []
    );
  });

  test("uma fatia pequena não é ampliada além do teto, fica centrada e o traço segue o zoom", async () => {
    const { container } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    await waitForReady(wrapperOf(container));
    const cy = onlyLiveInstance();
    const box = cy.elements().renderedBoundingBox();

    expect(cy.zoom()).toBe(MAX_FIT_ZOOM);
    expect((box.x1 + box.x2) / 2).toBeCloseTo(VIEWPORT.width / 2);
    expect((box.y1 + box.y2) / 2).toBeCloseTo(VIEWPORT.height / 2);
    expect(readGraphRuntime(cy).dashScale).toBe(1 / MAX_FIT_ZOOM);
    expect(readGraphRuntime(cy).maxLoc).toBe(
      Math.max(...packageSlice.nodes.map((node) => node.loc))
    );
  });

  test("uma fatia no orçamento fica no zoom do enquadramento, e o traço é reescalado por ele", async () => {
    const slice = syntheticSlice({
      edges: 1200,
      level: "PACKAGE",
      nodes: 300,
      seed: 1,
    });
    const { container } = render(<ArchitectureGraph {...propsFor(slice)} />);
    const wrapper = wrapperOf(container);
    await waitForReady(wrapper);
    const cy = onlyLiveInstance();

    expect(cy.zoom()).toBeLessThan(MAX_FIT_ZOOM);
    expect(readGraphRuntime(cy).dashScale).toBe(1 / cy.zoom());
    expect(wrapper.dataset.nodeCount).toBe("300");
    expect(wrapper.dataset.edgeCount).toBe("1200");
  });

  test("uma fatia substituída antes dos dois quadros não marca ready", async () => {
    const before = readyMarks();
    const { container, rerender } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const wrapper = wrapperOf(container);

    rerender(<ArchitectureGraph {...propsFor(typeSlice)} />);
    await waitForReady(wrapper);
    await settleFrames();

    expect(readyMarks()).toBe(before + 1);
    expect(wrapper.dataset.nodeCount).toBe(String(typeSlice.nodes.length));
  });

  test("os dois quadros de uma instância destruída não marcam ready", async () => {
    const before = readyMarks();
    const { unmount } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    unmount();

    await settleFrames();

    expect(readyMarks()).toBe(before);
  });

  test("trocar a fatia sai de ready até a nova estar desenhada", async () => {
    const { container, rerender } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const wrapper = wrapperOf(container);
    await waitForReady(wrapper);

    rerender(<ArchitectureGraph {...propsFor(typeSlice)} />);

    expect(wrapper.dataset.graphState).toBe("rendering");
    expect(wrapper.dataset.nodeCount).toBeUndefined();
    await waitForReady(wrapper);
    expect(wrapper.dataset.nodeCount).toBe(String(typeSlice.nodes.length));
  });

  test("sem pedido de fatia não há medida: o render não inventa um início", async () => {
    performance.clearMarks();
    performance.clearMeasures();
    const { container } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );

    await waitForReady(wrapperOf(container));

    expect(readyMarks()).toBe(1);
    expect(
      performance.getEntriesByName(GRAPH_PERF.render, "measure")
    ).toHaveLength(0);
  });

  test("a medida vai do último pedido ao ready, e uma fatia do cache não mede de novo", async () => {
    performance.clearMarks();
    performance.clearMeasures();
    const measures = () =>
      performance.getEntriesByName(GRAPH_PERF.render, "measure");
    const lastReady = () =>
      performance.getEntriesByName(GRAPH_PERF.ready, "mark").at(-1);
    const request = performance.mark(GRAPH_PERF.request);
    const { container, rerender } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const wrapper = wrapperOf(container);
    await waitForReady(wrapper);

    expect(measures()).toHaveLength(1);
    expect(measures()[0]?.startTime).toBe(request.startTime);
    expect(measures()[0]?.duration).toBeCloseTo(
      (lastReady()?.startTime ?? 0) - request.startTime
    );

    rerender(<ArchitectureGraph {...propsFor(typeSlice)} />);
    await waitForReady(wrapper);
    expect(readyMarks()).toBe(2);
    expect(measures()).toHaveLength(1);

    const secondRequest = performance.mark(GRAPH_PERF.request);
    rerender(<ArchitectureGraph {...propsFor(packageSlice)} />);
    await waitForReady(wrapper);

    expect(measures()).toHaveLength(2);
    expect(measures()[1]?.startTime).toBe(secondRequest.startTime);
  });

  test("fora de DEV o render não marca nem mede: o User Timing é do benchmark e do e2e", async () => {
    vi.stubEnv("DEV", false);
    performance.clearMarks();
    performance.clearMeasures();
    performance.mark(GRAPH_PERF.request);
    try {
      const { container } = render(
        <ArchitectureGraph {...propsFor(packageSlice)} />
      );
      await waitForReady(wrapperOf(container));

      expect(readyMarks()).toBe(0);
      expect(
        performance.getEntriesByName(GRAPH_PERF.render, "measure")
      ).toHaveLength(0);
    } finally {
      vi.unstubAllEnvs();
    }
  });

  test("com o contêiner já medido, o layout imediato também relê o tamanho antes de rodar", () => {
    const { rerender } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const cy = onlyLiveInstance();
    const resize = vi.spyOn(cy, "resize");
    const layout = vi.spyOn(cy, "layout");

    rerender(<ArchitectureGraph {...propsFor(typeSlice)} />);

    expect(resize).toHaveBeenCalledTimes(1);
    expect(layout).toHaveBeenCalledTimes(1);
    expect(resize.mock.invocationCallOrder[0]).toBeLessThan(
      layout.mock.invocationCallOrder[0] ?? Number.NEGATIVE_INFINITY
    );
  });

  test("a 0×0 o layout espera o primeiro tamanho não nulo que o ResizeObserver relata", async () => {
    const size = { height: 0, width: 0 };
    Object.defineProperty(HTMLElement.prototype, "clientWidth", {
      configurable: true,
      get: () => size.width,
    });
    Object.defineProperty(HTMLElement.prototype, "clientHeight", {
      configurable: true,
      get: () => size.height,
    });
    const observers: { callback: () => void; disconnected: boolean }[] = [];
    vi.stubGlobal(
      "ResizeObserver",
      class {
        readonly #entry: { callback: () => void; disconnected: boolean };
        constructor(callback: () => void) {
          this.#entry = { callback, disconnected: false };
          observers.push(this.#entry);
        }
        readonly observe = noop;
        readonly unobserve = noop;
        disconnect() {
          this.#entry.disconnected = true;
        }
      }
    );
    const resize = () => {
      for (const observer of observers) {
        observer.callback();
      }
    };
    const { container, unmount } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const wrapper = wrapperOf(container);

    resize();
    await settleFrames();
    expect(wrapper.dataset.graphState).toBe("rendering");

    size.width = VIEWPORT.width;
    size.height = VIEWPORT.height;
    act(resize);
    await waitForReady(wrapper);
    expect(onlyLiveInstance().zoom()).toBe(MAX_FIT_ZOOM);

    const marks = readyMarks();
    resize();
    await settleFrames();
    expect(readyMarks()).toBe(marks);

    unmount();
    expect(observers.length).toBeGreaterThan(0);
    expect(observers.every((observer) => observer.disconnected)).toBe(true);
  });
});

describe("ArchitectureGraph: falhas de render", () => {
  test("um id repetido que o cy.add descarta vira onRenderError e um canvas vazio", () => {
    const onRenderError = vi.fn();
    const elements = toGraphElements(packageSlice);
    const [first] = elements.nodes;
    const duplicated = {
      edges: elements.edges,
      nodes: first === undefined ? elements.nodes : [...elements.nodes, first],
    };
    vi.mocked(console.warn).mockImplementation(() => undefined);
    const { container } = render(
      <ArchitectureGraph
        {...propsFor(packageSlice, { elements: duplicated, onRenderError })}
      />
    );
    const cy = onlyLiveInstance();

    expect(onRenderError).toHaveBeenCalledTimes(1);
    expect(onRenderError.mock.calls[0]?.[0]).toBeInstanceOf(Error);
    expect(cy.elements()).toHaveLength(0);
    expect(isBatching(cy)).toBe(false);
    expect(wrapperOf(container).dataset.graphState).toBe("idle");
  });

  test("uma ponta solta que faz o cy.add lançar vira onRenderError, sem prender o lote", () => {
    const onRenderError = vi.fn();
    const elements = toGraphElements(packageSlice);
    const dangling = {
      edges: [
        {
          data: { id: "e_dangling", source: "pkg:missing", target: "pkg:gone" },
          group: "edges" as const,
        },
      ],
      nodes: elements.nodes,
    };
    render(
      <ArchitectureGraph
        {...propsFor(packageSlice, { elements: dangling, onRenderError })}
      />
    );
    const cy = onlyLiveInstance();
    const error: unknown = onRenderError.mock.calls[0]?.[0];

    expect(onRenderError).toHaveBeenCalledTimes(1);
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).cause).toBeInstanceOf(Error);
    expect((error as Error).message).not.toContain("pkg:");
    expect(cy.elements()).toHaveLength(0);
    expect(isBatching(cy)).toBe(false);
  });

  test("elementos sem a fatia deles não são desenhados", () => {
    const { container } = render(
      <ArchitectureGraph {...propsFor(packageSlice, { slice: null })} />
    );

    expect(onlyLiveInstance().elements()).toHaveLength(0);
    expect(wrapperOf(container).dataset.graphState).toBe("idle");
  });
});

describe("ArchitectureGraph: seleção, destaque e intenção", () => {
  test("um focus que chega antes da fatia é aplicado quando ela chega", () => {
    const id = packageSlice.nodes[2]?.id ?? "";
    const { rerender } = render(
      <ArchitectureGraph {...propsFor(null, { selectedElementId: id })} />
    );

    rerender(
      <ArchitectureGraph
        {...propsFor(packageSlice, { selectedElementId: id })}
      />
    );

    expect(onlyLiveInstance().getElementById(id).hasClass(SELECTED_CLASS)).toBe(
      true
    );
  });

  test("trocar o focus move a classe; limpar o focus a retira", () => {
    const [first, second] = packageSlice.nodes;
    const { rerender } = render(
      <ArchitectureGraph
        {...propsFor(packageSlice, { selectedElementId: first?.id ?? "" })}
      />
    );
    const cy = onlyLiveInstance();

    rerender(
      <ArchitectureGraph
        {...propsFor(packageSlice, { selectedElementId: second?.id ?? "" })}
      />
    );
    expect(cy.$(`.${SELECTED_CLASS}`).map((node) => node.id())).toEqual([
      second?.id,
    ]);

    rerender(<ArchitectureGraph {...propsFor(packageSlice)} />);
    expect(cy.$(`.${SELECTED_CLASS}`)).toHaveLength(0);
  });

  test("o destaque que já estava na store vale para a instância nova e para a fatia nova", () => {
    const id = typeSlice.nodes[0]?.id ?? "";
    useAppStore.setState({ activeElementId: id });
    const { rerender } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    const cy = onlyLiveInstance();

    rerender(<ArchitectureGraph {...propsFor(typeSlice)} />);

    const node = cy.getElementById(id);
    expect(node.hasClass(ACTIVE_CLASS)).toBe(true);
    expect(node.connectedEdges().not(`.${ACTIVE_CLASS}`)).toHaveLength(0);
  });

  test("uma escrita de outro campo da store não reestiliza o canvas", () => {
    render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    const cy = onlyLiveInstance();
    const startBatch = vi.spyOn(cy, "startBatch");

    act(() => {
      useAppStore.getState().clearWarnings();
    });

    expect(startBatch).not.toHaveBeenCalled();
  });

  test("passar o ponteiro sobre um nó escreve activeElementId; sair limpa", () => {
    render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    const cy = onlyLiveInstance();
    const id = packageSlice.nodes[1]?.id ?? "";

    act(() => {
      cy.getElementById(id).emit("mouseover");
    });
    expect(useAppStore.getState().activeElementId).toBe(id);
    expect(cy.getElementById(id).hasClass(ACTIVE_CLASS)).toBe(true);

    act(() => {
      cy.getElementById(id).emit("mouseout");
    });
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("o ponteiro que sai do canvas direto de cima de um nó solta o destaque", () => {
    render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    const cy = onlyLiveInstance();
    const id = packageSlice.nodes[1]?.id ?? "";

    act(() => {
      cy.getElementById(id).emit("mouseover");
    });
    expect(useAppStore.getState().activeElementId).toBe(id);

    act(() => {
      fireEvent.mouseLeave(screen.getByRole("img"));
    });

    expect(useAppStore.getState().activeElementId).toBeNull();
    expect(cy.getElementById(id).hasClass(ACTIVE_CLASS)).toBe(false);
  });

  test("sair do canvas sem nó sob o ponteiro não solta o destaque de outro escritor", () => {
    render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    const focused = packageSlice.nodes[2]?.id ?? "";

    act(() => {
      useAppStore.getState().setActiveElementId(focused);
      fireEvent.mouseLeave(screen.getByRole("img"));
    });

    expect(useAppStore.getState().activeElementId).toBe(focused);
  });

  test("sair de um nó não apaga o destaque que outro escritor pôs depois", () => {
    render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    const cy = onlyLiveInstance();
    const hovered = packageSlice.nodes[1]?.id ?? "";
    const focused = packageSlice.nodes[2]?.id ?? "";

    act(() => {
      cy.getElementById(hovered).emit("mouseover");
      useAppStore.getState().setActiveElementId(focused);
      cy.getElementById(hovered).emit("mouseout");
    });

    expect(useAppStore.getState().activeElementId).toBe(focused);
    expect(cy.getElementById(focused).hasClass(ACTIVE_CLASS)).toBe(true);
  });

  test("tocar uma aresta não seleciona nem limpa", () => {
    const onSelect = vi.fn();
    render(<ArchitectureGraph {...propsFor(packageSlice, { onSelect })} />);

    onlyLiveInstance().edges().first().emit("tap");

    expect(onSelect).not.toHaveBeenCalled();
  });

  test("o callback chamado é sempre o do render mais recente, sem recriar a instância", () => {
    const stale = vi.fn();
    const fresh = vi.fn();
    const { rerender } = render(
      <ArchitectureGraph {...propsFor(packageSlice, { onSelect: stale })} />
    );
    const cy = onlyLiveInstance();

    rerender(
      <ArchitectureGraph {...propsFor(packageSlice, { onSelect: fresh })} />
    );
    cy.emit("tap");

    expect(onlyLiveInstance()).toBe(cy);
    expect(stale).not.toHaveBeenCalled();
    expect(fresh).toHaveBeenCalledWith(null);
  });

  test("o placeholder do <style> é um só, por mais instâncias que se montem", () => {
    render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    render(<ArchitectureGraph {...propsFor(typeSlice)} />);

    expect(document.querySelectorAll(`#${PLACEHOLDER_ID}`)).toHaveLength(1);
  });
});

describe("ArchitectureGraph: a leitura de DEV", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  test("lê ids ordenados, posições em px da página e estilo; ausente é nulo", async () => {
    const id = packageSlice.nodes[0]?.id ?? "";
    const { container } = render(
      <ArchitectureGraph
        {...propsFor(packageSlice, { selectedElementId: id })}
      />
    );
    await waitForReady(wrapperOf(container));
    vi.spyOn(
      cytoscapeContainer(container),
      "getBoundingClientRect"
    ).mockReturnValue(
      DOMRect.fromRect({ height: 800, width: 1280, x: 100, y: 50 })
    );
    const cy = onlyLiveInstance();
    const handle = window.__STRATA_GRAPH__;
    const node = cy.getElementById(id).renderedPosition();
    const edge = cy.edges().first();
    const midpoint = edge.renderedMidpoint();

    expect(handle?.nodeIds()).toEqual(sortedIds(packageSlice.nodes));
    expect(handle?.edgeIds()).toEqual(sortedIds(packageSlice.edges));
    expect(handle?.renderedPosition(id)).toEqual({
      x: 100 + node.x,
      y: 50 + node.y,
    });
    expect(handle?.renderedPosition(edge.id())).toEqual({
      x: 100 + midpoint.x,
      y: 50 + midpoint.y,
    });
    expect(handle?.renderedPosition("pkg:missing")).toBeNull();
    expect(handle?.style(id, "border-width")).toBe("4px");
    expect(handle?.style("pkg:missing", "border-width")).toBeNull();
  });

  test("setZoom muda o zoom; fit volta ao enquadramento com teto e traço reescalado", async () => {
    const { container } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );
    await waitForReady(wrapperOf(container));
    const cy = onlyLiveInstance();
    const handle = window.__STRATA_GRAPH__;

    handle?.setZoom(0.5);
    expect(handle?.zoom()).toBe(0.5);

    handle?.fit();
    expect(handle?.zoom()).toBe(MAX_FIT_ZOOM);
    expect(readGraphRuntime(cy).dashScale).toBe(1 / MAX_FIT_ZOOM);
  });

  test("desmontar retira a leitura só se ela ainda for da instância que desmonta", () => {
    const first = render(<ArchitectureGraph {...propsFor(packageSlice)} />);
    const second = render(<ArchitectureGraph {...propsFor(typeSlice)} />);

    first.unmount();
    expect(window.__STRATA_GRAPH__?.nodeIds()).toEqual(
      sortedIds(typeSlice.nodes)
    );

    second.unmount();
    expect(window.__STRATA_GRAPH__).toBeUndefined();
  });

  test("fora de DEV a leitura nunca é registrada", () => {
    vi.stubEnv("DEV", false);

    const { unmount } = render(
      <ArchitectureGraph {...propsFor(packageSlice)} />
    );

    expect(window.__STRATA_GRAPH__).toBeUndefined();
    unmount();
    expect(window.__STRATA_GRAPH__).toBeUndefined();
  });
});
