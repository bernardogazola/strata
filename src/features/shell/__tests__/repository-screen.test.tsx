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
import { afterEach, describe, expect, test, vi } from "vitest";
import { fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import { ThemeProvider } from "@/components/theme-provider";
import { toGraphView } from "@/features/graph/adapter";
import type { ArchitectureGraphProps } from "@/features/graph/architecture-graph";
import type { ArchitectureOutlineProps } from "@/features/outline/architecture-outline";
import type { NodeSearchProps } from "@/features/outline/node-search";
import { t } from "@/i18n/messages";
import type { GraphSlice, SearchHit } from "@/protocol/graph";
import { createQueryClient } from "@/queries/client";
import { graphSliceQueryOptions } from "@/queries/options";
import type { RouterContext } from "@/router";
import { Route as RepositoryRoute } from "@/routes/repository.$handle";

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
  path: "/repository/$handle",
  search: RepositoryRoute.options.search,
  validateSearch: RepositoryRoute.options.validateSearch,
});
const routeTree = rootRoute.addChildren([indexRoute, repositoryRoute]);

const renderAt = async (
  url: string,
  seed?: (queryClient: ReturnType<typeof createQueryClient>) => void
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
  await screen.findByRole("main", { name: t.shell.regions.canvas });
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

const spyRequests = () => vi.spyOn(transport, "request");
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
    expect(
      screen.getByRole("heading", { level: 1, name: t.app.name })
    ).toBeDefined();
    expect(
      screen.getByRole("complementary", { name: t.shell.regions.sidebar })
    ).toBeDefined();
    expect(inspector()).toBeDefined();
    const header = within(screen.getByRole("banner"));
    expect(header.getByText(t.backend.STARTING)).toBeDefined();
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

  test("sem o param analysis, o estado de nenhuma análise com um link para o início", async () => {
    const request = vi.spyOn(transport, "request");
    const { router } = await renderAt(`/repository/${HANDLE}`);

    expect(
      screen.getByRole("heading", { name: t.graph.noAnalysis.title })
    ).toBeDefined();
    expect(screen.getByText(t.graph.noAnalysis.body)).toBeDefined();
    expect(screen.queryByRole("img")).toBeNull();
    expect(within(inspector()).getByText(t.inspector.empty)).toBeDefined();
    expect(request).not.toHaveBeenCalled();

    fireEvent.click(
      screen.getByRole("link", { name: t.graph.noAnalysis.action })
    );
    await screen.findByText("index");
    expect(router.state.location.pathname).toBe("/");
  });

  test("um analysis inválido conta como ausente", async () => {
    await renderAt(`/repository/${HANDLE}?analysis=an_errado`);

    expect(
      screen.getByRole("heading", { name: t.graph.noAnalysis.title })
    ).toBeDefined();
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

  test("sem análise, a trilha não lê cache nenhum e usa o texto genérico", async () => {
    await renderAt(`/repository/${HANDLE}?level=TYPE&parent=${ORDERS}`);

    expect(
      screen.getByRole("heading", { name: t.graph.noAnalysis.title })
    ).toBeDefined();
    expect(
      within(breadcrumb()).getByText(t.shell.breadcrumb.selectedPackage)
    ).toBeDefined();
  });

  test("um rótulo hostil chega saneado", async () => {
    await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}`,
      seedLevelOne(`ord${BIDI_OVERRIDE}ers`)
    );

    expect(within(breadcrumb()).getByText("orders").tagName).toBe("BDI");
  });

  test("o pacote padrão, de rótulo vazio, recebe o nome que o canvas lhe dá", async () => {
    await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}`,
      seedLevelOne("")
    );

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

  test("sem análise, nem busca, nem estrutura, nem botão de pular, e pedido nenhum", async () => {
    const request = spyRequests();
    await renderAt(`/repository/${HANDLE}?level=TYPE&parent=${ORDERS}`);

    expect(sidebarChildren()).toStrictEqual([]);
    expect(sidebar().childElementCount).toBe(0);
    expect(
      screen.queryByRole("button", { name: t.shell.skipToOutline })
    ).toBeNull();
    expect(request).not.toHaveBeenCalled();
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
      await within(inspector()).findByText(t.inspector.notOnMap)
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
    expect(within(inspector()).queryByText(t.inspector.notOnMap)).toBeNull();
  });

  test("em uma fatia completa, um focus ausente continua sendo seleção nenhuma", async () => {
    await renderAt(
      `${SCREEN_URL}&level=TYPE&parent=${ORDERS}&focus=${CUT_TYPE}`
    );
    await waitForCanvas(3, "tipos");

    expect(within(inspector()).getByText(t.inspector.empty)).toBeDefined();
    expect(within(inspector()).queryByText(t.inspector.notOnMap)).toBeNull();
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
