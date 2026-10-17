import { afterEach, describe, expect, test, vi } from "vitest";
import { isTauri } from "@/bridge/environment";
import {
  FakeBridgeError,
  fakeBridgeRoutes,
  fakeEmit,
  fakeRequest,
  fakeSubscribe,
} from "@/bridge/fake";
import { graphSliceSchema, outlinePageSchema } from "@/protocol/graph";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const protocolErrorOf = async (
  method: string,
  payload: unknown
): Promise<Record<string, unknown>> => {
  try {
    await fakeRequest(method, payload);
  } catch (error) {
    if (error instanceof FakeBridgeError) {
      return error.protocolError as unknown as Record<string, unknown>;
    }
    throw new Error(
      `${method} rejeitou com algo que não é um FakeBridgeError`,
      { cause: error }
    );
  }
  throw new Error(`${method} resolveu quando devia rejeitar`);
};

describe("a descoberta das transcrições", () => {
  test("o mapa de rotas é exatamente o esperado, e um mapa vazio não passa", () => {
    expect(fakeBridgeRoutes()).toStrictEqual({
      "graph.search": ["hit"],
      "graph.slice": ["package", "type"],
      "outline.children": ["root"],
      "repository.describe": ["ok"],
      "system.ping": ["ok"],
      "system.shutdown": ["ok"],
    });
  });

  test("nenhuma fixture negativa e nenhum tópico reservado vira rota", () => {
    for (const method of Object.keys(fakeBridgeRoutes())) {
      expect(method.startsWith("protocol")).toBe(false);
    }
  });
});

describe("servir uma fatia", () => {
  test("graph.slice de nível 1 devolve uma fatia válida dentro do orçamento", async () => {
    const slice = graphSliceSchema.parse(
      await fakeRequest("graph.slice", { analysisId: AN, level: "PACKAGE" })
    );
    expect(slice.level).toBe("PACKAGE");
    expect(slice.parentId).toBeNull();
    expect(slice.budget).toStrictEqual({ edges: 1200, nodes: 300 });
    expect(slice.nodes.length).toBeLessThanOrEqual(slice.budget.nodes);
    expect(slice.edges.length).toBeLessThanOrEqual(slice.budget.edges);
    expect(slice.totals).toStrictEqual({
      edges: slice.edges.length,
      nodes: slice.nodes.length,
    });
  });

  test("o discriminante escolhe o drill-down, e não a primeira fixture do tópico", async () => {
    const slice = graphSliceSchema.parse(
      await fakeRequest("graph.slice", {
        analysisId: AN,
        level: "TYPE",
        parentId: "pkg:br.pucpr.orders",
      })
    );
    expect([slice.level, slice.parentId]).toStrictEqual([
      "TYPE",
      "pkg:br.pucpr.orders",
    ]);
    expect(slice.nodes.every((node) => node.kind === "TYPE")).toBe(true);
  });

  test("toda aresta liga dois nós presentes na mesma fatia", async () => {
    const slice = graphSliceSchema.parse(
      await fakeRequest("graph.slice", { analysisId: AN, level: "PACKAGE" })
    );
    const ids = new Set(slice.nodes.map((node) => node.id));
    for (const edge of slice.edges) {
      expect([
        edge.id,
        ids.has(edge.source),
        ids.has(edge.target),
      ]).toStrictEqual([edge.id, true, true]);
    }
  });

  test("outline.children pede a raiz com parentId nulo", async () => {
    const page = outlinePageSchema.parse(
      await fakeRequest("outline.children", { analysisId: AN, parentId: null })
    );
    expect(page.nextCursor).toBeNull();
    expect(page.items.length).toBeGreaterThan(0);
  });
});

describe("as três recusas, que dizem coisas diferentes", () => {
  test("um método fora do registro é PROTO_UNKNOWN_METHOD, origem analyzer", async () => {
    const error = await protocolErrorOf("graph.nonexistent", {});
    expect([error.code, error.origin, error.params]).toStrictEqual([
      "PROTO_UNKNOWN_METHOD",
      "analyzer",
      { method: "graph.nonexistent" },
    ]);
  });

  test("um payload que não passa no schema do método é PROTO_INVALID_PAYLOAD", async () => {
    const error = await protocolErrorOf("graph.slice", {
      analysisId: AN,
      level: "GALAXY",
    });
    expect([error.code, error.params]).toStrictEqual([
      "PROTO_INVALID_PAYLOAD",
      { method: "graph.slice" },
    ]);
  });

  test("um método conhecido sem fixture falha alto, nomeando o método", async () => {
    const error = await protocolErrorOf("tour", { analysisId: AN });
    expect([error.code, error.origin]).toStrictEqual([
      "INTERNAL_UNEXPECTED",
      "frontend",
    ]);
    expect(error.detail).toContain("tour");
  });
});

describe("os overrides de desenvolvimento", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  const PACKAGE_REQUEST = { analysisId: AN, level: "PACKAGE" } as const;
  const OVERRIDE_RESULT = { marker: "override" };

  const installOverrides = (
    overrides: readonly FakeTranscriptOverride[]
  ): void => {
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", overrides);
  };

  test("um override que casa vence a fixture e volta tal como está", async () => {
    installOverrides([
      {
        method: "graph.slice",
        request: { level: "PACKAGE" },
        result: OVERRIDE_RESULT,
      },
    ]);
    expect(await fakeRequest("graph.slice", PACKAGE_REQUEST)).toBe(
      OVERRIDE_RESULT
    );
  });

  test("o primeiro override que casa vence os seguintes", async () => {
    const second = { marker: "second" };
    installOverrides([
      {
        method: "graph.slice",
        request: { level: "PACKAGE" },
        result: OVERRIDE_RESULT,
      },
      { method: "graph.slice", request: { level: "PACKAGE" }, result: second },
    ]);
    expect(await fakeRequest("graph.slice", PACKAGE_REQUEST)).toBe(
      OVERRIDE_RESULT
    );
  });

  test("um override que não casa pelos discriminantes cai na fixture", async () => {
    installOverrides([
      {
        method: "graph.slice",
        request: { level: "TYPE", parentId: "pkg:br.pucpr.orders" },
        result: OVERRIDE_RESULT,
      },
    ]);
    const slice = graphSliceSchema.parse(
      await fakeRequest("graph.slice", PACKAGE_REQUEST)
    );
    expect([slice.level, slice.nodes.length]).toStrictEqual(["PACKAGE", 3]);
  });

  test("um override de outro método é ignorado", async () => {
    installOverrides([
      {
        method: "outline.children",
        request: { level: "PACKAGE" },
        result: OVERRIDE_RESULT,
      },
    ]);
    const slice = graphSliceSchema.parse(
      await fakeRequest("graph.slice", PACKAGE_REQUEST)
    );
    expect(slice.level).toBe("PACKAGE");
  });

  test("sem discriminantes, a primeira fixture e o primeiro override casam com qualquer pedido", async () => {
    expect(await fakeRequest("system.ping", {})).toStrictEqual({
      uptimeMillis: 0,
    });
    installOverrides([
      {
        method: "system.ping",
        request: { ignored: true },
        result: OVERRIDE_RESULT,
      },
    ]);
    expect(await fakeRequest("system.ping", {})).toBe(OVERRIDE_RESULT);
  });

  test("um override não faz um pedido inválido passar", async () => {
    installOverrides([
      { method: "graph.slice", request: {}, result: OVERRIDE_RESULT },
    ]);
    const error = await protocolErrorOf("graph.slice", {
      analysisId: AN,
      level: "GALAXY",
    });
    expect(error.code).toBe("PROTO_INVALID_PAYLOAD");
  });

  test("fora do DEV o global nunca é lido", async () => {
    vi.stubEnv("DEV", false);
    installOverrides([
      {
        method: "graph.slice",
        request: { level: "PACKAGE" },
        result: OVERRIDE_RESULT,
      },
    ]);
    const slice = graphSliceSchema.parse(
      await fakeRequest("graph.slice", PACKAGE_REQUEST)
    );
    expect(slice.nodes.length).toBe(3);
  });
});

describe("os eventos", () => {
  test("um evento com fixture chega ao assinante, e desassinar cala", () => {
    const seen: unknown[] = [];
    const unsubscribe = fakeSubscribe((event) => seen.push(event));
    expect(fakeEmit("protocol.ready")).toBe(true);
    unsubscribe();
    expect(fakeEmit("protocol.ready")).toBe(true);
    expect(seen).toHaveLength(1);
  });

  test("o caso escolhe entre duas fixtures do mesmo evento", () => {
    const seen: { payload: unknown }[] = [];
    const unsubscribe = fakeSubscribe((event) => seen.push(event));
    fakeEmit("analysis.progress", "known-total");
    fakeEmit("analysis.progress", "unknown-total");
    unsubscribe();
    const totals = seen.map(
      (event) => (event.payload as { total: number | null }).total
    );
    expect(totals).toStrictEqual([3902, null]);
  });

  test("um caso que não existe não publica nada", () => {
    const seen: unknown[] = [];
    const unsubscribe = fakeSubscribe((event) => seen.push(event));
    expect(fakeEmit("analysis.progress", "no-such-case")).toBe(false);
    unsubscribe();
    expect(seen).toHaveLength(0);
  });
});

describe("a guarda que escolhe entre os dois bridges", () => {
  test("sem __TAURI_INTERNALS__ a guarda é falsa, como no navegador puro", () => {
    expect(isTauri()).toBe(false);
  });

  test("com a propriedade presente a guarda é verdadeira", () => {
    vi.stubGlobal("window", { __TAURI_INTERNALS__: {} });
    expect(isTauri()).toBe(true);
  });
});
