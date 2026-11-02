import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import type { Core, CytoscapeOptions } from "cytoscape";
import { createRef, type Ref } from "react";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  test,
  vi,
} from "vitest";
import { fakeRequest } from "@/bridge/fake";
import { toGraphView } from "@/features/graph/adapter";
import { ArchitectureGraph } from "@/features/graph/architecture-graph";
import { GRAPH_STYLE } from "@/features/graph/stylesheet";
import {
  ArchitectureOutline,
  type OutlineHandle,
} from "@/features/outline/architecture-outline";
import { t } from "@/i18n/messages";
import type { ElementKind } from "@/protocol/enums";
import type { GraphSlice } from "@/protocol/graph";
import { createQueryClient } from "@/queries/client";
import type { GraphViewParams } from "@/queries/options";
import { useAppStore } from "@/state/app-store";
import { installCanvasStub } from "@/test/canvas";
import {
  DRILLED_CHILDREN,
  DRILLED_PACKAGE_ID,
  PACKAGE_IDS,
  ROOT_ITEMS,
} from "@/test/fixture-graph";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const ROOT_IDS = ROOT_ITEMS.map((item) => item.id);
const TYPE_IDS = DRILLED_CHILDREN.map((item) => item.id);
const FIRST_PACKAGE = ROOT_IDS[0] ?? "";
const DRILLED = DRILLED_PACKAGE_ID;
const LAST_PACKAGE = ROOT_IDS.at(-1) ?? "";
const FIRST_TYPE = TYPE_IDS[0] ?? "";
const SECOND_TYPE = TYPE_IDS[1] ?? "";
const LAST_TYPE = TYPE_IDS.at(-1) ?? "";
const ROOT_ROWS = ROOT_IDS.length;
const DRILLED_ROWS = ROOT_IDS.length + TYPE_IDS.length;

const LEVEL_ONE: GraphViewParams = { level: "PACKAGE" };
const drilledTypes: GraphViewParams = { level: "TYPE", parentId: DRILLED };

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

const liveInstance = (): Core => {
  const live = instances.filter((instance) => !instance.destroyed());
  const [cy] = live;
  if (cy === undefined || live.length > 1) {
    throw new Error(
      `esperava exatamente uma instância viva, há ${live.length}`
    );
  }
  return cy;
};

const WHEEL_WARNING = /custom wheel sensitivity/;
const GLOBAL_CSS = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../global.css"
);

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

const OUTLINE_VIEWPORT = { height: 400, width: 320 } as const;
const CANVAS_VIEWPORT = { height: 800, width: 1280 } as const;
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

let restoreCanvas: () => void;
let tokenStyle: HTMLStyleElement;

beforeAll(async () => {
  tokenStyle = document.createElement("style");
  tokenStyle.textContent = graphTokenStyle(await readFile(GLOBAL_CSS, "utf8"));
});

beforeEach(() => {
  restoreCanvas = installCanvasStub();
  document.head.append(tokenStyle);
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(
    OUTLINE_VIEWPORT.height
  );
  vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(
    OUTLINE_VIEWPORT.width
  );
  Object.defineProperty(HTMLElement.prototype, "clientWidth", {
    configurable: true,
    get: () => CANVAS_VIEWPORT.width,
  });
  Object.defineProperty(HTMLElement.prototype, "clientHeight", {
    configurable: true,
    get: () => CANVAS_VIEWPORT.height,
  });
  useAppStore.setState({ activeElementId: null });
  const warn = console.warn.bind(console);
  vi.spyOn(console, "warn").mockImplementation((...args: unknown[]) => {
    if (!WHEEL_WARNING.test(String(args[0]))) {
      warn(...args);
    }
  });
});

afterEach(() => {
  tokenStyle.remove();
  restoreCanvas();
  for (const [name, descriptor] of Object.entries(sizeDescriptors)) {
    if (descriptor !== undefined) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor);
    }
  }
  vi.restoreAllMocks();
});

interface OutlineState {
  readonly focus?: string | null;
  readonly outlineRef?: Ref<OutlineHandle>;
  readonly view?: GraphViewParams;
}

const setup = (initial: OutlineState = {}) => {
  const queryClient = createQueryClient();
  const onCollapse = vi.fn<() => void>();
  const onExpand = vi.fn<(packageId: string) => void>();
  const onSelect = vi.fn<(elementId: string, kind: ElementKind) => void>();
  const ui = ({ focus = null, outlineRef, view = LEVEL_ONE }: OutlineState) => (
    <QueryClientProvider client={queryClient}>
      <button type="button">fora</button>
      <ArchitectureOutline
        analysisId={AN}
        focus={focus}
        onCollapse={onCollapse}
        onExpand={onExpand}
        onSelect={onSelect}
        ref={outlineRef}
        view={view}
      />
    </QueryClientProvider>
  );
  const result = render(ui(initial));
  return {
    onCollapse,
    onExpand,
    onSelect,
    rerender: (state: OutlineState) => result.rerender(ui(state)),
    unmount: result.unmount,
  };
};

const treeItems = (): HTMLElement[] => screen.queryAllByRole("treeitem");

const waitForRows = async (count: number): Promise<HTMLElement[]> => {
  await waitFor(() => expect(treeItems()).toHaveLength(count));
  return treeItems();
};

const rowById = (id: string): HTMLElement => {
  const row = treeItems().find((item) => item.dataset.elementId === id);
  if (row === undefined) {
    throw new Error(`sem linha para ${id}`);
  }
  return row;
};

const focusRow = (id: string): HTMLElement => {
  const row = rowById(id);
  act(() => row.focus());
  return row;
};

const failChildrenOf = (packageId: string): void => {
  vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
    {
      method: "outline.children",
      request: { parentId: packageId },
      result: { notAPage: true },
    },
  ] satisfies readonly FakeTranscriptOverride[]);
};

const press = (key: string): void => {
  fireEvent.keyDown(document.activeElement ?? document.body, { key });
};

const tabbableRows = (): HTMLElement[] =>
  treeItems().filter((row) => row.tabIndex === 0);

const activeElementId = (): string | null =>
  useAppStore.getState().activeElementId;

const activeBorder = `${GRAPH_STYLE.node.activeBorderWidth}px`;

const renderWithCanvas = async (): Promise<void> => {
  const slice = (await fakeRequest("graph.slice", {
    analysisId: AN,
    level: "PACKAGE",
  })) as GraphSlice;
  const { elements } = toGraphView(slice);
  const noop = (): void => undefined;
  render(
    <QueryClientProvider client={createQueryClient()}>
      <ArchitectureOutline
        analysisId={AN}
        focus={null}
        onCollapse={noop}
        onExpand={noop}
        onSelect={noop}
        view={LEVEL_ONE}
      />
      <ArchitectureGraph
        elements={elements}
        label={t.shell.regions.canvas}
        onExpand={noop}
        onRenderError={noop}
        onSelect={noop}
        selectedElementId={null}
        slice={slice}
      />
    </QueryClientProvider>
  );
  await waitForRows(ROOT_ROWS);
  await waitFor(() => expect(window.__STRATA_GRAPH__).toBeDefined());
};

describe("o mundo que as sequências de teclado percorrem", () => {
  test("a raiz é o nível 1, e o pacote aberto é a segunda linha dela, e não a última", () => {
    expect(ROOT_IDS).toStrictEqual(PACKAGE_IDS);
    expect(ROOT_IDS.indexOf(DRILLED)).toBe(1);
    expect(LAST_PACKAGE).not.toBe(DRILLED);
    expect(TYPE_IDS.length).toBeGreaterThanOrEqual(3);
  });
});

describe("ArchitectureOutline: uma parada de Tab", () => {
  test("exatamente uma linha tabulável, e a árvore em si sem tabIndex", async () => {
    setup();

    await waitForRows(ROOT_ROWS);

    expect(tabbableRows()).toStrictEqual([rowById(FIRST_PACKAGE)]);
    expect(screen.getByRole("tree").hasAttribute("tabindex")).toBe(false);
    expect(
      treeItems().every((row) => row.getAttribute("tabindex") !== null)
    ).toBe(true);
  });

  test("a linha tabulável é a selecionada quando há uma; senão o pacote aberto", async () => {
    const { rerender } = setup({ focus: LAST_PACKAGE });

    await waitForRows(ROOT_ROWS);
    expect(tabbableRows()).toStrictEqual([rowById(LAST_PACKAGE)]);

    rerender({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);
    expect(tabbableRows()).toStrictEqual([rowById(DRILLED)]);
  });

  test("focar a linha escreve activeElementId", async () => {
    setup();
    await waitForRows(ROOT_ROWS);

    focusRow(FIRST_PACKAGE);

    expect(activeElementId()).toBe(FIRST_PACKAGE);
  });

  test("ArrowDown move o foco do DOM e o activeElementId, e o canvas destaca o nó", async () => {
    await renderWithCanvas();

    focusRow(FIRST_PACKAGE);
    expect(window.__STRATA_GRAPH__?.style(FIRST_PACKAGE, "border-width")).toBe(
      activeBorder
    );

    press("ArrowDown");

    expect(document.activeElement).toBe(rowById(DRILLED));
    expect(activeElementId()).toBe(DRILLED);
    expect(window.__STRATA_GRAPH__?.style(DRILLED, "border-width")).toBe(
      activeBorder
    );
    expect(
      window.__STRATA_GRAPH__?.style(FIRST_PACKAGE, "border-width")
    ).not.toBe(activeBorder);
    expect(tabbableRows()).toStrictEqual([rowById(DRILLED)]);
  });
});

describe("ArchitectureOutline: abrir e fechar pelo teclado", () => {
  test("→ em um pacote fechado chama onExpand e, na vista TYPE, a mesma linha mantém o foco", async () => {
    const { onExpand, onSelect, rerender } = setup();
    await waitForRows(ROOT_ROWS);
    const drilled = focusRow(DRILLED);

    press("ArrowRight");

    expect(onExpand).toHaveBeenCalledExactlyOnceWith(DRILLED);
    expect(onSelect).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(drilled);

    rerender({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);

    expect(rowById(DRILLED)).toBe(drilled);
    expect(document.activeElement).toBe(drilled);
    expect(activeElementId()).toBe(DRILLED);
  });

  test("a rede de segurança: se o foco caiu no <body> durante a troca que a estrutura pediu, volta à linha", async () => {
    const { rerender } = setup();
    await waitForRows(ROOT_ROWS);
    focusRow(DRILLED);

    press("ArrowRight");
    act(() => (document.activeElement as HTMLElement | null)?.blur());
    expect(document.activeElement).toBe(document.body);
    rerender({ view: drilledTypes });

    expect(document.activeElement).toBe(rowById(DRILLED));
  });

  test("uma troca de vista que a estrutura não pediu não puxa o foco para ela", async () => {
    const { rerender } = setup();
    await waitForRows(ROOT_ROWS);

    rerender({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);

    expect(document.activeElement).toBe(document.body);
  });

  test("→ no pacote aberto entra no primeiro filho; ← no filho volta ao pacote", async () => {
    const { onCollapse, onExpand } = setup({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);
    focusRow(DRILLED);

    press("ArrowRight");
    expect(document.activeElement).toBe(rowById(FIRST_TYPE));
    expect(activeElementId()).toBe(FIRST_TYPE);

    press("ArrowRight");
    expect(document.activeElement).toBe(rowById(FIRST_TYPE));

    press("ArrowLeft");
    expect(document.activeElement).toBe(rowById(DRILLED));
    expect(onExpand).not.toHaveBeenCalled();
    expect(onCollapse).not.toHaveBeenCalled();
  });

  test("← no pacote aberto chama onCollapse e o foco fica nele", async () => {
    const { onCollapse, rerender } = setup({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);
    const drilled = focusRow(DRILLED);

    press("ArrowLeft");

    expect(onCollapse).toHaveBeenCalledOnce();
    rerender({ focus: DRILLED, view: LEVEL_ONE });
    await waitForRows(ROOT_ROWS);
    expect(document.activeElement).toBe(drilled);
  });

  test("← em um pacote fechado e → em um tipo não fazem nada", async () => {
    const { onCollapse, onExpand } = setup({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);

    focusRow(FIRST_PACKAGE);
    press("ArrowLeft");
    focusRow(LAST_TYPE);
    press("ArrowRight");

    expect(document.activeElement).toBe(rowById(LAST_TYPE));
    expect(onCollapse).not.toHaveBeenCalled();
    expect(onExpand).not.toHaveBeenCalled();
  });

  test("clicar na seta de um pacote abre ou fecha sem selecionar", async () => {
    const { onCollapse, onExpand, onSelect } = setup({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);

    const chevronOf = (id: string): Element => {
      const chevron = rowById(id).querySelector("[data-chevron]");
      if (chevron === null) {
        throw new Error(`sem seta em ${id}`);
      }
      return chevron;
    };
    fireEvent.click(chevronOf(LAST_PACKAGE));
    fireEvent.click(chevronOf(DRILLED));

    expect(onExpand).toHaveBeenCalledExactlyOnceWith(LAST_PACKAGE);
    expect(onCollapse).toHaveBeenCalledOnce();
    expect(onSelect).not.toHaveBeenCalled();
  });
});

describe("ArchitectureOutline: selecionar não segue o foco", () => {
  test("Enter, Espaço e clique selecionam com (id, kind); as setas nunca", async () => {
    const { onSelect } = setup({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);
    focusRow(FIRST_PACKAGE);

    press("ArrowDown");
    press("ArrowDown");
    press("ArrowUp");
    expect(onSelect).not.toHaveBeenCalled();

    press("Enter");
    focusRow(SECOND_TYPE);
    press(" ");
    fireEvent.click(rowById(LAST_PACKAGE));

    expect(onSelect.mock.calls).toStrictEqual([
      [DRILLED, "PACKAGE"],
      [SECOND_TYPE, "TYPE"],
      [LAST_PACKAGE, "PACKAGE"],
    ]);
  });

  test("Home e End vão à primeira e à última linha", async () => {
    setup({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);
    focusRow(FIRST_TYPE);

    press("End");
    expect(document.activeElement).toBe(rowById(LAST_PACKAGE));

    press("Home");
    expect(document.activeElement).toBe(rowById(FIRST_PACKAGE));
  });

  test("type-ahead: a próxima linha cujo rótulo começa com o que se digitou, e o buffer zera", async () => {
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
      {
        method: "outline.children",
        request: { parentId: null },
        result: {
          items: [
            { childCount: 0, id: "pkg:alfa", kind: "PACKAGE", label: "Alfa" },
            { childCount: 0, id: "pkg:beta", kind: "PACKAGE", label: "Beta" },
            { childCount: 0, id: "pkg:bravo", kind: "PACKAGE", label: "Bravo" },
          ],
          nextCursor: null,
        },
      },
    ] satisfies readonly FakeTranscriptOverride[]);
    const now = vi.spyOn(Date, "now").mockReturnValue(1000);
    setup();
    await waitForRows(3);
    focusRow("pkg:alfa");

    press("b");
    expect(document.activeElement).toBe(rowById("pkg:beta"));

    now.mockReturnValue(1100);
    press("R");
    expect(document.activeElement).toBe(rowById("pkg:bravo"));

    focusRow("pkg:beta");
    now.mockReturnValue(5000);
    press("r");
    expect(document.activeElement).toBe(rowById("pkg:beta"));
  });

  test("* não faz nada: há um pacote aberto por vez", async () => {
    const { onExpand } = setup();
    await waitForRows(ROOT_ROWS);
    focusRow(FIRST_PACKAGE);

    press("*");

    expect(onExpand).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(rowById(FIRST_PACKAGE));
  });
});

describe("ArchitectureOutline: o destaque cruzado", () => {
  test("o foco que sai da árvore solta o destaque, só se ele ainda é o dela", async () => {
    setup();
    await waitForRows(ROOT_ROWS);
    const outside = screen.getByRole("button", { name: "fora" });

    focusRow(FIRST_PACKAGE);
    act(() => outside.focus());
    expect(activeElementId()).toBeNull();

    focusRow(DRILLED);
    act(() => useAppStore.getState().setActiveElementId(LAST_PACKAGE));
    act(() => outside.focus());
    expect(activeElementId()).toBe(LAST_PACKAGE);
  });

  test("o ponteiro sobre uma linha escreve; ao sair, volta à linha focada ou solta", async () => {
    setup();
    await waitForRows(ROOT_ROWS);
    const outside = screen.getByRole("button", { name: "fora" });

    fireEvent.mouseOver(rowById(DRILLED));
    expect(activeElementId()).toBe(DRILLED);
    fireEvent.mouseOut(rowById(DRILLED), { relatedTarget: outside });
    expect(activeElementId()).toBeNull();

    focusRow(FIRST_PACKAGE);
    fireEvent.mouseOver(rowById(LAST_PACKAGE));
    expect(activeElementId()).toBe(LAST_PACKAGE);
    fireEvent.mouseOut(rowById(LAST_PACKAGE), { relatedTarget: outside });
    expect(activeElementId()).toBe(FIRST_PACKAGE);
  });

  test("o ponteiro cruzando outro nó do canvas não apaga o destaque que o foco de teclado segura", async () => {
    await renderWithCanvas();
    focusRow(FIRST_PACKAGE);
    const last = liveInstance().getElementById(LAST_PACKAGE);

    act(() => {
      last.emit("mouseover");
    });
    expect(window.__STRATA_GRAPH__?.style(LAST_PACKAGE, "border-width")).toBe(
      activeBorder
    );
    act(() => {
      last.emit("mouseout");
    });

    expect(document.activeElement).toBe(rowById(FIRST_PACKAGE));
    expect(activeElementId()).toBe(FIRST_PACKAGE);
    expect(rowById(FIRST_PACKAGE).hasAttribute("data-active")).toBe(true);
    expect(window.__STRATA_GRAPH__?.style(FIRST_PACKAGE, "border-width")).toBe(
      activeBorder
    );
    expect(
      window.__STRATA_GRAPH__?.style(LAST_PACKAGE, "border-width")
    ).not.toBe(activeBorder);
  });

  test("a linha focada que uma troca de vista de fora tira do DOM solta o destaque que a árvore escreveu", async () => {
    const { rerender } = setup({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);
    focusRow(FIRST_TYPE);
    expect(activeElementId()).toBe(FIRST_TYPE);

    rerender({ view: LEVEL_ONE });
    await waitForRows(ROOT_ROWS);

    expect(document.activeElement).toBe(document.body);
    expect(activeElementId()).toBeNull();
  });

  test("depois dessa troca a árvore não se diz mais focada: no Avançar, a tabulável é o pacote aberto", async () => {
    const { rerender } = setup({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);
    focusRow(FIRST_TYPE);

    rerender({ view: LEVEL_ONE });
    await waitForRows(ROOT_ROWS);
    rerender({ view: drilledTypes });
    await waitForRows(DRILLED_ROWS);

    expect(document.activeElement).toBe(document.body);
    expect(tabbableRows()).toStrictEqual([rowById(DRILLED)]);
    expect(rowById(FIRST_TYPE).hasAttribute("data-active")).toBe(false);
  });

  test("focar uma pseudo-linha solta o id que a árvore escreveu", async () => {
    failChildrenOf(FIRST_PACKAGE);
    setup({ view: { level: "TYPE", parentId: FIRST_PACKAGE } });
    const rows = await waitForRows(ROOT_ROWS + 1);
    focusRow(FIRST_PACKAGE);

    press("ArrowDown");

    expect(document.activeElement).toBe(rows[1]);
    expect(rows[1]?.textContent).toBe(t.outline.failed);
    expect(activeElementId()).toBeNull();

    press("ArrowLeft");
    expect(document.activeElement).toBe(rowById(FIRST_PACKAGE));
  });

  test("desmontar solta o destaque", async () => {
    const { unmount } = setup();
    await waitForRows(ROOT_ROWS);
    focusRow(LAST_PACKAGE);

    unmount();

    expect(activeElementId()).toBeNull();
  });

  test("uma linha marca data-active quando o destaque é dela, sem rolar", async () => {
    setup();
    await waitForRows(ROOT_ROWS);

    act(() => useAppStore.getState().setActiveElementId(LAST_PACKAGE));

    expect(rowById(LAST_PACKAGE).hasAttribute("data-active")).toBe(true);
    expect(rowById(FIRST_PACKAGE).hasAttribute("data-active")).toBe(false);
    expect(document.activeElement).toBe(document.body);
  });
});

describe("ArchitectureOutline: o foco de fora", () => {
  test("ref.focus() foca a linha tabulável; sem linhas, o título", async () => {
    const outlineRef = createRef<OutlineHandle>();
    setup({ focus: DRILLED, outlineRef });
    await waitForRows(ROOT_ROWS);

    act(() => outlineRef.current?.focus());

    expect(document.activeElement).toBe(rowById(DRILLED));
  });

  test("ref.focus() sem linhas foca o título da estrutura", async () => {
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
      {
        method: "outline.children",
        request: { parentId: null },
        result: { items: [], nextCursor: null },
      },
    ] satisfies readonly FakeTranscriptOverride[]);
    const outlineRef = createRef<OutlineHandle>();
    setup({ outlineRef });
    await screen.findByText(t.graph.empty.PACKAGE);

    act(() => outlineRef.current?.focus());

    expect(document.activeElement).toBe(
      screen.getByRole("heading", { name: t.outline.title })
    );
  });

  test("um focus de fora (canvas, busca) leva a linha tabulável sem mover o foco do DOM", async () => {
    const { rerender } = setup();
    await waitForRows(ROOT_ROWS);

    rerender({ focus: LAST_PACKAGE });

    expect(tabbableRows()).toStrictEqual([rowById(LAST_PACKAGE)]);
    expect(document.activeElement).toBe(document.body);
  });

  test("com o foco do DOM na árvore, um focus de fora não rouba a linha tabulável", async () => {
    const { rerender } = setup();
    await waitForRows(ROOT_ROWS);
    focusRow(FIRST_PACKAGE);

    rerender({ focus: LAST_PACKAGE });

    expect(tabbableRows()).toStrictEqual([rowById(FIRST_PACKAGE)]);
    expect(document.activeElement).toBe(rowById(FIRST_PACKAGE));
  });
});
