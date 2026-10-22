import { QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRouteWithContext,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import type { MouseEvent } from "react";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  type MockInstance,
  test,
  vi,
} from "vitest";
import { fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import { ThemeProvider } from "@/components/theme-provider";
import { toGraphView } from "@/features/graph/adapter";
import type { ArchitectureGraphProps } from "@/features/graph/architecture-graph";
import type { ArchitectureOutlineProps } from "@/features/outline/architecture-outline";
import type { NodeSearchProps } from "@/features/outline/node-search";
import { statusReport } from "@/features/progress/__tests__/status-report";
import { formatProtocolError } from "@/i18n/errors";
import { interpolate, plural } from "@/i18n/format";
import { t } from "@/i18n/messages";
import type { GraphSlice, SearchHit } from "@/protocol/graph";
import type {
  AnalysisStageReport,
  AnalysisStatusReport,
} from "@/protocol/repository";
import { createQueryClient } from "@/queries/client";
import { graphSliceQueryOptions } from "@/queries/options";
import type { RouterContext } from "@/router";
import { Route as RepositoryRoute } from "@/routes/repository.$handle";
import { type BackendState, useAppStore } from "@/state/app-store";

const IDS = vi.hoisted(() => ({
  BILLING: "pkg:br.pucpr.billing",
  ORDER_TYPE: "type:br.pucpr.orders.Order",
  ORDERS: "pkg:br.pucpr.orders",
}));

const focusOutline = vi.hoisted(() => vi.fn<() => void>());

vi.mock("@/features/graph/adapter", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("@/features/graph/adapter")>();
  return { ...actual, toGraphView: vi.fn(actual.toGraphView) };
});

vi.mock("@/features/graph/architecture-graph", async (importOriginal) => {
  const actual =
    await importOriginal<
      typeof import("@/features/graph/architecture-graph")
    >();
  let current: ArchitectureGraphProps | null = null;
  const onClick = (event: MouseEvent<HTMLButtonElement>): void => {
    const { action, id } = event.currentTarget.dataset;
    if (action === "select") {
      current?.onSelect(id ?? null);
    } else if (action === "expand") {
      current?.onExpand(id ?? "");
    } else {
      current?.onRenderError(new Error("o cy.add descartou elementos"));
    }
  };
  const FakeArchitectureGraph = (props: ArchitectureGraphProps) => {
    current = props;
    const { elements, label, selectedElementId, slice } = props;
    const ids = elements?.nodes.map((node) => node.data.id ?? "") ?? [];
    return (
      <>
        <div
          aria-label={label}
          data-node-count={elements?.nodes.length ?? "none"}
          data-selected={selectedElementId ?? ""}
          data-slice-level={slice?.level ?? ""}
          role="img"
        />
        {ids.map((id) => (
          <button
            data-action="select"
            data-id={id}
            key={`select:${id}`}
            onClick={onClick}
            type="button"
          >
            {`select:${id}`}
          </button>
        ))}
        {ids.map((id) => (
          <button
            data-action="expand"
            data-id={id}
            key={`expand:${id}`}
            onClick={onClick}
            type="button"
          >
            {`expand:${id}`}
          </button>
        ))}
        <button data-action="select" onClick={onClick} type="button">
          select:none
        </button>
        <button data-action="render-error" onClick={onClick} type="button">
          render-error
        </button>
      </>
    );
  };
  return { ...actual, ArchitectureGraph: FakeArchitectureGraph };
});

vi.mock("@/features/outline/architecture-outline", async () => {
  const { useImperativeHandle } = await import("react");
  const rows = [
    { id: IDS.ORDERS, kind: "PACKAGE" },
    { id: IDS.BILLING, kind: "PACKAGE" },
    { id: IDS.ORDER_TYPE, kind: "TYPE" },
  ] as const;
  let current: ArchitectureOutlineProps | null = null;
  const onClick = (event: MouseEvent<HTMLButtonElement>): void => {
    const { action, index } = event.currentTarget.dataset;
    const row = rows[Number(index)];
    if (action === "collapse") {
      current?.onCollapse();
    } else if (row !== undefined && action === "select") {
      current?.onSelect(row.id, row.kind);
    } else if (row !== undefined) {
      current?.onExpand(row.id);
    }
  };
  const FakeArchitectureOutline = (props: ArchitectureOutlineProps) => {
    current = props;
    useImperativeHandle(props.ref, () => ({ focus: focusOutline }), []);
    return (
      <section
        data-analysis={props.analysisId}
        data-focus={props.focus ?? ""}
        data-level={props.view.level}
        data-parent={props.view.parentId ?? ""}
        data-testid="outline"
      >
        {rows.map((row, index) => (
          <button
            data-action="select"
            data-index={index}
            key={`select:${row.id}`}
            onClick={onClick}
            type="button"
          >
            {`outline:select:${row.id}`}
          </button>
        ))}
        {rows.map((row, index) =>
          row.kind === "PACKAGE" ? (
            <button
              data-action="expand"
              data-index={index}
              key={`expand:${row.id}`}
              onClick={onClick}
              type="button"
            >
              {`outline:expand:${row.id}`}
            </button>
          ) : null
        )}
        <button data-action="collapse" onClick={onClick} type="button">
          outline:collapse
        </button>
      </section>
    );
  };
  return { ArchitectureOutline: FakeArchitectureOutline };
});

vi.mock("@/features/outline/node-search", () => {
  const hits = [
    {
      elementId: IDS.ORDER_TYPE,
      kind: "TYPE",
      label: "Order",
      packageId: IDS.ORDERS,
      qualifiedName: "br.pucpr.orders.Order",
    },
    {
      elementId: IDS.BILLING,
      kind: "PACKAGE",
      label: "billing",
      packageId: null,
      qualifiedName: "br.pucpr.billing",
    },
  ] as const satisfies readonly SearchHit[];
  let current: NodeSearchProps | null = null;
  const onClick = (event: MouseEvent<HTMLButtonElement>): void => {
    const hit = hits[Number(event.currentTarget.dataset.index)];
    if (hit !== undefined) {
      current?.onReveal(hit);
    }
  };
  const FakeNodeSearch = (props: NodeSearchProps) => {
    current = props;
    return (
      <search
        data-analysis={props.analysisId}
        data-focus={props.focus ?? ""}
        data-testid="search"
      >
        {hits.map((hit, index) => (
          <button
            data-index={index}
            key={hit.elementId}
            onClick={onClick}
            type="button"
          >
            {`search:reveal:${hit.elementId}`}
          </button>
        ))}
      </search>
    );
  };
  return { NodeSearch: FakeNodeSearch };
});

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";
const { BILLING, ORDER_TYPE, ORDERS } = IDS;
const SCREEN_URL = `/repository/${HANDLE}?analysis=${AN}`;
const BIDI_OVERRIDE = String.fromCodePoint(0x20_2e);

type StageStatus = AnalysisStageReport["status"];

const RUNNING_META = /^Em andamento há \d/;

const READY: BackendState = { phase: "READY", protocolVersion: 1 };
const initialStore = useAppStore.getState();

const fixtureStatus = async (): Promise<AnalysisStatusReport> =>
  (await fakeRequest("analysis.status", {
    analysisId: AN,
  })) as AnalysisStatusReport;

const withStructuralGraph = (
  report: AnalysisStatusReport,
  status: StageStatus
): AnalysisStatusReport => ({
  ...report,
  stages: report.stages.map((stage) =>
    stage.stage === "STRUCTURAL_GRAPH" ? { ...stage, status } : stage
  ),
});

const mapReadyStatus = async (): Promise<AnalysisStatusReport> =>
  withStructuralGraph(await fixtureStatus(), "COMPLETED");

let requestSpy: MockInstance<typeof transport.request>;

const serveStatus = (answer: () => Promise<unknown>): void => {
  requestSpy.mockImplementation((method, payload) =>
    method === "analysis.status" ? answer() : fakeRequest(method, payload)
  );
};

beforeEach(() => {
  useAppStore.setState({ ...initialStore, backend: READY }, true);
  requestSpy = vi.spyOn(transport, "request");
  serveStatus(mapReadyStatus);
});

afterEach(() => {
  vi.restoreAllMocks();
  focusOutline.mockClear();
});

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
});
const indexRoute = createRoute({
  component: () => <p>index</p>,
  getParentRoute: () => rootRoute,
  path: "/",
});
const repositoryRoute = createRoute({
  component: RepositoryRoute.options.component,
  getParentRoute: () => rootRoute,
  notFoundComponent: RepositoryRoute.options.notFoundComponent,
  params: RepositoryRoute.options.params,
  path: "/repository/$handle",
  search: RepositoryRoute.options.search,
  validateSearch: RepositoryRoute.options.validateSearch,
});
const routeTree = rootRoute.addChildren([indexRoute, repositoryRoute]);

interface RenderOptions {
  readonly main?: string;
  readonly seed?: (queryClient: ReturnType<typeof createQueryClient>) => void;
}

const renderAt = async (
  url: string,
  { main = t.shell.regions.canvas, seed }: RenderOptions = {}
) => {
  const queryClient = createQueryClient();
  seed?.(queryClient);
  const router = createRouter({
    context: { queryClient },
    history: createMemoryHistory({ initialEntries: [url] }),
    routeTree,
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <RouterProvider router={router} />
      </ThemeProvider>
    </QueryClientProvider>
  );
  await screen.findByRole("main", { name: main });
  return { queryClient, router };
};

const urlSearch = (
  router: Awaited<ReturnType<typeof renderAt>>["router"]
): Record<string, string> =>
  Object.fromEntries(new URLSearchParams(router.state.location.searchStr));

const canvas = () => screen.getByRole("img");

const waitForCanvas = async (count: number, labelPart: string) => {
  await waitFor(() => {
    expect(canvas().getAttribute("data-node-count")).toBe(String(count));
    expect(canvas().getAttribute("aria-label")).toContain(labelPart);
  });
};

const inspector = () =>
  screen.getByRole("complementary", { name: t.shell.regions.inspector });

const breadcrumb = () =>
  screen.getByRole("navigation", { name: t.shell.breadcrumb.label });

const currentCrumb = () =>
  breadcrumb().querySelector<HTMLElement>('[aria-current="page"]');

const liveRegion = () =>
  screen
    .getByRole("main", { name: t.shell.regions.canvas })
    .querySelector<HTMLElement>('[aria-live="polite"]');

const sidebar = () =>
  screen.getByRole("complementary", { name: t.shell.regions.sidebar });

const mapRegion = () =>
  screen.getByRole("main", { name: t.shell.regions.canvas });

const spyRequests = () => requestSpy;
const requestedMethods = (): string[] => [
  ...new Set(requestSpy.mock.calls.map(([method]) => method)),
];

const slicePayloads = (request: ReturnType<typeof spyRequests>) =>
  request.mock.calls
    .filter(([method]) => method === "graph.slice")
    .map(([, payload]) => payload);

const LEVEL_ONE_LABEL =
  "Mapa da arquitetura, pacotes: 3 pacotes e 3 dependências.";
const ORDERS_TYPES_LABEL =
  "Mapa da arquitetura, tipos: 3 tipos e 2 dependências.";

describe("/repository/$handle: a moldura", () => {
  test("monta os landmarks, o cabeçalho e a legenda, e a fatia chega ao canvas", async () => {
    await renderAt(SCREEN_URL);

    expect(screen.getByRole("banner")).toBeDefined();
    expect(screen.getAllByRole("main")).toHaveLength(1);
    expect(
      within(screen.getByRole("banner")).getByText(t.app.name)
    ).toBeDefined();
    expect(
      within(mapRegion()).getByRole("heading", {
        level: 1,
        name: t.shell.regions.canvas,
      })
    ).toBeDefined();
    expect(
      screen.getByRole("complementary", { name: t.shell.regions.sidebar })
    ).toBeDefined();
    expect(inspector()).toBeDefined();
    const header = within(screen.getByRole("banner"));
    expect(header.getByText(t.backend.READY)).toBeDefined();
    expect(await header.findByText("multi-module")).toBeDefined();
    expect(header.getByRole("button", { name: t.header.close })).toBeDefined();
    expect(header.getByRole("button", { name: "Alternar tema" })).toBeDefined();
    expect(within(mapRegion()).getByText(t.graph.legend.title)).toBeDefined();

    await waitForCanvas(3, "3 pacotes");
    expect(
      within(breadcrumb())
        .getByText(t.shell.breadcrumb.root)
        .getAttribute("aria-current")
    ).toBe("page");
    expect(within(inspector()).getByText(t.inspector.empty)).toBeDefined();
  });

  test("sem o param analysis, a preparação: o describe é pedido e o formulário aparece", async () => {
    await renderAt(`/repository/${HANDLE}`, {
      main: t.shell.regions.prepare,
    });

    expect(
      await screen.findByRole("heading", { level: 1, name: "multi-module" })
    ).toBeDefined();
    expect(
      screen.getByRole("button", { name: t.config.analyze })
    ).toBeDefined();
    expect(requestedMethods()).toStrictEqual(["repository.describe"]);
    expect(screen.queryByRole("img")).toBeNull();
    expect(
      screen.queryByRole("navigation", { name: t.shell.breadcrumb.label })
    ).toBeNull();
    expect(screen.queryByRole("complementary")).toBeNull();
    expect(screen.getAllByRole("main")).toHaveLength(1);
  });

  test("um analysis inválido conta como ausente: a preparação", async () => {
    await renderAt(`/repository/${HANDLE}?analysis=an_errado`, {
      main: t.shell.regions.prepare,
    });

    expect(
      await screen.findByRole("heading", { level: 1, name: "multi-module" })
    ).toBeDefined();
    expect(requestedMethods()).not.toContain("analysis.status");
  });

  test("um level inválido cai em PACKAGE sem apagar o parent", async () => {
    const { router } = await renderAt(
      `${SCREEN_URL}&level=PACOTE&parent=${ORDERS}`
    );

    await waitForCanvas(3, "pacotes");
    const match = router.state.matches.at(-1);
    expect(match?.search).toMatchObject({ level: "PACKAGE", parent: ORDERS });
    expect(
      within(breadcrumb())
        .getByText(t.shell.breadcrumb.root)
        .getAttribute("aria-current")
    ).toBe("page");
  });
});

describe("/repository/$handle: a vizinhança de um tipo", () => {
  test("TYPE_NEIGHBORHOOD cai no nível 1 até haver a vista da vizinhança", async () => {
    const request = spyRequests();
    const { router } = await renderAt(
      `${SCREEN_URL}&level=TYPE_NEIGHBORHOOD&parent=${ORDER_TYPE}`
    );

    await waitForCanvas(3, "pacotes");
    expect(slicePayloads(request)).toStrictEqual([
      { analysisId: AN, level: "PACKAGE" },
    ]);
    expect(currentCrumb()?.textContent).toBe(t.shell.breadcrumb.root);
    expect(within(breadcrumb()).queryByRole("link")).toBeNull();
    expect(urlSearch(router)).toMatchObject({
      level: "TYPE_NEIGHBORHOOD",
      parent: ORDER_TYPE,
    });
  });
});

describe("/repository/$handle: navegação", () => {
  test("expandir empilha level=TYPE&parent=…, e voltar acha o nível 1 no cache", async () => {
    const request = spyRequests();
    const { router } = await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");
    const lengthBefore = router.history.length;
    const levelOneSearch = urlSearch(router);

    fireEvent.click(screen.getByRole("button", { name: `expand:${ORDERS}` }));

    await waitForCanvas(3, "tipos");
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      level: "TYPE",
      parent: ORDERS,
    });
    expect(router.history.length).toBe(lengthBefore + 1);
    const parent = within(breadcrumb()).getByText("orders");
    expect(parent.tagName).toBe("BDI");
    expect(parent.getAttribute("lang")).toBe("");
    expect(parent.getAttribute("translate")).toBe("no");
    expect(slicePayloads(request)).toHaveLength(2);

    router.history.back();

    await waitForCanvas(3, "pacotes");
    expect(urlSearch(router)).toStrictEqual(levelOneSearch);
    expect(levelOneSearch).toMatchObject({ analysis: AN });
    expect(slicePayloads(request)).toHaveLength(2);
  });

  test("selecionar substitui a entrada e o inspetor mostra o nó", async () => {
    const { router } = await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");
    const lengthBefore = router.history.length;

    fireEvent.click(screen.getByRole("button", { name: `select:${BILLING}` }));

    await waitFor(() =>
      expect(canvas().getAttribute("data-selected")).toBe(BILLING)
    );
    expect(urlSearch(router)).toMatchObject({ focus: BILLING });
    expect(router.history.length).toBe(lengthBefore);
    expect(within(inspector()).getByText("billing").tagName).toBe("BDI");

    fireEvent.click(screen.getByRole("button", { name: "select:none" }));

    await waitFor(() =>
      expect(canvas().getAttribute("data-selected")).toBe("")
    );
    expect(urlSearch(router)).not.toHaveProperty("focus");
    expect(within(inspector()).getByText(t.inspector.empty)).toBeDefined();
  });

  test("com um pacote já selecionado, o botão do inspetor expande sem ponteiro", async () => {
    const { router } = await renderAt(`${SCREEN_URL}&focus=${ORDERS}`);
    await waitForCanvas(3, "pacotes");

    fireEvent.click(
      within(inspector()).getByRole("button", { name: t.inspector.expand })
    );

    await waitForCanvas(3, "tipos");
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      level: "TYPE",
      parent: ORDERS,
    });
  });

  test("um focus que não está na fatia deixa o inspetor vazio", async () => {
    await renderAt(`${SCREEN_URL}&focus=pkg:br.pucpr.inexistente`);
    await waitForCanvas(3, "pacotes");

    expect(within(inspector()).getByText(t.inspector.empty)).toBeDefined();
  });

  test("a raiz da trilha volta ao nível 1 com o pacote de onde se saiu selecionado", async () => {
    const { router } = await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}`
    );
    await waitForCanvas(3, "tipos");

    fireEvent.click(
      within(breadcrumb()).getByRole("link", { name: t.shell.breadcrumb.root })
    );

    await waitForCanvas(3, "pacotes");
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      focus: ORDERS,
      level: "PACKAGE",
    });
  });
});

describe("/repository/$handle: o foco em uma troca de nível", () => {
  test("a montagem não move o foco", async () => {
    await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");

    expect(document.activeElement).toBe(document.body);
  });

  test("expandir pelo inspetor leva o foco à trilha e anuncia a vista nova", async () => {
    await renderAt(`${SCREEN_URL}&focus=${ORDERS}`);
    await waitForCanvas(3, "pacotes");
    expect(liveRegion()?.textContent).toBe(LEVEL_ONE_LABEL);
    const expand = within(inspector()).getByRole("button", {
      name: t.inspector.expand,
    });
    expand.focus();

    fireEvent.click(expand);

    await waitForCanvas(3, "tipos");
    await waitFor(() => expect(document.activeElement).toBe(currentCrumb()));
    expect(currentCrumb()?.textContent).toBe("orders");
    expect(currentCrumb()?.tabIndex).toBe(-1);
    expect(currentCrumb()?.className).toContain("text-muted-foreground");
    expect(liveRegion()?.textContent).toBe(ORDERS_TYPES_LABEL);
  });

  test("voltar pela raiz da trilha leva o foco ao item da raiz", async () => {
    await renderAt(`${SCREEN_URL}&level=TYPE&parent=${ORDERS}`);
    await waitForCanvas(3, "tipos");
    const root = within(breadcrumb()).getByRole("link", {
      name: t.shell.breadcrumb.root,
    });
    root.focus();

    fireEvent.click(root);

    await waitForCanvas(3, "pacotes");
    await waitFor(() => expect(document.activeElement).toBe(currentCrumb()));
    expect(currentCrumb()?.textContent).toBe(t.shell.breadcrumb.root);
    expect(currentCrumb()?.className).toContain("text-muted-foreground");
    expect(liveRegion()?.textContent).toBe(LEVEL_ONE_LABEL);
  });

  test("uma troca de nível com o foco em outro controle não o rouba", async () => {
    const { router } = await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}`
    );
    await waitForCanvas(3, "tipos");
    const toggle = screen.getByRole("button", { name: "Alternar tema" });
    toggle.focus();

    router.history.push(SCREEN_URL);

    await waitForCanvas(3, "pacotes");
    expect(document.activeElement).toBe(toggle);
  });
});

describe("/repository/$handle: a falha de render e a fatia", () => {
  test("a falha de render vale só para a fatia dela: o drill-down desenha", async () => {
    await renderAt(`${SCREEN_URL}&focus=${ORDERS}`);
    await waitForCanvas(3, "pacotes");

    fireEvent.click(screen.getByRole("button", { name: "render-error" }));
    await screen.findByRole("alert");
    expect(canvas().getAttribute("data-node-count")).toBe("none");

    fireEvent.click(
      within(inspector()).getByRole("button", { name: t.inspector.expand })
    );

    await waitForCanvas(3, "tipos");
    expect(screen.queryByRole("alert")).toBeNull();
    expect(canvas().getAttribute("data-slice-level")).toBe("TYPE");
  });
});

describe("/repository/$handle: o inspetor e o adaptador", () => {
  test("o inspetor não adapta a fatia de novo: um pedido e uma adaptação por fatia", async () => {
    const adapt = vi.mocked(toGraphView);
    adapt.mockClear();
    const request = spyRequests();
    await renderAt(`${SCREEN_URL}&focus=${ORDERS}`);
    await waitForCanvas(3, "pacotes");
    await within(inspector()).findByText("orders");

    expect(slicePayloads(request)).toHaveLength(1);
    expect(adapt).toHaveBeenCalledTimes(1);

    fireEvent.click(
      within(inspector()).getByRole("button", { name: t.inspector.expand })
    );
    await waitForCanvas(3, "tipos");
    fireEvent.click(
      screen.getByRole("button", { name: `select:${ORDER_TYPE}` })
    );
    await within(inspector()).findByText("Order");

    expect(slicePayloads(request)).toHaveLength(2);
    expect(adapt).toHaveBeenCalledTimes(2);
  });
});

describe("/repository/$handle: o rótulo do pai na trilha", () => {
  const seedLevelOne =
    (label: string) =>
    (queryClient: ReturnType<typeof createQueryClient>): void => {
      const slice = {
        aggregated: false,
        budget: { edges: 1200, nodes: 300 },
        edges: [],
        level: "PACKAGE",
        nodes: [
          {
            fanIn: 0,
            fanOut: 0,
            id: ORDERS,
            kind: "PACKAGE",
            label,
            loc: 1,
            pagerank: 1,
            resolutionMix: { INFERRED: 0, RESOLVED: 0, UNRESOLVED_EXTERNAL: 0 },
            typeCount: 1,
          },
        ],
        parentId: null,
        totals: { edges: 0, nodes: 1 },
        truncated: false,
      } satisfies GraphSlice;
      queryClient.setQueryData(
        graphSliceQueryOptions(AN, { level: "PACKAGE" }).queryKey,
        slice
      );
    };

  test("em um link direto, sem o nível 1 em cache, o texto genérico", async () => {
    await renderAt(`${SCREEN_URL}&level=TYPE&parent=${ORDERS}`);

    expect(
      within(breadcrumb()).getByText(t.shell.breadcrumb.selectedPackage)
    ).toBeDefined();
  });

  test("um rótulo hostil chega saneado", async () => {
    await renderAt(`${SCREEN_URL}&level=TYPE&parent=${ORDERS}`, {
      seed: seedLevelOne(`ord${BIDI_OVERRIDE}ers`),
    });

    const label = within(breadcrumb()).getByText("orders");
    expect(label.tagName).toBe("BDI");
    expect(label.className).not.toContain("font-mono");
  });

  test("o pacote padrão, de rótulo vazio, recebe o nome que o canvas lhe dá", async () => {
    await renderAt(`${SCREEN_URL}&level=TYPE&parent=${ORDERS}`, {
      seed: seedLevelOne(""),
    });

    expect(
      within(breadcrumb()).getByText(t.graph.defaultPackage)
    ).toBeDefined();
  });

  test("a fixture do nível 1 é a que a trilha lê", async () => {
    const fixture = (await fakeRequest("graph.slice", {
      analysisId: AN,
      level: "PACKAGE",
    })) as GraphSlice;

    expect(fixture.nodes.find((node) => node.id === ORDERS)?.label).toBe(
      "orders"
    );
  });
});

describe("/repository/$handle: a barra lateral", () => {
  const sidebarChildren = (): readonly (string | undefined)[] =>
    [...sidebar().querySelectorAll<HTMLElement>("[data-testid]")].map(
      (element) => element.dataset.testid
    );

  test("com uma análise, a busca e depois a estrutura, com a vista resolvida e o focus da URL", async () => {
    await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}&focus=${ORDER_TYPE}`
    );

    expect(sidebarChildren()).toStrictEqual(["search", "outline"]);
    const outline = within(sidebar()).getByTestId("outline");
    expect(outline.getAttribute("data-analysis")).toBe(AN);
    expect(outline.getAttribute("data-level")).toBe("TYPE");
    expect(outline.getAttribute("data-parent")).toBe(ORDERS);
    expect(outline.getAttribute("data-focus")).toBe(ORDER_TYPE);
    const search = within(sidebar()).getByTestId("search");
    expect(search.getAttribute("data-analysis")).toBe(AN);
    expect(search.getAttribute("data-focus")).toBe(ORDER_TYPE);
  });

  test("a coluna da barra lateral tem altura limitada, para a árvore virtualizada rolar nela", async () => {
    await renderAt(SCREEN_URL);

    const column = sidebar().firstElementChild;
    expect(column?.className.split(" ")).toStrictEqual(
      expect.arrayContaining(["flex", "flex-col", "h-full", "min-h-0"])
    );
  });

  test("a estrutura recebe a vista resolvida: TYPE_NEIGHBORHOOD é o nível 1", async () => {
    await renderAt(
      `${SCREEN_URL}&level=TYPE_NEIGHBORHOOD&parent=${ORDER_TYPE}`
    );

    const outline = within(sidebar()).getByTestId("outline");
    expect(outline.getAttribute("data-level")).toBe("PACKAGE");
    expect(outline.getAttribute("data-parent")).toBe("");
    expect(outline.getAttribute("data-focus")).toBe("");
  });

  test("sem análise, nem busca, nem estrutura, nem botão de pular: só o describe é pedido", async () => {
    await renderAt(`/repository/${HANDLE}?level=TYPE&parent=${ORDERS}`, {
      main: t.shell.regions.prepare,
    });
    await screen.findByRole("heading", { level: 1, name: "multi-module" });

    expect(
      screen.queryByRole("complementary", { name: t.shell.regions.sidebar })
    ).toBeNull();
    expect(screen.queryByTestId("search")).toBeNull();
    expect(screen.queryByTestId("outline")).toBeNull();
    expect(
      screen.queryByRole("button", { name: t.shell.skipToOutline })
    ).toBeNull();
    expect(requestedMethods()).toStrictEqual(["repository.describe"]);
  });
});

describe("/repository/$handle: a estrutura muda a URL pelas transições", () => {
  test("Enter em um tipo do pacote aberto substitui a entrada: só o focus muda", async () => {
    const { router } = await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}`
    );
    await waitForCanvas(3, "tipos");
    const lengthBefore = router.history.length;

    fireEvent.click(
      screen.getByRole("button", { name: `outline:select:${ORDER_TYPE}` })
    );

    await waitFor(() =>
      expect(canvas().getAttribute("data-selected")).toBe(ORDER_TYPE)
    );
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      focus: ORDER_TYPE,
      level: "TYPE",
      parent: ORDERS,
    });
    expect(router.history.length).toBe(lengthBefore);
    expect(
      within(sidebar()).getByTestId("outline").getAttribute("data-focus")
    ).toBe(ORDER_TYPE);
  });

  test("Enter em um pacote no nível 1 substitui a entrada", async () => {
    const { router } = await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");
    const lengthBefore = router.history.length;

    fireEvent.click(
      screen.getByRole("button", { name: `outline:select:${BILLING}` })
    );

    await waitFor(() =>
      expect(canvas().getAttribute("data-selected")).toBe(BILLING)
    );
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      focus: BILLING,
      level: "PACKAGE",
    });
    expect(router.history.length).toBe(lengthBefore);
  });

  test("Enter em um pacote enquanto o mapa mostra tipos empilha o nível 1, com ele selecionado", async () => {
    const { router } = await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}`
    );
    await waitForCanvas(3, "tipos");
    const lengthBefore = router.history.length;

    fireEvent.click(
      screen.getByRole("button", { name: `outline:select:${BILLING}` })
    );

    await waitForCanvas(3, "pacotes");
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      focus: BILLING,
      level: "PACKAGE",
    });
    expect(router.history.length).toBe(lengthBefore + 1);
    expect(canvas().getAttribute("data-selected")).toBe(BILLING);

    router.history.back();

    await waitForCanvas(3, "tipos");
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      level: "TYPE",
      parent: ORDERS,
    });
  });

  test("a seta para a direita empilha o pacote aberto, e voltar desfaz", async () => {
    const { router } = await renderAt(`${SCREEN_URL}&focus=${BILLING}`);
    await waitForCanvas(3, "pacotes");
    const lengthBefore = router.history.length;
    const levelOneSearch = urlSearch(router);

    fireEvent.click(
      screen.getByRole("button", { name: `outline:expand:${ORDERS}` })
    );

    await waitForCanvas(3, "tipos");
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      level: "TYPE",
      parent: ORDERS,
    });
    expect(router.history.length).toBe(lengthBefore + 1);

    router.history.back();

    await waitForCanvas(3, "pacotes");
    expect(urlSearch(router)).toStrictEqual(levelOneSearch);
    expect(levelOneSearch).toMatchObject({ focus: BILLING });
  });

  test("a seta para a esquerda empilha a volta ao nível 1, com o pacote de onde se saiu selecionado", async () => {
    const { router } = await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}&focus=${ORDER_TYPE}`
    );
    await waitForCanvas(3, "tipos");
    const lengthBefore = router.history.length;

    fireEvent.click(screen.getByRole("button", { name: "outline:collapse" }));

    await waitForCanvas(3, "pacotes");
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      focus: ORDERS,
      level: "PACKAGE",
    });
    expect(router.history.length).toBe(lengthBefore + 1);

    router.history.back();

    await waitForCanvas(3, "tipos");
    expect(urlSearch(router)).toMatchObject({ focus: ORDER_TYPE });
  });
});

describe("/repository/$handle: a busca leva o mapa até o resultado", () => {
  test("um tipo de outro pacote empilha os tipos dele, com o tipo selecionado", async () => {
    const { router } = await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");
    const lengthBefore = router.history.length;
    const levelOneSearch = urlSearch(router);

    fireEvent.click(
      screen.getByRole("button", { name: `search:reveal:${ORDER_TYPE}` })
    );

    await waitForCanvas(3, "tipos");
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      focus: ORDER_TYPE,
      level: "TYPE",
      parent: ORDERS,
    });
    expect(router.history.length).toBe(lengthBefore + 1);
    expect(canvas().getAttribute("data-selected")).toBe(ORDER_TYPE);
    expect(await within(inspector()).findByText("Order")).toBeDefined();

    router.history.back();

    await waitForCanvas(3, "pacotes");
    expect(urlSearch(router)).toStrictEqual(levelOneSearch);
  });

  test("um pacote no nível 1 substitui a entrada: só a seleção muda", async () => {
    const { router } = await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");
    const lengthBefore = router.history.length;

    fireEvent.click(
      screen.getByRole("button", { name: `search:reveal:${BILLING}` })
    );

    await waitFor(() =>
      expect(canvas().getAttribute("data-selected")).toBe(BILLING)
    );
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      focus: BILLING,
      level: "PACKAGE",
    });
    expect(router.history.length).toBe(lengthBefore);
  });
});

describe("/repository/$handle: o botão de pular para a estrutura", () => {
  const FOCUSABLE =
    'a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"])';

  test("é o primeiro focável do <main>, fora do canvas, e leva o foco à estrutura sem tocar no histórico", async () => {
    const { router } = await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");
    const skip = within(mapRegion()).getByRole("button", {
      name: t.shell.skipToOutline,
    });
    const hrefBefore = router.state.location.href;
    const lengthBefore = router.history.length;

    expect(mapRegion().querySelector(FOCUSABLE)).toBe(skip);
    expect(mapRegion().firstElementChild).toBe(skip);
    expect(canvas().contains(skip)).toBe(false);

    fireEvent.click(skip);

    expect(focusOutline).toHaveBeenCalledTimes(1);
    expect(router.history.length).toBe(lengthBefore);
    expect(router.state.location.href).toBe(hrefBefore);
  });
});

describe("/repository/$handle: um elemento que o mapa cortou", () => {
  const CUT_TYPE = "type:br.pucpr.orders.Cut";

  const truncateOrders = async (): Promise<void> => {
    const fixture = (await fakeRequest("graph.slice", {
      analysisId: AN,
      level: "TYPE",
      parentId: ORDERS,
    })) as GraphSlice;
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
      {
        method: "graph.slice",
        request: { level: "TYPE", parentId: ORDERS },
        result: {
          ...fixture,
          totals: { edges: 9120, nodes: 1842 },
          truncated: true,
        },
      },
    ] satisfies readonly FakeTranscriptOverride[]);
  };

  test("fora de uma fatia truncada, o inspetor diz que o limite o deixou de fora", async () => {
    await truncateOrders();
    await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}&focus=${CUT_TYPE}`
    );
    await waitForCanvas(3, "tipos");

    expect(
      await within(inspector()).findByText(t.inspector.notOnMap.truncated)
    ).toBeDefined();
    expect(within(inspector()).queryByText(t.inspector.empty)).toBeNull();
  });

  test("em uma fatia truncada, um tipo que está no mapa mostra o resumo dele", async () => {
    await truncateOrders();
    await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}&focus=${ORDER_TYPE}`
    );
    await waitForCanvas(3, "tipos");

    expect(await within(inspector()).findByText("Order")).toBeDefined();
    expect(
      within(inspector()).queryByText(t.inspector.notOnMap.truncated)
    ).toBeNull();
  });

  test("fora de um nível 1 agrupado, o inspetor diz que o pacote está em um grupo", async () => {
    const fixture = (await fakeRequest("graph.slice", {
      analysisId: AN,
      level: "PACKAGE",
    })) as GraphSlice;
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
      {
        method: "graph.slice",
        request: { level: "PACKAGE" },
        result: {
          ...fixture,
          aggregated: true,
          totals: { edges: 9120, nodes: 1842 },
        },
      },
    ] satisfies readonly FakeTranscriptOverride[]);
    await renderAt(`${SCREEN_URL}&focus=pkg:br.pucpr.legacy.deep`);
    await waitForCanvas(3, "pacotes");

    expect(
      await within(inspector()).findByText(t.inspector.notOnMap.aggregated)
    ).toBeDefined();
    expect(within(inspector()).queryByText(t.inspector.empty)).toBeNull();
  });

  test("em uma fatia completa, um focus ausente continua sendo seleção nenhuma", async () => {
    await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}&focus=${CUT_TYPE}`
    );
    await waitForCanvas(3, "tipos");

    expect(within(inspector()).getByText(t.inspector.empty)).toBeDefined();
    expect(
      within(inspector()).queryByText(t.inspector.notOnMap.truncated)
    ).toBeNull();
  });
});

describe("/repository/$handle: a legenda", () => {
  test("mora no painel do mapa, depois do canvas, e não mais na barra lateral", async () => {
    await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");

    const legend = within(mapRegion()).getByRole("region", {
      name: t.graph.legend.title,
    });
    expect(canvas().parentElement?.nextElementSibling).toBe(legend);
    expect(within(sidebar()).queryByText(t.graph.legend.title)).toBeNull();
  });
});

describe("a tela pelo analysis.status em /repository/$handle?analysis=", () => {
  const analysisRegion = () =>
    screen.getByRole("main", { name: t.shell.regions.analysis });
  const banner = () => within(screen.getByRole("banner"));
  const READ_METHODS = ["graph.slice", "outline.children", "graph.search"];

  const runningReport = (graph: StageStatus = "PENDING") =>
    statusReport({
      completeness: { totalFiles: 9 },
      stages: {
        LAYOUT_DISCOVERY: {
          counters: { selectedRoots: 3 },
          durationMs: 2,
          status: "COMPLETED",
        },
        REPOSITORY_VALIDATION: { durationMs: 24, status: "COMPLETED" },
        SOURCE_ANALYSIS: { status: "RUNNING" },
        STRUCTURAL_GRAPH: { status: graph },
      },
    });

  test("SKIPPED: o resumo com o cartão do mapa, e nenhum pedido de fatia, estrutura ou busca", async () => {
    serveStatus(fixtureStatus);
    await renderAt(SCREEN_URL, { main: t.shell.regions.analysis });

    expect(
      await within(analysisRegion()).findByRole("heading", {
        level: 1,
        name: interpolate(plural(9, t.analysis.completed.headline), {
          total: 9,
        }),
      })
    ).toBeDefined();
    expect(
      within(analysisRegion()).getByRole("heading", {
        name: t.analysis.map.title,
      })
    ).toBeDefined();
    expect(screen.queryByRole("img")).toBeNull();
    expect(
      screen.queryByRole("main", { name: t.shell.regions.canvas })
    ).toBeNull();
    expect(
      requestedMethods().filter((method) => READ_METHODS.includes(method))
    ).toStrictEqual([]);
    expect(banner().queryByText(t.header.analysis.PARTIAL)).toBeNull();
  });

  test("COMPLETED: o mapa monta, e os três métodos de leitura saem", async () => {
    await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");

    expect(requestedMethods()).toStrictEqual(
      expect.arrayContaining(["analysis.status", "graph.slice"])
    );
    expect(
      screen.queryByRole("main", { name: t.shell.regions.analysis })
    ).toBeNull();
  });

  test("CANCELLED sem o grafo: o resumo e o selo, mas nenhum cartão do mapa", async () => {
    serveStatus(() =>
      Promise.resolve(
        statusReport({
          stages: {
            REPOSITORY_VALIDATION: { durationMs: 24, status: "COMPLETED" },
            SOURCE_ANALYSIS: { durationMs: 900, status: "CANCELLED" },
          },
          status: "CANCELLED",
        })
      )
    );
    await renderAt(SCREEN_URL, { main: t.shell.regions.analysis });

    expect(
      await within(analysisRegion()).findByRole("heading", {
        level: 1,
        name: t.analysis.cancelled.headline,
      })
    ).toBeDefined();
    expect(
      within(analysisRegion()).queryByRole("heading", {
        name: t.analysis.map.title,
      })
    ).toBeNull();
    expect(banner().getByText(t.header.analysis.CANCELLED)).toBeDefined();
  });

  test("ANALYSIS_NOT_FOUND: a análise não está mais disponível, e Analisar de novo empilha a preparação com o foco no título", async () => {
    serveStatus(() =>
      Promise.reject({
        code: "ANALYSIS_NOT_FOUND",
        origin: "analyzer",
        params: {},
      })
    );
    const { router } = await renderAt(SCREEN_URL, {
      main: t.shell.regions.analysis,
    });

    expect(
      await within(analysisRegion()).findByRole("heading", {
        level: 1,
        name: t.analysis.notFound.title,
      })
    ).toBeDefined();
    expect(screen.getByText(t.analysis.notFound.body)).toBeDefined();
    await banner().findByText("multi-module");
    const lengthBefore = router.history.length;

    fireEvent.click(screen.getByRole("button", { name: t.analysis.again }));

    const title = await screen.findByRole("heading", {
      level: 1,
      name: "multi-module",
    });
    expect(urlSearch(router)).toStrictEqual({ level: "PACKAGE" });
    expect(router.history.length).toBe(lengthBefore + 1);
    expect(document.activeElement).toBe(title);
  });

  test("outra falha do status: a página do erro, com Analisar de novo", async () => {
    serveStatus(() =>
      Promise.reject({
        code: "PROTO_INVALID_RESPONSE",
        origin: "frontend",
        params: {},
      })
    );
    await renderAt(SCREEN_URL, { main: t.shell.regions.analysis });

    const { title } = formatProtocolError({
      code: "PROTO_INVALID_RESPONSE",
      origin: "frontend",
      params: {},
    });
    expect(
      await within(analysisRegion()).findByRole("heading", {
        level: 1,
        name: title,
      })
    ).toBeDefined();
    expect(
      within(analysisRegion()).getByRole("button", { name: t.analysis.again })
    ).toBeDefined();
  });

  test("com o analisador iniciando, nem status nem describe: o esqueleto espera", async () => {
    useAppStore.setState({ backend: { phase: "STARTING" } });
    await renderAt(SCREEN_URL, { main: t.shell.regions.analysis });

    expect(analysisRegion().getAttribute("aria-busy")).toBe("true");
    expect(within(analysisRegion()).queryByRole("heading")).toBeNull();
    expect(requestSpy).not.toHaveBeenCalled();
  });

  test("RUNNING: o título da execução, há quanto tempo, as etapas e Cancelar análise até o terminal", async () => {
    serveStatus(() => Promise.resolve(runningReport()));
    const cancel = vi
      .spyOn(transport, "analyzerCancel")
      .mockResolvedValue({ cancelled: false });
    await renderAt(SCREEN_URL, { main: t.shell.regions.analysis });

    expect(
      await within(analysisRegion()).findByRole("heading", {
        level: 1,
        name: "Analisando 9 arquivos Java em 3 raízes",
      })
    ).toBeDefined();
    const title = screen.getByRole("heading", { level: 1 });
    expect(title.nextElementSibling?.textContent).toMatch(RUNNING_META);
    expect(
      within(analysisRegion()).getByRole("list", {
        name: t.analysis.stages.toggle,
      }).children
    ).toHaveLength(10);
    expect(banner().getByText(t.header.analysis.RUNNING)).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: t.analysis.cancel }));

    const cancelling = await screen.findByRole("button", {
      name: t.analysis.cancelling,
    });
    expect(cancel).toHaveBeenCalledWith(AN);
    expect(cancelling.getAttribute("aria-disabled")).toBe("true");
    expect(
      requestedMethods().filter((method) => READ_METHODS.includes(method))
    ).toStrictEqual([]);
  });

  test("uma recusa do cancelamento devolve o botão, com o motivo", async () => {
    serveStatus(() => Promise.resolve(runningReport()));
    vi.spyOn(transport, "analyzerCancel").mockRejectedValue({
      code: "PROC_ANALYZER_NOT_READY",
      origin: "shell",
      params: {},
    });
    await renderAt(SCREEN_URL, { main: t.shell.regions.analysis });

    fireEvent.click(
      await screen.findByRole("button", { name: t.analysis.cancel })
    );

    expect(await within(analysisRegion()).findByRole("alert")).toBeDefined();
    expect(
      screen
        .getByRole("button", { name: t.analysis.cancel })
        .getAttribute("aria-disabled")
    ).not.toBe("true");
  });

  test("RUNNING com o grafo concluído: o mapa monta, e o andamento é o selo do cabeçalho", async () => {
    serveStatus(() => Promise.resolve(runningReport("COMPLETED")));
    await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");

    expect(banner().getByText(t.header.analysis.RUNNING)).toBeDefined();
    expect(mapRegion().firstElementChild).toBe(
      within(mapRegion()).getByRole("button", { name: t.shell.skipToOutline })
    );
  });

  test("parcial com o mapa: o selo parcial e a ressalva compacta no inspetor", async () => {
    serveStatus(async () => ({
      ...(await mapReadyStatus()),
      completeness: {
        historyTruncatedAtCommits: null,
        parseFailures: 0,
        shallowClone: false,
        skippedFiles: 2,
        totalFiles: 9,
      },
    }));
    await renderAt(SCREEN_URL);

    expect(await banner().findByText(t.header.analysis.PARTIAL)).toBeDefined();
    expect(
      within(inspector()).getByText(
        interpolate(plural(2, t.analysis.partial.files), {
          skipped: 2,
          total: 9,
        })
      )
    ).toBeDefined();
  });

  test("Fechar repositório fecha o handle e volta ao início", async () => {
    const close = vi.spyOn(transport, "closeRepository");
    const cancel = vi.spyOn(transport, "analyzerCancel");
    const { router } = await renderAt(SCREEN_URL);

    fireEvent.click(banner().getByRole("button", { name: t.header.close }));

    await screen.findByText("index");
    expect(close).toHaveBeenCalledWith(HANDLE);
    expect(router.state.location.pathname).toBe("/");
    expect(cancel).not.toHaveBeenCalled();
  });

  test("Fechar repositório no meio da execução a cancela antes de fechar o handle", async () => {
    serveStatus(() => Promise.resolve(runningReport()));
    const cancel = vi
      .spyOn(transport, "analyzerCancel")
      .mockResolvedValue({ cancelled: true });
    const close = vi.spyOn(transport, "closeRepository");
    const { router } = await renderAt(SCREEN_URL, {
      main: t.shell.regions.analysis,
    });
    await screen.findByRole("button", { name: t.analysis.cancel });

    fireEvent.click(banner().getByRole("button", { name: t.header.close }));

    await screen.findByText("index");
    expect(cancel).toHaveBeenCalledWith(AN);
    expect(close).toHaveBeenCalledWith(HANDLE);
    expect(cancel.mock.invocationCallOrder[0]).toBeLessThan(
      close.mock.invocationCallOrder[0] ?? 0
    );
    expect(router.state.location.pathname).toBe("/");
  });

  test("uma recusa do cancelamento não impede o fechamento", async () => {
    serveStatus(() => Promise.resolve(runningReport("COMPLETED")));
    const cancel = vi.spyOn(transport, "analyzerCancel").mockRejectedValue({
      code: "PROC_ANALYZER_NOT_READY",
      origin: "shell",
      params: {},
    });
    const close = vi.spyOn(transport, "closeRepository");
    const { router } = await renderAt(SCREEN_URL);
    await waitForCanvas(3, "pacotes");

    fireEvent.click(banner().getByRole("button", { name: t.header.close }));

    await screen.findByText("index");
    expect(cancel).toHaveBeenCalledWith(AN);
    expect(close).toHaveBeenCalledWith(HANDLE);
    expect(router.state.location.pathname).toBe("/");
  });

  test("do resumo, Fechar repositório não pede cancelamento nenhum", async () => {
    serveStatus(fixtureStatus);
    const cancel = vi.spyOn(transport, "analyzerCancel");
    const close = vi.spyOn(transport, "closeRepository");
    await renderAt(SCREEN_URL, { main: t.shell.regions.analysis });
    await within(analysisRegion()).findByRole("heading", {
      name: t.analysis.map.title,
    });

    fireEvent.click(banner().getByRole("button", { name: t.header.close }));

    await screen.findByText("index");
    expect(close).toHaveBeenCalledWith(HANDLE);
    expect(cancel).not.toHaveBeenCalled();
  });

  test("do Analisar ao resumo: a tela troca e o foco que caiu vai ao título novo", async () => {
    serveStatus(fixtureStatus);
    const { router } = await renderAt(`/repository/${HANDLE}`, {
      main: t.shell.regions.prepare,
    });
    const analyze = await screen.findByRole("button", {
      name: t.config.analyze,
    });
    await waitFor(() => {
      expect(analyze.getAttribute("aria-disabled")).not.toBe("true");
    });
    analyze.focus();

    fireEvent.click(analyze);

    const title = await screen.findByRole("heading", {
      level: 1,
      name: interpolate(plural(9, t.analysis.completed.headline), {
        total: 9,
      }),
    });
    expect(urlSearch(router)).toStrictEqual({
      analysis: AN,
      level: "PACKAGE",
    });
    await waitFor(() => {
      expect(document.activeElement).toBe(title);
    });
  });
});

describe("/repository/$handle: um handle malformado", () => {
  test("é a página não encontrada da rota, sem pedido nenhum, e volta ao início", async () => {
    const { router } = await renderAt("/repository/nao-e-um-handle", {
      main: t.shell.regions.repository,
    });

    const { title } = formatProtocolError({
      code: "REPO_HANDLE_UNKNOWN",
      origin: "frontend",
    });
    expect(
      screen.getByRole("heading", { level: 1, name: title })
    ).toBeDefined();
    expect(requestSpy).not.toHaveBeenCalled();

    fireEvent.click(
      screen.getByRole("link", { name: t.repository.backToStart })
    );

    await screen.findByText("index");
    expect(router.state.location.pathname).toBe("/");
  });
});
