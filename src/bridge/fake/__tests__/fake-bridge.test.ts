import { afterEach, describe, expect, test, vi } from "vitest";
import { isTauri } from "@/bridge/environment";
import {
  FakeBridgeError,
  fakeBridgeRoutes,
  fakeEmit,
  fakeRequest,
  fakeSubscribe,
  readMethodTranscripts,
} from "@/bridge/fake";
import { dependencyDirectionSchema } from "@/protocol/enums";
import { graphSliceSchema, outlinePageSchema } from "@/protocol/graph";
import {
  DESCRIBED_IDS,
  DRILLED_PACKAGE_ID,
  dependencyPageOf,
  detailOf,
  ELEMENT_REFUSALS,
  EVIDENCE_PAIRS,
  evidenceOf,
  outlinePageOf,
  PACKAGE_IDS,
  PACKAGE_SLICE,
  packageNodeOf,
  ROOT_ITEMS,
  TYPE_IDS,
  typeSliceOf,
} from "@/test/fixture-graph";
import aggregatedSource from "../../../../protocol/fixtures/graph.slice.package-aggregated.jsonl?raw";

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
      "analysis.cancel": ["unknown"],
      "analysis.start": ["ok"],
      "analysis.status": ["completed"],
      "element.dependencies": [
        "api-in",
        "api-out",
        "audited-in",
        "audited-out",
        "catalog-endpoint-in",
        "catalog-endpoint-out",
        "catalog-entry-in",
        "catalog-entry-out",
        "catalog-in",
        "catalog-metadata-in",
        "catalog-metadata-out",
        "catalog-out",
        "core-gen-in",
        "core-gen-out",
        "core-in",
        "core-out",
        "core-spi-in",
        "core-spi-out",
        "kind-in",
        "kind-out",
        "main-in",
        "main-out",
        "memory-store-in",
        "memory-store-out",
        "store-in",
        "store-out",
      ],
      "element.dependencyEvidence": [
        "catalog-endpoint-catalog",
        "catalog-store",
        "main-string",
      ],
      "element.describe": [
        "api",
        "audited",
        "catalog",
        "catalog-endpoint",
        "catalog-entry",
        "catalog-metadata",
        "core",
        "core-gen",
        "core-spi",
        "kind",
        "main",
        "memory-store",
        "store",
      ],
      "graph.search": ["hit"],
      "graph.slice": [
        "package",
        "type",
        "type-api",
        "type-core-gen",
        "type-core-spi",
      ],
      "outline.children": ["api", "core", "core-gen", "core-spi", "root"],
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

describe("o conjunto UNSERVED: validada na carga, nunca servida", () => {
  const AGGREGATED_STEM = "graph.slice.package-aggregated";
  const sources = {
    [`../../../protocol/fixtures/${AGGREGATED_STEM}.jsonl`]: aggregatedSource,
  };

  test("um nome sem fixture reprova a carga, e diz qual: um arquivo renomeado não passa calado", () => {
    expect(() =>
      readMethodTranscripts(
        sources,
        new Set([AGGREGATED_STEM, "graph.slice.renamed"])
      )
    ).toThrow("UNSERVED cita fixtures que não existem: graph.slice.renamed");
  });

  test("sem fixture nenhuma, todo nome do conjunto é velho", () => {
    expect(() => readMethodTranscripts({}, new Set([AGGREGATED_STEM]))).toThrow(
      `UNSERVED cita fixtures que não existem: ${AGGREGATED_STEM}`
    );
  });

  test("a fixture que o conjunto nomeia é lida e não vira rota", () => {
    const routes = readMethodTranscripts(sources, new Set([AGGREGATED_STEM]));

    expect(routes.get("graph.slice")).toBeUndefined();
  });

  test("fora do conjunto, a mesma fixture viraria o caso package-aggregated do nível 1", () => {
    const routes = readMethodTranscripts(sources, new Set());

    expect(
      routes.get("graph.slice")?.map((route) => route.caseName)
    ).toStrictEqual(["package-aggregated"]);
    expect(routes.get("graph.slice")?.[0]?.request).toMatchObject({
      level: "PACKAGE",
    });
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

  test("o nível 1 servido é o graph.slice.package do mundo, e não outro caso de nível 1", async () => {
    const slice = graphSliceSchema.parse(
      await fakeRequest("graph.slice", { analysisId: AN, level: "PACKAGE" })
    );
    expect(slice).toStrictEqual(PACKAGE_SLICE);
    expect([slice.aggregated, slice.truncated]).toStrictEqual([false, false]);
  });

  test("o discriminante escolhe o drill-down, e não a primeira fixture do tópico", async () => {
    const slice = graphSliceSchema.parse(
      await fakeRequest("graph.slice", {
        analysisId: AN,
        level: "TYPE",
        parentId: DRILLED_PACKAGE_ID,
      })
    );
    expect([slice.level, slice.parentId]).toStrictEqual([
      "TYPE",
      DRILLED_PACKAGE_ID,
    ]);
    expect(slice.nodes.every((node) => node.kind === "TYPE")).toBe(true);
  });

  test.each(PACKAGE_IDS)(
    "todo pacote do nível 1 desce: %s tem a fatia TYPE e os filhos da estrutura dele",
    async (packageId) => {
      const slice = graphSliceSchema.parse(
        await fakeRequest("graph.slice", {
          analysisId: AN,
          level: "TYPE",
          parentId: packageId,
        })
      );
      const page = outlinePageSchema.parse(
        await fakeRequest("outline.children", {
          analysisId: AN,
          parentId: packageId,
        })
      );

      expect(slice).toStrictEqual(typeSliceOf(packageId));
      expect(page).toStrictEqual(outlinePageOf(packageId));
      expect([slice.level, slice.parentId, slice.truncated]).toStrictEqual([
        "TYPE",
        packageId,
        false,
      ]);
      expect(page.nextCursor).toBeNull();
      expect(page.items.map((item) => item.id).sort()).toStrictEqual(
        slice.nodes.map((node) => node.id).sort()
      );
    }
  );

  test.each(PACKAGE_IDS)(
    "childCount, typeCount e totals.nodes de %s são o mesmo número",
    (packageId) => {
      const root = ROOT_ITEMS.find((item) => item.id === packageId);
      const { nodes, totals } = typeSliceOf(packageId);

      expect([
        root?.childCount,
        packageNodeOf(packageId).typeCount,
        nodes.length,
      ]).toStrictEqual([totals.nodes, totals.nodes, totals.nodes]);
    }
  );

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
    expect(page.items).toStrictEqual(ROOT_ITEMS);
    expect(page.items.map((item) => item.id)).toStrictEqual(PACKAGE_IDS);
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
        request: { level: "TYPE", parentId: DRILLED_PACKAGE_ID },
        result: OVERRIDE_RESULT,
      },
    ]);
    const slice = graphSliceSchema.parse(
      await fakeRequest("graph.slice", PACKAGE_REQUEST)
    );
    expect([slice.level, slice.nodes.length]).toStrictEqual([
      "PACKAGE",
      PACKAGE_SLICE.nodes.length,
    ]);
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
    expect(slice.nodes.length).toBe(PACKAGE_SLICE.nodes.length);
  });
});

describe("os discriminantes de element.*", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  const CATALOG = "type:nowhere.Catalog";
  const STORE = "type:nowhere.Store";
  const ENTRY = "type:nowhere.CatalogEntry";
  const IN_PAGE = { marker: "in" };
  const OUT_PAGE = { marker: "out" };
  const EVIDENCE = { marker: "evidence" };

  const installOverrides = (
    overrides: readonly FakeTranscriptOverride[]
  ): void => {
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", overrides);
  };

  test("element.dependencies escolhe pelo elemento e pela direção", async () => {
    installOverrides([
      {
        method: "element.dependencies",
        request: { direction: "IN", elementId: CATALOG },
        result: IN_PAGE,
      },
      {
        method: "element.dependencies",
        request: { direction: "OUT", elementId: CATALOG },
        result: OUT_PAGE,
      },
    ]);

    expect(
      await fakeRequest("element.dependencies", {
        analysisId: AN,
        direction: "OUT",
        elementId: CATALOG,
      })
    ).toBe(OUT_PAGE);
    expect(
      await fakeRequest("element.dependencies", {
        analysisId: AN,
        direction: "IN",
        elementId: CATALOG,
      })
    ).toBe(IN_PAGE);
  });

  test("cursor e limit não escolhem caso: a página seguinte casa com a mesma transcrição", async () => {
    installOverrides([
      {
        method: "element.dependencies",
        request: { direction: "IN", elementId: CATALOG },
        result: IN_PAGE,
      },
    ]);

    expect(
      await fakeRequest("element.dependencies", {
        analysisId: AN,
        cursor: "AgAAAAAAAAAAAQAAAAAAAAAC",
        direction: "IN",
        elementId: CATALOG,
        limit: 1,
      })
    ).toBe(IN_PAGE);
  });

  test("element.describe escolhe pelo elemento", async () => {
    installOverrides([
      {
        method: "element.describe",
        request: { elementId: CATALOG },
        result: OUT_PAGE,
      },
    ]);
    const error = await protocolErrorOf("element.describe", {
      analysisId: AN,
      elementId: STORE,
    });

    expect(
      await fakeRequest("element.describe", {
        analysisId: AN,
        elementId: CATALOG,
      })
    ).toBe(OUT_PAGE);
    expect([error.code, error.origin]).toStrictEqual([
      "INTERNAL_UNEXPECTED",
      "frontend",
    ]);
  });

  test.each([
    {
      field: "elementId",
      method: "element.describe",
      requests: [{ elementId: CATALOG }, { elementId: STORE }],
    },
    {
      field: "elementId",
      method: "element.dependencies",
      requests: [
        { direction: "IN", elementId: CATALOG },
        { direction: "IN", elementId: STORE },
      ],
    },
    {
      field: "direction",
      method: "element.dependencies",
      requests: [
        { direction: "IN", elementId: CATALOG },
        { direction: "OUT", elementId: CATALOG },
      ],
    },
    {
      field: "source",
      method: "element.dependencyEvidence",
      requests: [
        { source: CATALOG, target: ENTRY },
        { source: STORE, target: ENTRY },
      ],
    },
    {
      field: "target",
      method: "element.dependencyEvidence",
      requests: [
        { source: CATALOG, target: STORE },
        { source: CATALOG, target: ENTRY },
      ],
    },
  ] as const)(
    "$method: $field sozinho separa dois casos que dividem o resto",
    async ({ method, requests }) => {
      installOverrides(
        requests.map((request, index) => ({
          method,
          request,
          result: { marker: index },
        }))
      );

      const answers = await Promise.all(
        requests.map((request) =>
          fakeRequest(method, { analysisId: AN, ...request })
        )
      );

      expect(answers).toStrictEqual(
        requests.map((_request, index) => ({ marker: index }))
      );
    }
  );

  test("element.dependencyEvidence escolhe pelo par ordenado: o par trocado é outra aresta", async () => {
    installOverrides([
      {
        method: "element.dependencyEvidence",
        request: { source: CATALOG, target: STORE },
        result: EVIDENCE,
      },
    ]);
    const swapped = await protocolErrorOf("element.dependencyEvidence", {
      analysisId: AN,
      source: STORE,
      target: CATALOG,
    });

    expect(
      await fakeRequest("element.dependencyEvidence", {
        analysisId: AN,
        limit: 5,
        source: CATALOG,
        target: STORE,
      })
    ).toBe(EVIDENCE);
    expect(swapped.code).toBe("INTERNAL_UNEXPECTED");
  });
});

describe("o mundo do inspetor servido pelas fixtures", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  const DIRECTIONS = dependencyDirectionSchema.options;
  const MARKER = { marker: "override" };
  const PACKAGE_ID = PACKAGE_IDS[0] ?? "";
  const TYPE_ID = TYPE_IDS[0] ?? "";
  const OTHER_TYPE_ID = TYPE_IDS[1] ?? "";
  const GROUP_ID = `pfx:${detailOf(PACKAGE_ID).qualifiedName}`;
  const ELEMENT_METHODS = [
    "element.dependencies",
    "element.dependencyEvidence",
    "element.describe",
  ] as const;
  type ElementMethod = (typeof ELEMENT_METHODS)[number];

  const discriminantsOf = (
    method: ElementMethod,
    elementId: string
  ): Record<string, unknown> =>
    ({
      "element.dependencies": { direction: "IN", elementId },
      "element.dependencyEvidence": {
        source: elementId,
        target: OTHER_TYPE_ID,
      },
      "element.describe": { elementId },
    })[method];

  const acceptedIdOf = (method: ElementMethod): string =>
    method === "element.dependencyEvidence" ? TYPE_ID : PACKAGE_ID;

  test.each(DESCRIBED_IDS)(
    "element.describe de %s devolve o detalhe dele",
    async (elementId) => {
      expect(
        await fakeRequest("element.describe", { analysisId: AN, elementId })
      ).toStrictEqual(detailOf(elementId));
    }
  );

  test.each(
    DESCRIBED_IDS.flatMap((elementId) =>
      DIRECTIONS.map((direction) => [elementId, direction] as const)
    )
  )(
    "element.dependencies de %s em %s devolve a página daquela direção",
    async (elementId, direction) => {
      expect(
        await fakeRequest("element.dependencies", {
          analysisId: AN,
          direction,
          elementId,
        })
      ).toStrictEqual(dependencyPageOf(elementId, direction));
    }
  );

  test.each(EVIDENCE_PAIRS)(
    "element.dependencyEvidence de $source para $target devolve a evidência do par",
    async ({ source, target }) => {
      expect(
        await fakeRequest("element.dependencyEvidence", {
          analysisId: AN,
          source,
          target,
        })
      ).toStrictEqual({ evidence: evidenceOf(source, target) });
    }
  );

  test("as recusas gravadas do inspetor são as quatro de E20, cada uma com o seu código", () => {
    expect(
      ELEMENT_REFUSALS.map(({ error, stem }) => [stem, error.code])
    ).toStrictEqual([
      ["element.dependencies.foreign-cursor", "PROTO_INVALID_PAYLOAD"],
      ["element.dependencyEvidence.no-pair", "PROTO_INVALID_PAYLOAD"],
      ["element.describe.method", "PROTO_INVALID_PAYLOAD"],
      ["element.describe.unknown", "ELEMENT_NOT_FOUND"],
    ]);
  });

  const FAKE_ANSWER_OF_REFUSAL: Readonly<
    Record<string, readonly [code: string, origin: string]>
  > = {
    "element.dependencyEvidence.no-pair": ["INTERNAL_UNEXPECTED", "frontend"],
    "element.describe.method": ["PROTO_INVALID_PAYLOAD", "analyzer"],
    "element.describe.unknown": ["INTERNAL_UNEXPECTED", "frontend"],
  };

  test("a tabela das respostas falsas cobre toda recusa gravada sem cursor", () => {
    expect(
      ELEMENT_REFUSALS.filter(({ request }) => !("cursor" in request)).map(
        ({ stem }) => stem
      )
    ).toStrictEqual(Object.keys(FAKE_ANSWER_OF_REFUSAL).sort());
  });

  test.each(ELEMENT_REFUSALS.filter(({ request }) => !("cursor" in request)))(
    "$stem: o bridge falso nunca toca a resposta de erro gravada",
    async ({ method, request, stem }) => {
      const error = await protocolErrorOf(method, request);

      expect([error.code, error.origin, error.params]).toStrictEqual([
        ...(FAKE_ANSWER_OF_REFUSAL[stem] ?? []),
        { method },
      ]);
    }
  );

  test("um id de método no describe é recusado como o Java o recusa: o mesmo código e os mesmos params", async () => {
    const refusal = ELEMENT_REFUSALS.find(
      ({ stem }) => stem === "element.describe.method"
    );
    const error = await protocolErrorOf(
      refusal?.method ?? "",
      refusal?.request
    );

    expect([error.code, error.params]).toStrictEqual([
      refusal?.error.code,
      refusal?.error.params,
    ]);
  });

  test("o cursor estrangeiro não é recusado pelo bridge falso: cursor não escolhe caso", async () => {
    const refusal = ELEMENT_REFUSALS.find(({ request }) => "cursor" in request);
    const { direction, elementId } = (refusal?.request ?? {}) as {
      direction?: "IN" | "OUT";
      elementId?: string;
    };

    expect(
      await fakeRequest(refusal?.method ?? "", refusal?.request)
    ).toStrictEqual(dependencyPageOf(elementId ?? "", direction ?? "IN"));
  });

  test.each(ELEMENT_METHODS)(
    "%s recusa um id pfx: no Zod, antes de qualquer transcrição: nem um override o serve",
    async (method) => {
      vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
        { method, request: discriminantsOf(method, GROUP_ID), result: MARKER },
        {
          method,
          request: discriminantsOf(method, acceptedIdOf(method)),
          result: MARKER,
        },
      ] satisfies FakeTranscriptOverride[]);
      const error = await protocolErrorOf(method, {
        analysisId: AN,
        ...discriminantsOf(method, GROUP_ID),
      });

      expect([error.code, error.params]).toStrictEqual([
        "PROTO_INVALID_PAYLOAD",
        { method },
      ]);
      expect(
        await fakeRequest(method, {
          analysisId: AN,
          ...discriminantsOf(method, acceptedIdOf(method)),
        })
      ).toBe(MARKER);
    }
  );
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
