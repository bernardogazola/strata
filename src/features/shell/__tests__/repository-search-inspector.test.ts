import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { describe, expect, test } from "vitest";
import {
  changesView,
  REPOSITORY_SEARCH_DEFAULTS,
  type RepositorySearch,
  type RevealTarget,
  repositorySearchSchema,
  revealElement,
  selectDirection,
  selectTab,
} from "@/features/shell/repository-search";
import type { DependencyEntry } from "@/protocol/graph";
import { Route as RepositoryRoute } from "@/routes/repository.$handle";
import {
  DESCRIBED_IDS,
  dependencyPageOf,
  detailOf,
} from "@/test/fixture-graph";
import { WORLD_ANALYSIS_ID } from "@/test/inspector";

const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";

const typeIdOf = (label: string): string =>
  DESCRIBED_IDS.find(
    (id) => id.startsWith("type:") && detailOf(id).label === label
  ) ?? "";
const CATALOG_ID = typeIdOf("Catalog");
const CATALOG_PACKAGE = detailOf(CATALOG_ID).packageId ?? "";
const rowOf = (
  elementId: string,
  predicate: (entry: DependencyEntry) => boolean
): DependencyEntry => {
  const entry = dependencyPageOf(elementId, "OUT").items.find(predicate);
  if (entry === undefined) {
    throw new Error(`nenhuma linha OUT de ${elementId} atende ao critério`);
  }
  return entry;
};
const STORE_ROW = rowOf(CATALOG_ID, (entry) => entry.label === "Store");
const AUDITED_ROW = rowOf(CATALOG_ID, (entry) => entry.label === "Audited");
const STRING_ROW = rowOf(typeIdOf("CatalogEntry"), (entry) => entry.isExternal);
const PACKAGE_ROW = rowOf(CATALOG_PACKAGE, (entry) => entry.kind === "PACKAGE");

const targetOf = ({
  elementId,
  kind,
  packageId,
}: DependencyEntry): RevealTarget => ({ elementId, kind, packageId });

const at = (patch: Partial<RepositorySearch>): RepositorySearch =>
  repositorySearchSchema.parse({ analysis: WORLD_ANALYSIS_ID, ...patch });

const catalogInspected = at({
  direction: "IN",
  focus: CATALOG_ID,
  level: "TYPE",
  parent: CATALOG_PACKAGE,
  tab: "dependencies",
});

describe("selectTab e selectDirection", () => {
  test("trocar a aba substitui a entrada e mantém a seleção", () => {
    const next = selectTab("dependencies")(at({ focus: CATALOG_ID }));

    expect(next).toStrictEqual({
      ...at({ focus: CATALOG_ID }),
      tab: "dependencies",
    });
    expect(changesView(at({ focus: CATALOG_ID }), next)).toBe(false);
  });

  test("trocar a direção substitui a entrada e mantém a aba e a seleção", () => {
    const prev = at({ focus: CATALOG_ID, tab: "dependencies" });

    const next = selectDirection("IN")(prev);

    expect(next).toStrictEqual({ ...prev, direction: "IN" });
    expect(changesView(prev, next)).toBe(false);
  });
});

describe("revealElement sobre as linhas da tabela", () => {
  test("a fixture tem os papéis que o teste afirma", () => {
    expect(STORE_ROW.packageId).not.toBe(CATALOG_PACKAGE);
    expect(AUDITED_ROW.packageId).toBe(CATALOG_PACKAGE);
    expect(STRING_ROW.packageId).toBeNull();
    expect(PACKAGE_ROW.packageId).toBeNull();
  });

  test("um tipo de outro pacote abre o pacote dele selecionado, e empilha", () => {
    const next = revealElement(targetOf(STORE_ROW))(catalogInspected);

    expect(next).toStrictEqual({
      ...catalogInspected,
      focus: STORE_ROW.elementId,
      level: "TYPE",
      parent: STORE_ROW.packageId,
    });
    expect(changesView(catalogInspected, next)).toBe(true);
  });

  test("um tipo do mesmo pacote só troca a seleção, e substitui", () => {
    const next = revealElement(targetOf(AUDITED_ROW))(catalogInspected);

    expect(next).toStrictEqual({
      ...catalogInspected,
      focus: AUDITED_ROW.elementId,
    });
    expect(changesView(catalogInspected, next)).toBe(false);
  });

  test("a aba e a direção acompanham a pessoa até o elemento revelado", () => {
    const next = revealElement(targetOf(STORE_ROW))(catalogInspected);

    expect(next.tab).toBe(catalogInspected.tab);
    expect(next.direction).toBe(catalogInspected.direction);
  });

  test("um pacote é selecionado no nível 1, e empilha a partir do nível TYPE", () => {
    const next = revealElement(targetOf(PACKAGE_ROW))(catalogInspected);

    expect(next).toMatchObject({
      focus: PACKAGE_ROW.elementId,
      level: "PACKAGE",
    });
    expect(next.parent).toBeUndefined();
    expect(changesView(catalogInspected, next)).toBe(true);
  });

  test("uma ponta externa deixa a vista como está, o mesmo objeto", () => {
    expect(revealElement(targetOf(STRING_ROW))(catalogInspected)).toBe(
      catalogInspected
    );
  });
});

describe("o param direction", () => {
  test("o padrão é OUT, e o padrão é o de REPOSITORY_SEARCH_DEFAULTS", () => {
    expect(REPOSITORY_SEARCH_DEFAULTS.direction).toBe("OUT");
    expect(repositorySearchSchema.parse({}).direction).toBe("OUT");
  });

  test("IN passa como veio; lixo cai em OUT sem derrubar os outros params", () => {
    expect(repositorySearchSchema.parse({ direction: "IN" }).direction).toBe(
      "IN"
    );
    expect(
      repositorySearchSchema.parse({
        direction: "SIDEWAYS",
        focus: CATALOG_ID,
        tab: "dependencies",
      })
    ).toMatchObject({
      direction: "OUT",
      focus: CATALOG_ID,
      tab: "dependencies",
    });
    expect(repositorySearchSchema.parse({ direction: 7 }).direction).toBe(
      "OUT"
    );
  });
});

const mountRoute = async (query: string) => {
  const rootRoute = createRootRoute({ component: Outlet });
  const repositoryRoute = createRoute({
    component: () => null,
    getParentRoute: () => rootRoute,
    path: "/repository/$handle",
    search: RepositoryRoute.options.search,
    validateSearch: RepositoryRoute.options.validateSearch,
  });
  const router = createRouter({
    history: createMemoryHistory({
      initialEntries: [
        `/repository/${HANDLE}?analysis=${WORLD_ANALYSIS_ID}&${query}`,
      ],
    }),
    routeTree: rootRoute.addChildren([repositoryRoute]),
  });
  render(createElement(RouterProvider, { router }));
  await waitFor(() => {
    expect(router.state.status).toBe("idle");
  });
  return {
    search: router.state.matches.at(-1)?.search,
    url: Object.fromEntries(
      new URLSearchParams(router.state.location.searchStr)
    ),
  };
};

describe("a direção na URL", () => {
  test("OUT, o padrão, sai da barra de endereço; IN fica", async () => {
    const plain = await mountRoute("level=PACKAGE&direction=OUT");
    expect(plain.search).toMatchObject({ direction: "OUT" });
    expect(plain.url).toStrictEqual({
      analysis: WORLD_ANALYSIS_ID,
      level: "PACKAGE",
    });

    const chosen = await mountRoute(
      "level=PACKAGE&direction=IN&tab=dependencies"
    );
    expect(chosen.search).toMatchObject({
      direction: "IN",
      tab: "dependencies",
    });
    expect(chosen.url).toMatchObject({ direction: "IN", tab: "dependencies" });
  });

  test("uma direção inválida cai em OUT e também sai da URL", async () => {
    const { search, url } = await mountRoute(
      "level=PACKAGE&direction=SIDEWAYS"
    );

    expect(search).toMatchObject({ direction: "OUT" });
    expect(url).not.toHaveProperty("direction");
  });
});
