import {
  MutationObserver,
  QueryClientProvider,
  useInfiniteQuery,
  useQuery,
} from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import {
  GraphSliceViolationError,
  toGraphElements,
} from "@/features/graph/adapter";
import { GRAPH_PERF } from "@/features/graph/perf";
import type { GraphSlice } from "@/protocol/graph";
import type { AnalysisConfiguration } from "@/protocol/repository";
import { createQueryClient } from "@/queries/client";
import { queryKeys } from "@/queries/keys";
import {
  analysisStartMutationOptions,
  analysisStatusQueryOptions,
  analyzerQueryOptions,
  type GraphViewParams,
  graphSearchQueryOptions,
  graphSliceQueryOptions,
  OUTLINE_PAGE_LIMIT,
  outlineChildrenQueryOptions,
  repositoryDescribeQueryOptions,
  repositoryQueryOptions,
  SEARCH_KINDS,
} from "@/queries/options";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const ORDERS = "pkg:br.pucpr.orders";
const HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

const fixtureSlice = async (view: GraphViewParams): Promise<GraphSlice> =>
  (await fakeRequest("graph.slice", {
    analysisId: AN,
    ...view,
  })) as GraphSlice;

const renderSliceQuery = (view: GraphViewParams) => {
  const queryClient = createQueryClient();
  const wrapper = ({ children }: { readonly children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  const hook = renderHook(() => useQuery(graphSliceQueryOptions(AN, view)), {
    wrapper,
  });
  return { ...hook, queryClient };
};

describe("graphSliceQueryOptions: a chave", () => {
  test("fica sob structure.all, que o evento STRUCTURAL_GRAPH invalida", () => {
    const { queryKey } = graphSliceQueryOptions(AN, { level: "PACKAGE" });

    expect(queryKey.slice(0, 3)).toStrictEqual(queryKeys.structure.all(AN));
    expect(queryKey).toStrictEqual(
      queryKeys.structure.slice(AN, { level: "PACKAGE" })
    );
  });

  test("parentId ausente e parentId undefined dão a mesma chave, sem chave undefined", () => {
    const absent = graphSliceQueryOptions(AN, { level: "PACKAGE" }).queryKey;
    const explicit = graphSliceQueryOptions(AN, {
      level: "PACKAGE",
      parentId: undefined,
    }).queryKey;

    expect(explicit).toStrictEqual(absent);
    expect(explicit.at(-1)).not.toHaveProperty("parentId");
  });

  test("o pai entra na chave, então cada pacote aberto tem a sua entrada", () => {
    const { queryKey } = graphSliceQueryOptions(AN, {
      level: "TYPE",
      parentId: ORDERS,
    });

    expect(queryKey.at(-1)).toStrictEqual({ level: "TYPE", parentId: ORDERS });
  });

  test("uma invalidação de structure.all alcança a fatia em cache", async () => {
    const { queryClient, result } = renderSliceQuery({ level: "PACKAGE" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    await queryClient.invalidateQueries({
      queryKey: queryKeys.structure.all(AN),
      refetchType: "none",
    });

    const { queryKey } = graphSliceQueryOptions(AN, { level: "PACKAGE" });
    expect(queryClient.getQueryState(queryKey)?.isInvalidated).toBe(true);
  });
});

describe("graphSliceQueryOptions: o pedido", () => {
  test("a chave e o payload saem do mesmo objeto: sem parentId no nível 1", async () => {
    const request = vi.spyOn(transport, "request");
    const { result } = renderSliceQuery({
      level: "PACKAGE",
      parentId: undefined,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(request).toHaveBeenCalledTimes(1);
    const [method, payload] = request.mock.calls[0] ?? [];
    expect(method).toBe("graph.slice");
    expect(payload).toStrictEqual({ analysisId: AN, level: "PACKAGE" });
  });

  test("o drill-down manda o pai", async () => {
    const request = vi.spyOn(transport, "request");
    const { result } = renderSliceQuery({ level: "TYPE", parentId: ORDERS });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(request.mock.calls[0]?.[1]).toStrictEqual({
      analysisId: AN,
      level: "TYPE",
      parentId: ORDERS,
    });
  });

  test("marca o início do pedido no User Timing, que o benchmark mede", async () => {
    const mark = vi.spyOn(performance, "mark");
    const { result } = renderSliceQuery({ level: "PACKAGE" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mark).toHaveBeenCalledWith(GRAPH_PERF.request);
  });

  test("fora de DEV o pedido não marca: um refetch em segundo plano não deixa entrada", async () => {
    vi.stubEnv("DEV", false);
    const mark = vi.spyOn(performance, "mark");
    const { result } = renderSliceQuery({ level: "PACKAGE" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mark).not.toHaveBeenCalledWith(GRAPH_PERF.request);
  });
});

describe("graphSliceQueryOptions: a camada", () => {
  afterEach(() => {
    vi.doUnmock("@/features/graph/architecture-graph");
    vi.doUnmock("cytoscape");
    vi.resetModules();
  });

  test("importar as queries não carrega o componente do canvas nem o Cytoscape", async () => {
    vi.resetModules();
    vi.doMock("@/features/graph/architecture-graph", () => {
      throw new Error("queries/ importou o componente do canvas");
    });
    vi.doMock("cytoscape", () => {
      throw new Error("queries/ carregou o Cytoscape");
    });

    await expect(import("@/queries/options")).resolves.toHaveProperty(
      "graphSliceQueryOptions"
    );
  });
});

describe("graphSliceQueryOptions: o select", () => {
  test("resolve no navegador para a GraphView da fixture do nível 1", async () => {
    const fixture = await fixtureSlice({ level: "PACKAGE" });
    const { result } = renderSliceQuery({ level: "PACKAGE" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const view = result.current.data;
    expect(view?.slice).toStrictEqual(fixture);
    expect(view?.elements).toStrictEqual(toGraphElements(fixture));
    expect(view?.elements.nodes.map((node) => node.data.id)).toStrictEqual([
      "pkg:br.pucpr.billing",
      ORDERS,
      "pkg:br.pucpr.shared",
    ]);
  });

  test("resolve os tipos do pacote aberto", async () => {
    const { result } = renderSliceQuery({ level: "TYPE", parentId: ORDERS });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data?.slice.parentId).toBe(ORDERS);
    expect(result.current.data?.elements.nodes).toHaveLength(3);
  });

  test("uma fatia acima do orçamento vira o erro da query, não um throw no render", async () => {
    const fixture = await fixtureSlice({ level: "PACKAGE" });
    const overBudget: GraphSlice = {
      ...fixture,
      budget: { edges: fixture.budget.edges, nodes: 2 },
    };
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
      {
        method: "graph.slice",
        request: { level: "PACKAGE" },
        result: overBudget,
      },
    ] satisfies readonly FakeTranscriptOverride[]);

    const { result } = renderSliceQuery({ level: "PACKAGE" });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.data).toBeUndefined();
    expect(result.current.error).toBeInstanceOf(GraphSliceViolationError);
    expect(result.current.error).toMatchObject({
      violation: "NODES_OVER_BUDGET",
    });
  });

  test("o select é uma referência estável entre chamadas da fábrica", () => {
    const first = graphSliceQueryOptions(AN, { level: "PACKAGE" });
    const second = graphSliceQueryOptions(AN, {
      level: "TYPE",
      parentId: ORDERS,
    });

    expect(first.select).toBe(second.select);
  });
});

describe("as fábricas genéricas", () => {
  test("analyzerQueryOptions usa a chave dada e pede o método sem handle", async () => {
    const request = vi.spyOn(transport, "request");
    const queryKey = ["teste", "ping"] as const;
    const options = analyzerQueryOptions(queryKey, "system.ping", {});

    const result = await createQueryClient().fetchQuery(options);

    expect(options.queryKey).toBe(queryKey);
    expect(result).toHaveProperty("uptimeMillis");
    expect(request).toHaveBeenCalledWith("system.ping", {});
  });

  test("repositoryQueryOptions manda o handle junto ao pedido", async () => {
    const request = vi.spyOn(transport, "request");
    const options = repositoryQueryOptions(
      queryKeys.repository.describe(HANDLE),
      "repository.describe",
      {},
      HANDLE
    );

    const description = await createQueryClient().fetchQuery(options);

    expect(request).toHaveBeenCalledWith("repository.describe", {}, HANDLE);
    expect(description).toHaveProperty("buildSystem");
  });
});

const wrapperFor =
  (queryClient: ReturnType<typeof createQueryClient>) =>
  ({ children }: { readonly children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);

describe("as queries de abrir e analisar", () => {
  test("o status fica sob o prefixo da análise, que os três terminais invalidam", () => {
    const { queryKey } = analysisStatusQueryOptions(AN);

    expect(queryKey).toStrictEqual(queryKeys.analysisStatus(AN));
    expect(queryKey.slice(0, 2)).toStrictEqual(queryKeys.analysis(AN));
  });

  test("a descrição fica sob o handle, fora de qualquer análise", () => {
    const { queryKey } = repositoryDescribeQueryOptions(HANDLE);

    expect(queryKey).toStrictEqual(queryKeys.repository.describe(HANDLE));
    expect(queryKey.slice(0, 1)).toStrictEqual(queryKeys.repository.all);
  });

  test("a descrição manda o handle junto ao pedido", async () => {
    const request = vi.spyOn(transport, "request");

    const description = await createQueryClient().fetchQuery(
      repositoryDescribeQueryOptions(HANDLE)
    );

    expect(request).toHaveBeenCalledWith("repository.describe", {}, HANDLE);
    expect(description).toHaveProperty("sourceRoots");
  });

  test("o status pede analysis.status pelo id, sem handle", async () => {
    const request = vi.spyOn(transport, "request");

    const report = await createQueryClient().fetchQuery(
      analysisStatusQueryOptions(AN)
    );

    expect(request).toHaveBeenCalledWith("analysis.status", { analysisId: AN });
    expect(report.analysisId).toBe(AN);
  });

  test("uma invalidação do prefixo da análise alcança o status em cache", async () => {
    const queryClient = createQueryClient();
    await queryClient.fetchQuery(analysisStatusQueryOptions(AN));

    await queryClient.invalidateQueries({
      queryKey: queryKeys.analysis(AN),
      refetchType: "none",
    });

    expect(
      queryClient.getQueryState(queryKeys.analysisStatus(AN))?.isInvalidated
    ).toBe(true);
  });
});

describe("analysisStartMutationOptions", () => {
  const config: AnalysisConfiguration = {
    historyScope: "ALL",
    historyScopeCommits: null,
    includeTests: false,
    rebuildCache: false,
    sourceRootIds: null,
  };

  const start = (queryClient = createQueryClient()) =>
    new MutationObserver(
      queryClient,
      analysisStartMutationOptions(HANDLE)
    ).mutate(config);

  test("manda as cinco chaves e o handle, e devolve o id da análise", async () => {
    const request = vi.spyOn(transport, "request");

    const started = await start();

    expect(started.analysisId).toBe(AN);
    expect(request).toHaveBeenCalledTimes(1);
    const [method, payload, handle] = request.mock.calls[0] ?? [];
    expect(method).toBe("analysis.start");
    expect(handle).toBe(HANDLE);
    expect(Object.keys(payload ?? {}).sort()).toStrictEqual([
      "historyScope",
      "historyScopeCommits",
      "includeTests",
      "rebuildCache",
      "sourceRootIds",
    ]);
    expect(payload).toStrictEqual(config);
  });

  test("não semeia a análise corrente do repositório", async () => {
    const queryClient = createQueryClient();

    await start(queryClient);

    expect(
      queryClient
        .getQueryCache()
        .find({ queryKey: queryKeys.repository.currentAnalysis(HANDLE) })
    ).toBeUndefined();
  });
});

describe("outlineChildrenQueryOptions", () => {
  test("a chave fica sob structure.all e não carrega cursor", () => {
    const { queryKey } = outlineChildrenQueryOptions(AN, ORDERS);

    expect(queryKey).toStrictEqual(queryKeys.structure.outline(AN, ORDERS));
    expect(queryKey.slice(0, 3)).toStrictEqual(queryKeys.structure.all(AN));
  });

  test("a primeira página da raiz pede parentId nulo, sem cursor, na página máxima", async () => {
    const request = vi.spyOn(transport, "request");
    const queryClient = createQueryClient();
    const { result } = renderHook(
      () => useInfiniteQuery(outlineChildrenQueryOptions(AN, null)),
      { wrapper: wrapperFor(queryClient) }
    );

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(request).toHaveBeenCalledTimes(1);
    const [method, payload] = request.mock.calls[0] ?? [];
    expect(method).toBe("outline.children");
    expect(payload).toMatchObject({
      analysisId: AN,
      limit: OUTLINE_PAGE_LIMIT,
      parentId: null,
    });
    expect((payload as { cursor?: unknown }).cursor).toBeUndefined();
    expect(result.current.data?.map((node) => node.id)).toStrictEqual([
      "pkg:br.pucpr.billing",
      ORDERS,
      "pkg:br.pucpr.shared",
    ]);
    expect(result.current.hasNextPage).toBe(false);
  });

  test("a segunda página vai pelo cursor da primeira, na mesma entrada de cache", async () => {
    const nextCursor = "Y3Vyc29yLTI";
    const node = (id: string) => ({
      childCount: 0,
      id,
      kind: "TYPE",
      label: id,
    });
    const request = vi
      .spyOn(transport, "request")
      .mockImplementation((_method, payload) => {
        const { cursor } = payload as { cursor?: string };
        return Promise.resolve(
          cursor === undefined
            ? { items: [node("type:a.A")], nextCursor }
            : { items: [node("type:a.B")], nextCursor: null }
        );
      });
    const queryClient = createQueryClient();
    const { result } = renderHook(
      () => useInfiniteQuery(outlineChildrenQueryOptions(AN, ORDERS)),
      { wrapper: wrapperFor(queryClient) }
    );
    await waitFor(() => {
      expect(result.current.hasNextPage).toBe(true);
    });

    await result.current.fetchNextPage();

    await waitFor(() => {
      expect(result.current.data?.map((item) => item.id)).toStrictEqual([
        "type:a.A",
        "type:a.B",
      ]);
    });
    expect(result.current.hasNextPage).toBe(false);
    expect(request.mock.calls[1]?.[1]).toMatchObject({
      cursor: nextCursor,
      parentId: ORDERS,
    });
    expect(
      queryClient.getQueryCache().findAll({
        queryKey: queryKeys.structure.outline(AN, ORDERS),
      })
    ).toHaveLength(1);
  });
});

describe("graphSearchQueryOptions", () => {
  test("a chave fica sob structure.all", () => {
    const { queryKey } = graphSearchQueryOptions(AN, "order");

    expect(queryKey).toStrictEqual(queryKeys.structure.search(AN, "order"));
    expect(queryKey.slice(0, 3)).toStrictEqual(queryKeys.structure.all(AN));
  });

  test("pede só pacotes e tipos, com a consulta como veio", async () => {
    const request = vi.spyOn(transport, "request");

    const result = await createQueryClient().fetchQuery(
      graphSearchQueryOptions(AN, "order")
    );

    expect(request).toHaveBeenCalledWith("graph.search", {
      analysisId: AN,
      kinds: [...SEARCH_KINDS],
      query: "order",
    });
    expect(result.results.map((hit) => hit.elementId)).toContain(ORDERS);
  });

  test("a consulta vazia não vira pedido", async () => {
    const request = vi.spyOn(transport, "request");
    const queryClient = createQueryClient();
    const { result } = renderHook(
      () => useQuery(graphSearchQueryOptions(AN, "")),
      { wrapper: wrapperFor(queryClient) }
    );

    await waitFor(() => {
      expect(result.current.fetchStatus).toBe("idle");
    });
    expect(request).not.toHaveBeenCalled();
    expect(result.current.data).toBeUndefined();
  });
});
