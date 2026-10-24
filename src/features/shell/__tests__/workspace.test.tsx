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
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import { ThemeProvider } from "@/components/theme-provider";
import {
  AGGREGATED_SLICE,
  ALPHA,
  ALPHA_MEMBER,
} from "@/features/graph/__tests__/aggregated-slice-data";
import type { ArchitectureGraphProps } from "@/features/graph/architecture-graph";
import type { ArchitectureOutlineProps } from "@/features/outline/architecture-outline";
import {
  COMPLETED_STAGES,
  STARTED_AT,
  statusReport,
} from "@/features/progress/__tests__/status-report";
import { t } from "@/i18n/messages";
import { createQueryClient } from "@/queries/client";
import type { RouterContext } from "@/router";
import { Route as RepositoryRoute } from "@/routes/repository.$handle";
import { type BackendState, useAppStore } from "@/state/app-store";

const OUTLINE_PACKAGE = vi.hoisted(() => "pkg:org.acme.alpha.core");

vi.mock("@/features/graph/architecture-graph", async (importOriginal) => {
  const actual =
    await importOriginal<
      typeof import("@/features/graph/architecture-graph")
    >();
  let current: ArchitectureGraphProps | null = null;
  const onClick = (event: MouseEvent<HTMLButtonElement>): void => {
    current?.onSelect(event.currentTarget.dataset.id ?? null);
  };
  const FakeArchitectureGraph = (props: ArchitectureGraphProps) => {
    current = props;
    const { elements, label, selectedElementId } = props;
    const ids = elements?.nodes.map((node) => node.data.id ?? "") ?? [];
    return (
      <>
        <div
          aria-label={label}
          data-node-count={elements?.nodes.length ?? "none"}
          data-selected={selectedElementId ?? ""}
          role="img"
        />
        {ids.map((id) => (
          <button data-id={id} key={id} onClick={onClick} type="button">
            {`select:${id}`}
          </button>
        ))}
      </>
    );
  };
  return { ...actual, ArchitectureGraph: FakeArchitectureGraph };
});

vi.mock("@/features/outline/architecture-outline", () => {
  let current: ArchitectureOutlineProps | null = null;
  const onClick = (): void => {
    current?.onSelect(OUTLINE_PACKAGE, "PACKAGE");
  };
  const FakeArchitectureOutline = (props: ArchitectureOutlineProps) => {
    current = props;
    return (
      <section data-testid="outline">
        <button onClick={onClick} type="button">
          {`outline:select:${OUTLINE_PACKAGE}`}
        </button>
      </section>
    );
  };
  return { ArchitectureOutline: FakeArchitectureOutline };
});

vi.mock("@/features/outline/node-search", () => ({
  NodeSearch: () => null,
}));

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const CLASS_SEPARATOR = /\s+/;
const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";
const SCREEN_URL = `/repository/${HANDLE}?analysis=${AN}`;

const NOT_ON_MAP_AGGREGATED =
  "Este pacote não aparece sozinho no mapa: o primeiro nível agrupa os pacotes por prefixo. A estrutura da arquitetura e a busca alcançam cada pacote.";

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

beforeEach(() => {
  useAppStore.setState({ ...initialStore, backend: READY }, true);
  vi.spyOn(transport, "request").mockImplementation((method, payload) =>
    method === "analysis.status"
      ? Promise.resolve(MAP_READY)
      : fakeRequest(method, payload)
  );
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

const canvas = () => screen.getByRole("img");

const waitForNodes = async (count: number) => {
  await waitFor(() =>
    expect(canvas().getAttribute("data-node-count")).toBe(String(count))
  );
};

const banner = () => within(screen.getByRole("banner"));

const inspector = () =>
  within(
    screen.getByRole("complementary", { name: t.shell.regions.inspector })
  );

const serveAggregatedLevelOne = (): void => {
  vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
    {
      method: "graph.slice",
      request: { level: "PACKAGE" },
      result: AGGREGATED_SLICE,
    },
  ] satisfies readonly FakeTranscriptOverride[]);
};

describe("o espaço de trabalho: Analisar de novo no cabeçalho", () => {
  test('"Analisar de novo" fica ao lado de "Fechar repositório", com a mesma cara: ghost, pequeno, sem quebrar', async () => {
    await renderAt(SCREEN_URL);

    const again = banner().getByRole("button", { name: t.analysis.again });
    const close = banner().getByRole("button", { name: t.header.close });
    const adjacent =
      again.nextElementSibling === close || close.nextElementSibling === again;
    expect(adjacent).toBe(true);
    expect(again.className).toContain("whitespace-nowrap");
    expect(new Set(again.className.split(CLASS_SEPARATOR))).toStrictEqual(
      new Set(close.className.split(CLASS_SEPARATOR))
    );
  });

  test("clicar faz a transição do resumo, leaveAnalysis: empilha, e a URL volta à preparação sem nada do mapa", async () => {
    const { router } = await renderAt(
      `${SCREEN_URL}&focus=pkg:br.pucpr.legacy.api`
    );
    await waitForNodes(4);
    const lengthBefore = router.history.length;

    fireEvent.click(banner().getByRole("button", { name: t.analysis.again }));

    await screen.findByRole("main", { name: t.shell.regions.prepare });
    expect(urlSearch(router)).toStrictEqual({ level: "PACKAGE" });
    expect(router.history.length).toBe(lengthBefore + 1);
  });
});

describe("o espaço de trabalho: um nível 1 agrupado", () => {
  test("selecionar um grupo no canvas deixa o id pfx: dele no focus da URL", async () => {
    serveAggregatedLevelOne();
    const { router } = await renderAt(SCREEN_URL);
    await waitForNodes(3);

    fireEvent.click(screen.getByRole("button", { name: `select:${ALPHA}` }));

    await waitFor(() => expect(urlSearch(router).focus).toBe(ALPHA));
    expect(canvas().getAttribute("data-selected")).toBe(ALPHA);
  });

  test("com um grupo no focus, o inspetor mostra o grupo, com os vizinhos em pacotes", async () => {
    serveAggregatedLevelOne();
    await renderAt(`${SCREEN_URL}&focus=${ALPHA}`);
    await waitForNodes(3);

    expect(
      await inspector().findByRole("heading", { level: 2, name: "alpha" })
    ).toBeDefined();
    const rows = [
      ...screen
        .getByRole("complementary", { name: t.shell.regions.inspector })
        .querySelectorAll("dl > div"),
    ].map((row) => [
      row.querySelector("dt")?.textContent,
      row.querySelector("dd")?.textContent,
    ]);
    expect(rows).toContainEqual([t.inspector.dependsOn, "7 pacotes"]);
    expect(rows).toContainEqual([t.inspector.usedBy, "3 pacotes"]);
    expect(
      inspector().queryByRole("button", { name: t.inspector.expand })
    ).toBeNull();
  });

  test("um pacote escolhido na estrutura, dentro de um grupo: o inspetor diz que o primeiro nível o agrupou", async () => {
    serveAggregatedLevelOne();
    const { router } = await renderAt(SCREEN_URL);
    await waitForNodes(3);

    fireEvent.click(
      screen.getByRole("button", {
        name: `outline:select:${OUTLINE_PACKAGE}`,
      })
    );

    await waitFor(() => expect(urlSearch(router).focus).toBe(ALPHA_MEMBER));
    expect(await inspector().findByText(NOT_ON_MAP_AGGREGATED)).toBeDefined();
  });

  test("o mesmo pacote oferece Ver os tipos deste pacote, que abre o nível TYPE dele", async () => {
    serveAggregatedLevelOne();
    const { router } = await renderAt(SCREEN_URL);
    await waitForNodes(3);
    fireEvent.click(
      screen.getByRole("button", {
        name: `outline:select:${OUTLINE_PACKAGE}`,
      })
    );
    await waitFor(() => expect(urlSearch(router).focus).toBe(ALPHA_MEMBER));

    fireEvent.click(
      await inspector().findByRole("button", { name: t.inspector.expand })
    );

    await waitFor(() =>
      expect(urlSearch(router)).toStrictEqual({
        analysis: AN,
        level: "TYPE",
        parent: ALPHA_MEMBER,
      })
    );
  });
});
