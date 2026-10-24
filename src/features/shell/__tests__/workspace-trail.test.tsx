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
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { FakeBridgeError, fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import { ThemeProvider } from "@/components/theme-provider";
import {
  AGGREGATED_SLICE,
  ALPHA_MEMBER,
} from "@/features/graph/__tests__/aggregated-slice-data";
import type { ArchitectureGraphProps } from "@/features/graph/architecture-graph";
import {
  COMPLETED_STAGES,
  STARTED_AT,
  statusReport,
} from "@/features/progress/__tests__/status-report";
import { formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";
import type { GraphSlice, OutlineNode, OutlinePage } from "@/protocol/graph";
import { createQueryClient } from "@/queries/client";
import type { RouterContext } from "@/router";
import { Route as RepositoryRoute } from "@/routes/repository.$handle";
import { type BackendState, useAppStore } from "@/state/app-store";
import {
  DRILLED_PACKAGE_ID,
  labelOf,
  PACKAGE_IDS,
  typeSliceOf,
  UNKNOWN_PARENT,
} from "@/test/fixture-graph";

vi.mock("@/features/graph/architecture-graph", async (importOriginal) => {
  const actual =
    await importOriginal<
      typeof import("@/features/graph/architecture-graph")
    >();
  const FakeArchitectureGraph = ({
    elements,
    label,
  }: ArchitectureGraphProps) => (
    <div
      aria-label={label}
      data-node-count={elements?.nodes.length ?? "none"}
      role="img"
    />
  );
  return { ...actual, ArchitectureGraph: FakeArchitectureGraph };
});

vi.mock("@/features/outline/node-search", () => ({
  NodeSearch: () => null,
}));

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";
const SCREEN_URL = `/repository/${HANDLE}?analysis=${AN}`;

const VIEWPORT = { height: 400, width: 320 } as const;

const READY: BackendState = { phase: "READY", protocolVersion: 1 };
const initialStore = useAppStore.getState();

const MAP_READY = statusReport({
  completeness: { totalFiles: 9 },
  stages: {
    ...COMPLETED_STAGES,
    STRUCTURAL_GRAPH: {
      durationMs: 3,
      startedAt: STARTED_AT,
      status: "COMPLETED",
    },
  },
  status: "COMPLETED",
});

const asksRefusedParent = (method: string, payload: unknown): boolean => {
  const { parentId } = payload as { readonly parentId?: unknown };
  return (
    (method === "graph.slice" || method === "outline.children") &&
    parentId === UNKNOWN_PARENT.parentId
  );
};

beforeEach(() => {
  useAppStore.setState({ ...initialStore, backend: READY }, true);
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(
    VIEWPORT.height
  );
  vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(
    VIEWPORT.width
  );
  vi.spyOn(transport, "request").mockImplementation((method, payload) => {
    if (method === "analysis.status") {
      return Promise.resolve(MAP_READY);
    }
    return asksRefusedParent(method, payload)
      ? Promise.reject(new FakeBridgeError(UNKNOWN_PARENT.error))
      : fakeRequest(method, payload);
  });
});

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
  notFoundComponent: RepositoryRoute.options.notFoundComponent,
  params: RepositoryRoute.options.params,
  path: "/repository/$handle",
  search: RepositoryRoute.options.search,
  validateSearch: RepositoryRoute.options.validateSearch,
});
const routeTree = rootRoute.addChildren([indexRoute, repositoryRoute]);

const renderAt = async (url: string) => {
  const queryClient = createQueryClient();
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
  return { router };
};

const urlSearch = (
  router: Awaited<ReturnType<typeof renderAt>>["router"]
): Record<string, string> =>
  Object.fromEntries(new URLSearchParams(router.state.location.searchStr));

const trail = () =>
  within(screen.getByRole("navigation", { name: t.shell.breadcrumb.label }));

const mapRegion = () =>
  within(screen.getByRole("main", { name: t.shell.regions.canvas }));

const inspector = () =>
  within(
    screen.getByRole("complementary", { name: t.shell.regions.inspector })
  );

const outline = () =>
  within(screen.getByRole("tree", { name: t.outline.title }));

const currentCrumb = (): HTMLElement => {
  const current = screen
    .getByRole("navigation", { name: t.shell.breadcrumb.label })
    .querySelector<HTMLElement>('[aria-current="page"]');
  if (current === null) {
    throw new Error("a trilha não tem passo atual");
  }
  return current;
};

describe("o parent velho de uma URL colada", () => {
  const STALE_URL = `${SCREEN_URL}&level=TYPE&parent=${UNKNOWN_PARENT.parentId}`;
  const REFUSAL = formatProtocolError(UNKNOWN_PARENT.error);

  test("o painel do mapa diz o título e a dica do registro, sem Tentar de novo", async () => {
    await renderAt(STALE_URL);

    const alert = await mapRegion().findByRole("alert");

    expect(within(alert).getByText(REFUSAL.title)).toBeDefined();
    expect(REFUSAL.hint).toBeDefined();
    expect(within(alert).getByText(REFUSAL.hint ?? "")).toBeDefined();
    expect(
      mapRegion().queryByRole("button", { name: t.graph.retry })
    ).toBeNull();
  });

  test("a dica nomeia a raiz da trilha, e o link dela volta ao nível 1, que desenha os pacotes", async () => {
    const { router } = await renderAt(STALE_URL);
    await mapRegion().findByRole("alert");
    const back = trail().getByRole("link", { name: t.shell.breadcrumb.root });
    expect(REFUSAL.hint).toContain(t.shell.breadcrumb.root);

    fireEvent.click(back);

    await waitFor(() =>
      expect(urlSearch(router)).toStrictEqual({
        analysis: AN,
        focus: UNKNOWN_PARENT.parentId,
        level: "PACKAGE",
      })
    );
    await waitFor(() =>
      expect(screen.getByRole("img").getAttribute("data-node-count")).toBe(
        String(PACKAGE_IDS.length)
      )
    );
    expect(mapRegion().queryByRole("alert")).toBeNull();
  });

  test("a estrutura diz a mesma recusa na primeira linha, e o pacote velho não tem nome na trilha", async () => {
    await renderAt(STALE_URL);

    const body = REFUSAL.body
      .map((segment) => (segment.kind === "chrome" ? segment.text : ""))
      .join("");
    expect(body).not.toBe("");
    await waitFor(() =>
      expect(outline().getAllByRole("treeitem")[0]?.textContent).toBe(body)
    );
    expect(currentCrumb().textContent).toBe(t.shell.breadcrumb.selectedPackage);
  });
});

const MEMBER_ROW: OutlineNode = {
  childCount: 0,
  id: ALPHA_MEMBER,
  kind: "PACKAGE",
  label: "alpha.core",
};
const AGGREGATED_ROOT: OutlinePage = { items: [MEMBER_ROW], nextCursor: null };

const MEMBER_TYPES: GraphSlice = {
  ...typeSliceOf(DRILLED_PACKAGE_ID),
  edges: [],
  nodes: [],
  parentId: ALPHA_MEMBER,
  totals: { edges: 0, nodes: 0 },
  truncated: false,
};

const serveAggregatedWorld = (): void => {
  vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
    {
      method: "graph.slice",
      request: { level: "PACKAGE" },
      result: AGGREGATED_SLICE,
    },
    {
      method: "graph.slice",
      request: { level: "TYPE", parentId: ALPHA_MEMBER },
      result: MEMBER_TYPES,
    },
    {
      method: "outline.children",
      request: { parentId: null },
      result: AGGREGATED_ROOT,
    },
  ] satisfies readonly FakeTranscriptOverride[]);
};

describe("o nome do pacote aberto em um nível 1 agrupado", () => {
  test("aberto pela estrutura e pelo inspetor, o pacote de dentro de um grupo leva o rótulo da raiz da estrutura", async () => {
    serveAggregatedWorld();
    const { router } = await renderAt(SCREEN_URL);
    expect(AGGREGATED_SLICE.nodes.map((node) => node.id)).not.toContain(
      ALPHA_MEMBER
    );

    await screen.findByRole("tree", { name: t.outline.title });
    fireEvent.click(await outline().findByText(MEMBER_ROW.label));
    await waitFor(() => expect(urlSearch(router).focus).toBe(ALPHA_MEMBER));
    fireEvent.click(
      await inspector().findByRole("button", { name: t.inspector.expand })
    );

    await waitFor(() =>
      expect(urlSearch(router)).toMatchObject({
        level: "TYPE",
        parent: ALPHA_MEMBER,
      })
    );
    await waitFor(() =>
      expect(currentCrumb().textContent).toBe(MEMBER_ROW.label)
    );
    expect(currentCrumb().title).toBe(MEMBER_ROW.label);
  });

  test("em um link direto para o nível TYPE, o nome entra na trilha assim que a raiz da estrutura chega", async () => {
    await renderAt(`${SCREEN_URL}&level=TYPE&parent=${DRILLED_PACKAGE_ID}`);

    await waitFor(() =>
      expect(currentCrumb().textContent).toBe(labelOf(DRILLED_PACKAGE_ID))
    );
    expect(
      vi
        .mocked(transport.request)
        .mock.calls.some(
          ([method, payload]) =>
            method === "graph.slice" &&
            (payload as { level?: unknown }).level === "PACKAGE"
        )
    ).toBe(false);
  });
});

describe("o inspetor vazio no nível TYPE", () => {
  test("sem seleção, a primeira frase do vazio fica, e a dica do duplo clique em um pacote sai", async () => {
    await renderAt(`${SCREEN_URL}&level=TYPE&parent=${DRILLED_PACKAGE_ID}`);

    expect(await inspector().findByText(t.inspector.empty)).toBeDefined();
    expect(inspector().queryByText(t.graph.legend.expandHint)).toBeNull();
  });

  test("no nível 1 a dica continua, logo depois da primeira frase", async () => {
    await renderAt(SCREEN_URL);

    const empty = await inspector().findByText(t.inspector.empty);
    expect(empty.nextElementSibling?.textContent).toBe(
      t.graph.legend.expandHint
    );
  });
});

describe("a trilha nunca quebra linha", () => {
  test("a raiz não encolhe, o pacote aberto corta com reticências e guarda o nome inteiro no title", async () => {
    await renderAt(`${SCREEN_URL}&level=TYPE&parent=${DRILLED_PACKAGE_ID}`);
    const label = labelOf(DRILLED_PACKAGE_ID);
    await waitFor(() => expect(currentCrumb().textContent).toBe(label));

    const list = screen
      .getByRole("navigation", { name: t.shell.breadcrumb.label })
      .querySelector("ol");
    const [rootStep] = trail().getAllByRole("listitem");
    const classesOf = (element: Element | null | undefined): string[] =>
      element?.className.split(" ") ?? [];

    expect(classesOf(list)).toContain("whitespace-nowrap");
    expect(classesOf(rootStep)).toContain("shrink-0");
    expect(classesOf(currentCrumb())).toEqual(
      expect.arrayContaining(["min-w-0", "truncate"])
    );
    expect(currentCrumb().title).toBe(label);
  });
});
