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
import { t } from "@/i18n/messages";
import type { GraphSlice } from "@/protocol/graph";
import { createQueryClient } from "@/queries/client";
import { graphSliceQueryOptions } from "@/queries/options";
import type { RouterContext } from "@/router";
import { Route as RepositoryRoute } from "@/routes/repository.$handle";

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

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";
const ORDERS = "pkg:br.pucpr.orders";
const BILLING = "pkg:br.pucpr.billing";
const ORDER_TYPE = "type:br.pucpr.orders.Order";
const SCREEN_URL = `/repository/${HANDLE}?analysis=${AN}`;
const BIDI_OVERRIDE = String.fromCodePoint(0x20_2e);

afterEach(() => {
  vi.restoreAllMocks();
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
    expect(
      within(
        screen.getByRole("complementary", { name: t.shell.regions.sidebar })
      ).getByText(t.graph.legend.title)
    ).toBeDefined();

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
    const request = vi.spyOn(transport, "request");
    const { router } = await renderAt(
      `${SCREEN_URL}&level=TYPE_NEIGHBORHOOD&parent=${ORDER_TYPE}`
    );

    await waitForCanvas(3, "pacotes");
    expect(request.mock.calls.map(([, payload]) => payload)).toStrictEqual([
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
    const request = vi.spyOn(transport, "request");
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
    expect(request).toHaveBeenCalledTimes(2);

    router.history.back();

    await waitForCanvas(3, "pacotes");
    expect(urlSearch(router)).toStrictEqual(levelOneSearch);
    expect(levelOneSearch).toMatchObject({ analysis: AN });
    expect(request).toHaveBeenCalledTimes(2);
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
    const request = vi.spyOn(transport, "request");
    await renderAt(`${SCREEN_URL}&focus=${ORDERS}`);
    await waitForCanvas(3, "pacotes");
    await within(inspector()).findByText("orders");

    expect(request).toHaveBeenCalledTimes(1);
    expect(adapt).toHaveBeenCalledTimes(1);

    fireEvent.click(
      within(inspector()).getByRole("button", { name: t.inspector.expand })
    );
    await waitForCanvas(3, "tipos");
    fireEvent.click(
      screen.getByRole("button", { name: `select:${ORDER_TYPE}` })
    );
    await within(inspector()).findByText("Order");

    expect(request).toHaveBeenCalledTimes(2);
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
