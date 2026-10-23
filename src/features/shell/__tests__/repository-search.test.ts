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
import { describe, expect, expectTypeOf, test } from "vitest";
import type { z } from "zod";
import {
  backToLevelOne,
  changesView,
  expandPackage,
  type InspectorTab,
  isRevealable,
  leaveAnalysis,
  REPOSITORY_SEARCH_DEFAULTS,
  type RepositorySearch,
  repositorySearchSchema,
  resolveGraphView,
  revealSearchHit,
  selectElement,
  selectWhereDrawn,
  startAnalysis,
} from "@/features/shell/repository-search";
import type { GraphLevel, ResolutionQuality } from "@/protocol/enums";
import type { SearchHit } from "@/protocol/graph";
import { Route as RepositoryRoute } from "@/routes/repository.$handle";

const ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const NEXT_ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1X";
const PACKAGE_ID = "pkg:br.pucpr.orders";
const TYPE_ID = "type:br.pucpr.orders.Order";
const OTHER_PACKAGE_ID = "pkg:br.pucpr.billing";
const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";

describe("repositorySearchSchema", () => {
  test("uma URL sem nenhum param abre o nível PACKAGE, sem análise", () => {
    expect(repositorySearchSchema.parse({})).toStrictEqual({
      level: "PACKAGE",
      ...REPOSITORY_SEARCH_DEFAULTS,
    });
  });

  test("params válidos passam como vieram", () => {
    const search = {
      analysis: ANALYSIS_ID,
      focus: TYPE_ID,
      level: "TYPE",
      minQuality: "RESOLVED",
      parent: PACKAGE_ID,
      percentile: 90,
      tab: "history",
    };

    expect(repositorySearchSchema.parse(search)).toStrictEqual(search);
  });

  test("um level inválido cai em PACKAGE sem apagar o parent válido", () => {
    const parsed = repositorySearchSchema.parse({
      analysis: ANALYSIS_ID,
      level: "PACOTE",
      parent: PACKAGE_ID,
    });

    expect(parsed.level).toBe("PACKAGE");
    expect(parsed.parent).toBe(PACKAGE_ID);
    expect(parsed.analysis).toBe(ANALYSIS_ID);
  });

  test("cada campo inválido vira o seu padrão sozinho", () => {
    const parsed = repositorySearchSchema.parse({
      analysis: "an_minúsculo",
      focus: "OrderService",
      level: 3,
      minQuality: "CONFIANÇA",
      parent: "/etc/passwd",
      percentile: 100,
      tab: "historia",
    });

    expect(parsed).toStrictEqual({
      analysis: undefined,
      focus: undefined,
      level: "PACKAGE",
      parent: undefined,
      ...REPOSITORY_SEARCH_DEFAULTS,
    });
  });

  test("campos desconhecidos não sobrevivem à validação", () => {
    expect(
      repositorySearchSchema.parse({ path: "/Users/alguem/repo" })
    ).not.toHaveProperty("path");
  });

  test("um link pode omitir todos os campos, level inclusive", () => {
    expectTypeOf<z.input<typeof repositorySearchSchema>>().toEqualTypeOf<{
      analysis?: string | undefined;
      focus?: string | undefined;
      level?: GraphLevel | undefined;
      minQuality?: ResolutionQuality | undefined;
      parent?: string | undefined;
      percentile?: number | undefined;
      tab?: InspectorTab | undefined;
    }>();
    expectTypeOf<RepositorySearch["level"]>().toEqualTypeOf<GraphLevel>();
  });
});

describe("resolveGraphView", () => {
  const search = (patch: Partial<RepositorySearch>): RepositorySearch =>
    repositorySearchSchema.parse({ analysis: ANALYSIS_ID, ...patch });

  test("PACKAGE pede o nível 1 e ignora o parent", () => {
    expect(
      resolveGraphView(search({ level: "PACKAGE", parent: PACKAGE_ID }))
    ).toStrictEqual({ level: "PACKAGE" });
  });

  test("TYPE com parent pede os tipos do pacote", () => {
    expect(
      resolveGraphView(search({ level: "TYPE", parent: PACKAGE_ID }))
    ).toStrictEqual({ level: "TYPE", parentId: PACKAGE_ID });
  });

  test("TYPE_NEIGHBORHOOD com parent cai no nível 1 até haver a vista da vizinhança", () => {
    expect(
      resolveGraphView(search({ level: "TYPE_NEIGHBORHOOD", parent: TYPE_ID }))
    ).toStrictEqual({ level: "PACKAGE" });
  });

  test("abaixo de PACKAGE sem parent cai no nível 1", () => {
    expect(resolveGraphView(search({ level: "TYPE" }))).toStrictEqual({
      level: "PACKAGE",
    });
    expect(
      resolveGraphView(search({ level: "TYPE_NEIGHBORHOOD" }))
    ).toStrictEqual({ level: "PACKAGE" });
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
        `/repository/${HANDLE}?analysis=${ANALYSIS_ID}&${query}`,
      ],
    }),
    routeTree: rootRoute.addChildren([repositoryRoute]),
  });
  render(createElement(RouterProvider, { router }));
  await waitFor(() => {
    expect(router.state.status).toBe("idle");
  });
  return {
    router,
    search: router.state.matches.at(-1)?.search,
    url: Object.fromEntries(
      new URLSearchParams(router.state.location.searchStr)
    ),
  };
};

describe("a rota /repository/$handle", () => {
  test("um percentil inválido cai para 75 sem zerar o level nem o parent", async () => {
    const { search, url } = await mountRoute(
      `level=TYPE&parent=${encodeURIComponent(PACKAGE_ID)}&percentile=abc`
    );

    expect(search).toMatchObject({
      level: "TYPE",
      parent: PACKAGE_ID,
      percentile: 75,
    });
    expect(url).toStrictEqual({
      analysis: ANALYSIS_ID,
      level: "TYPE",
      parent: PACKAGE_ID,
    });
  });

  test("os três padrões novos nunca entram na URL; um valor que não é padrão fica", async () => {
    const plain = await mountRoute("level=PACKAGE");
    expect(plain.url).toStrictEqual({
      analysis: ANALYSIS_ID,
      level: "PACKAGE",
    });

    const chosen = await mountRoute(
      "percentile=90&minQuality=RESOLVED&tab=history"
    );
    expect(chosen.url).toMatchObject({
      minQuality: "RESOLVED",
      percentile: "90",
      tab: "history",
    });
  });

  test("startAnalysis deixa na URL só a análise nova e o nível", async () => {
    const { router, search } = await mountRoute(
      `level=TYPE&parent=${encodeURIComponent(PACKAGE_ID)}&tab=history`
    );

    await router.navigate({
      params: { handle: HANDLE },
      search: startAnalysis(NEXT_ANALYSIS_ID)(
        repositorySearchSchema.parse(search)
      ),
      to: "/repository/$handle",
    });

    expect(
      Object.fromEntries(new URLSearchParams(router.state.location.searchStr))
    ).toStrictEqual({ analysis: NEXT_ANALYSIS_ID, level: "PACKAGE" });
  });

  test.each([
    ["percentile=49", { percentile: 75 }],
    ["percentile=100", { percentile: 75 }],
    ["percentile=80.5", { percentile: 75 }],
    ["minQuality=BOGUS", { minQuality: "INFERRED" }],
    ["tab=historia", { tab: "overview" }],
  ])("%s cai no seu padrão sozinho", async (query, expected) => {
    const { search } = await mountRoute(query);
    expect(search).toMatchObject({ analysis: ANALYSIS_ID, ...expected });
  });
});

describe("as transições de vista", () => {
  const at = (patch: Partial<RepositorySearch>): RepositorySearch =>
    repositorySearchSchema.parse({ analysis: ANALYSIS_ID, ...patch });
  const levelOne = at({ level: "PACKAGE" });
  const ordersTypes = at({ level: "TYPE", parent: PACKAGE_ID });

  const hit = (patch: Partial<SearchHit>): SearchHit => ({
    elementId: TYPE_ID,
    kind: "TYPE",
    label: "Order",
    packageId: PACKAGE_ID,
    qualifiedName: "br.pucpr.orders.Order",
    ...patch,
  });

  test("selecionar muda só o focus, e por isso substitui a entrada", () => {
    const next = selectElement(PACKAGE_ID)(levelOne);
    expect(next).toStrictEqual({ ...levelOne, focus: PACKAGE_ID });
    expect(changesView(levelOne, next)).toBe(false);
    expect(selectElement(null)(next).focus).toBeUndefined();
  });

  test("expandir desce para os tipos, limpa a seleção e empilha", () => {
    const next = expandPackage(PACKAGE_ID)({ ...levelOne, focus: PACKAGE_ID });
    expect(next).toMatchObject({ level: "TYPE", parent: PACKAGE_ID });
    expect(next.focus).toBeUndefined();
    expect(changesView(levelOne, next)).toBe(true);
  });

  test("trocar o pacote aberto também empilha", () => {
    expect(
      changesView(ordersTypes, expandPackage(OTHER_PACKAGE_ID)(ordersTypes))
    ).toBe(true);
  });

  test("voltar ao nível 1 seleciona o pacote de onde a pessoa saiu", () => {
    const next = backToLevelOne(ordersTypes);
    expect(next).toMatchObject({ focus: PACKAGE_ID, level: "PACKAGE" });
    expect(next.parent).toBeUndefined();
    expect(changesView(ordersTypes, next)).toBe(true);
  });

  test("um pacote selecionado enquanto o mapa mostra tipos vai para o nível 1", () => {
    const next = selectWhereDrawn(OTHER_PACKAGE_ID, "PACKAGE")(ordersTypes);
    expect(next).toMatchObject({ focus: OTHER_PACKAGE_ID, level: "PACKAGE" });
    expect(next.parent).toBeUndefined();
    expect(changesView(ordersTypes, next)).toBe(true);
  });

  test("um pacote no nível 1 e um tipo no pacote aberto só mudam o focus", () => {
    expect(selectWhereDrawn(PACKAGE_ID, "PACKAGE")(levelOne)).toStrictEqual({
      ...levelOne,
      focus: PACKAGE_ID,
    });
    expect(selectWhereDrawn(TYPE_ID, "TYPE")(ordersTypes)).toStrictEqual({
      ...ordersTypes,
      focus: TYPE_ID,
    });
  });

  test("um tipo achado na busca abre o pacote dele selecionado", () => {
    const next = revealSearchHit(hit({}))(levelOne);
    expect(next).toMatchObject({
      focus: TYPE_ID,
      level: "TYPE",
      parent: PACKAGE_ID,
    });
    expect(changesView(levelOne, next)).toBe(true);
    expect(
      changesView(ordersTypes, revealSearchHit(hit({}))(ordersTypes))
    ).toBe(false);
  });

  test("um pacote achado na busca é selecionado no nível 1", () => {
    const next = revealSearchHit(
      hit({ elementId: PACKAGE_ID, kind: "PACKAGE", packageId: null })
    )(ordersTypes);
    expect(next).toMatchObject({ focus: PACKAGE_ID, level: "PACKAGE" });
    expect(next.parent).toBeUndefined();
  });

  test("um resultado que o mapa não mostra deixa a vista como está", () => {
    const orphan = hit({ packageId: null });
    const method = hit({
      elementId: "method:br.pucpr.orders.Order#total()",
      kind: "METHOD",
    });

    expect(revealSearchHit(orphan)(ordersTypes)).toBe(ordersTypes);
    expect(revealSearchHit(method)(ordersTypes)).toBe(ordersTypes);
    expect(isRevealable(orphan)).toBe(false);
    expect(isRevealable(method)).toBe(false);
    expect(isRevealable(hit({}))).toBe(true);
  });
});

describe("entrar e sair de uma análise", () => {
  const preparing = repositorySearchSchema.parse({});
  const deepInAnalysis = repositorySearchSchema.parse({
    analysis: ANALYSIS_ID,
    focus: TYPE_ID,
    level: "TYPE",
    minQuality: "RESOLVED",
    parent: PACKAGE_ID,
    percentile: 90,
    tab: "history",
  });

  test("startAnalysis põe a análise e devolve todo o resto ao padrão", () => {
    expect(startAnalysis(NEXT_ANALYSIS_ID)(deepInAnalysis)).toStrictEqual({
      ...REPOSITORY_SEARCH_DEFAULTS,
      analysis: NEXT_ANALYSIS_ID,
      level: "PACKAGE",
    });
  });

  test("startAnalysis a partir da preparação empilha: o voltar leva de volta a ela", () => {
    const next = startAnalysis(ANALYSIS_ID)(preparing);

    expect(next.analysis).toBe(ANALYSIS_ID);
    expect(changesView(preparing, next)).toBe(true);
  });

  test("o resultado de startAnalysis passa pelo schema como saiu", () => {
    const next = startAnalysis(ANALYSIS_ID)(preparing);

    expect(repositorySearchSchema.parse(next)).toStrictEqual(next);
  });

  test("leaveAnalysis volta à preparação, sem nada do mapa que ficou para trás", () => {
    const next = leaveAnalysis(deepInAnalysis);

    expect(next).toStrictEqual(preparing);
    expect(next).not.toHaveProperty("analysis");
    expect(changesView(deepInAnalysis, next)).toBe(true);
  });

  test("trocar só a análise empilha, mesmo com o mesmo mapa à vista", () => {
    const levelOne = repositorySearchSchema.parse({ analysis: ANALYSIS_ID });
    const other = { ...levelOne, analysis: NEXT_ANALYSIS_ID };

    expect(resolveGraphView(other)).toStrictEqual(resolveGraphView(levelOne));
    expect(changesView(levelOne, other)).toBe(true);
  });

  test("uma seleção dentro da mesma análise continua substituindo", () => {
    const levelOne = repositorySearchSchema.parse({ analysis: ANALYSIS_ID });

    expect(changesView(levelOne, selectElement(PACKAGE_ID)(levelOne))).toBe(
      false
    );
  });
});
