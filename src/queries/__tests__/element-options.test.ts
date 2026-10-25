import { QueryClientProvider, useInfiniteQuery } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { transport } from "@/bridge/transport";
import { DependencyPageContractError } from "@/features/inspector/inspector-model";
import type { DependencyPage } from "@/protocol/graph";
import { createQueryClient } from "@/queries/client";
import { queryKeys } from "@/queries/keys";
import {
  DEPENDENCY_PAGE_LIMIT,
  dependencyPagesQueryOptions,
  elementDetailQueryOptions,
} from "@/queries/options";
import {
  externalTypeRow,
  internalTypeRow,
  NEXT_CURSOR,
  pageOf,
} from "@/test/dependency-rows";
import {
  DESCRIBED_IDS,
  dependencyPageOf,
  detailOf,
} from "@/test/fixture-graph";
import { WORLD_ANALYSIS_ID } from "@/test/inspector";

const AN = WORLD_ANALYSIS_ID;

const CATALOG_ENTRY_ID =
  DESCRIBED_IDS.find((id) => detailOf(id).label === "CatalogEntry") ?? "";

const LIMITS_PAGE_DEFAULT = 500;

afterEach(() => {
  vi.restoreAllMocks();
});

const wrapperFor =
  (queryClient: ReturnType<typeof createQueryClient>) =>
  ({ children }: { readonly children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);

const mockPages = (pages: readonly DependencyPage[]) =>
  vi
    .spyOn(transport, "request")
    .mockImplementation((_method: string, payload: unknown) => {
      const { cursor } = payload as { readonly cursor?: string };
      const index = cursor === undefined ? 0 : 1;
      const page = pages[index];
      return page === undefined
        ? Promise.reject(new Error(`sem página ${index}`))
        : Promise.resolve(page);
    });

describe("elementDetailQueryOptions", () => {
  test("a chave é structure.element, sob structure.all", () => {
    const { queryKey } = elementDetailQueryOptions(AN, CATALOG_ENTRY_ID);

    expect(queryKey).toStrictEqual(
      queryKeys.structure.element(AN, CATALOG_ENTRY_ID)
    );
    expect(queryKey.slice(0, 3)).toStrictEqual(queryKeys.structure.all(AN));
  });

  test("pede element.describe pelo id, sem handle, e devolve o detalhe do mundo", async () => {
    const request = vi.spyOn(transport, "request");

    const detail = await createQueryClient().fetchQuery(
      elementDetailQueryOptions(AN, CATALOG_ENTRY_ID)
    );

    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith("element.describe", {
      analysisId: AN,
      elementId: CATALOG_ENTRY_ID,
    });
    expect(detail).toStrictEqual(detailOf(CATALOG_ENTRY_ID));
  });
});

describe("dependencyPagesQueryOptions: a chave", () => {
  test("o limite é o padrão da spec, 500", () => {
    expect(DEPENDENCY_PAGE_LIMIT).toBe(LIMITS_PAGE_DEFAULT);
  });

  test("a chave é structure.dependencies com a direção e o limite, sem cursor", () => {
    const { queryKey } = dependencyPagesQueryOptions(
      AN,
      CATALOG_ENTRY_ID,
      "IN"
    );

    expect(queryKey).toStrictEqual(
      queryKeys.structure.dependencies(
        AN,
        CATALOG_ENTRY_ID,
        "IN",
        LIMITS_PAGE_DEFAULT
      )
    );
  });

  test("é filha da chave do elemento: remover o elemento por prefixo leva as duas direções", () => {
    const element = queryKeys.structure.element(AN, CATALOG_ENTRY_ID);
    const keys = (["IN", "OUT"] as const).map(
      (direction) =>
        dependencyPagesQueryOptions(AN, CATALOG_ENTRY_ID, direction).queryKey
    );

    for (const key of keys) {
      expect(key.slice(0, element.length)).toStrictEqual(element);
    }
    expect(keys[0]).not.toStrictEqual(keys[1]);
  });
});

describe("dependencyPagesQueryOptions: o pedido", () => {
  test("a primeira página envia limit 500 e nenhum cursor", async () => {
    const request = vi.spyOn(transport, "request");

    await createQueryClient().fetchInfiniteQuery(
      dependencyPagesQueryOptions(AN, CATALOG_ENTRY_ID, "OUT")
    );

    expect(request).toHaveBeenCalledTimes(1);
    const [method, payload] = request.mock.calls[0] ?? [];
    expect(method).toBe("element.dependencies");
    expect(payload).toMatchObject({
      analysisId: AN,
      direction: "OUT",
      elementId: CATALOG_ENTRY_ID,
      limit: LIMITS_PAGE_DEFAULT,
    });
    expect((payload as { cursor?: unknown }).cursor).toBeUndefined();
  });

  test("a segunda página vai pelo cursor da primeira, com o mesmo limite", async () => {
    const totals = { in: 2, out: 0, outExternal: null };
    const request = mockPages([
      pageOf([internalTypeRow(1)], totals, NEXT_CURSOR),
      pageOf([internalTypeRow(2)], totals),
    ]);

    await createQueryClient().fetchInfiniteQuery({
      ...dependencyPagesQueryOptions(AN, CATALOG_ENTRY_ID, "IN"),
      pages: 2,
    });

    expect(request).toHaveBeenCalledTimes(2);
    expect(request.mock.calls[1]?.[1]).toMatchObject({
      cursor: NEXT_CURSOR,
      direction: "IN",
      elementId: CATALOG_ENTRY_ID,
      limit: LIMITS_PAGE_DEFAULT,
    });
  });

  test("getNextPageParam é a identidade sobre nextCursor", () => {
    const { getNextPageParam } = dependencyPagesQueryOptions(
      AN,
      CATALOG_ENTRY_ID,
      "IN"
    );
    const totals = { in: DEPENDENCY_PAGE_LIMIT, out: 0, outExternal: null };
    const short = pageOf(
      [internalTypeRow(1), internalTypeRow(2), internalTypeRow(3)],
      totals,
      NEXT_CURSOR
    );
    const full = pageOf(
      Array.from({ length: DEPENDENCY_PAGE_LIMIT }, (_, n) =>
        internalTypeRow(n)
      ),
      totals
    );

    expect(getNextPageParam(short, [short], null, [null])).toBe(NEXT_CURSOR);
    expect(getNextPageParam(full, [full], null, [null])).toBeNull();
  });
});

describe("dependencyPagesQueryOptions: o select", () => {
  test("achata a página do mundo: Kind, o cabeçalho das externas, java.lang.String", async () => {
    const page = dependencyPageOf(CATALOG_ENTRY_ID, "OUT");
    const queryClient = createQueryClient();
    const { result } = renderHook(
      () =>
        useInfiniteQuery(
          dependencyPagesQueryOptions(AN, CATALOG_ENTRY_ID, "OUT")
        ),
      { wrapper: wrapperFor(queryClient) }
    );

    await waitFor(() => {
      expect(result.current.status).not.toBe("pending");
    });

    expect(result.current.error).toBeNull();
    expect(result.current.data?.items.map((item) => item.type)).toStrictEqual([
      "row",
      "externals-heading",
      "row",
    ]);
    expect(result.current.data?.reachable).toBe(
      page.totals.out + (page.totals.outExternal ?? 0) + 1
    );
    expect(result.current.hasNextPage).toBe(false);
  });

  test("uma página que fura o contrato vira o erro da query, e nenhuma página a mais", async () => {
    const totals = { in: 0, out: 1, outExternal: 1 };
    const request = mockPages([
      pageOf([externalTypeRow(1), internalTypeRow(2)], totals),
    ]);
    const queryClient = createQueryClient();
    const { result } = renderHook(
      () =>
        useInfiniteQuery(
          dependencyPagesQueryOptions(AN, CATALOG_ENTRY_ID, "OUT")
        ),
      { wrapper: wrapperFor(queryClient) }
    );

    await waitFor(() => {
      expect(result.current.error).toBeInstanceOf(DependencyPageContractError);
    });
    expect(
      (result.current.error as DependencyPageContractError).violation
    ).toBe("INTERNAL_AFTER_EXTERNAL");
    expect(request).toHaveBeenCalledTimes(1);
  });
});
