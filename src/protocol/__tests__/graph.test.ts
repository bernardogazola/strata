import { describe, expect, test } from "vitest";
import {
  dependencyEvidenceResultSchema,
  dependencyPageSchema,
  elementDetailSchema,
  graphEdgeSchema,
  graphNodeSchema,
  graphSliceSchema,
  relationshipEvidenceSchema,
} from "../graph";
import { METHODS } from "../registry";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const groupNode = (overrides: Record<string, unknown> = {}) => ({
  fanIn: 0,
  fanOut: 1,
  id: "pfx:a",
  kind: "PACKAGE_PREFIX",
  label: "a.",
  loc: 1204,
  pagerank: 0.5,
  resolutionMix: { INFERRED: 0, RESOLVED: 301, UNRESOLVED_EXTERNAL: 0 },
  typeCount: 301,
  ...overrides,
});

const groupEdge = {
  id: "e_af63dc4c8601ec8c",
  resolution: "RESOLVED",
  source: "pfx:a",
  target: "pfx:b",
  types: { FIELD_TYPE: 1 },
  weight: 1,
};

const slice = (parentId: unknown) => ({
  aggregated: false,
  budget: { edges: 5000, nodes: 1000 },
  edges: [],
  level: "TYPE",
  nodes: [],
  parentId,
  totals: { edges: 0, nodes: 0 },
  truncated: false,
});

const sliceParams = METHODS["graph.slice"].params;

describe("o nó de grupo da fatia agregada", () => {
  test("um PACKAGE_PREFIX de id pfx: é um nó legal", () => {
    expect(graphNodeSchema.safeParse(groupNode()).success).toBe(true);
  });

  test("o tipo do nó é o vocabulário do grafo, e não o de elemento", () => {
    for (const kind of ["METHOD", "FILE"]) {
      expect(graphNodeSchema.safeParse(groupNode({ kind })).success, kind).toBe(
        false
      );
    }
  });

  test("um id de grupo malformado reprova no nó e na aresta", () => {
    expect(graphNodeSchema.safeParse(groupNode({ id: "pfx:" })).success).toBe(
      false
    );
    expect(
      graphEdgeSchema.safeParse({ ...groupEdge, target: "pfx:a." }).success
    ).toBe(false);
  });

  test("as pontas de uma aresta aceitam ids de grupo", () => {
    expect(graphEdgeSchema.safeParse(groupEdge).success).toBe(true);
  });

  test("a fatia nunca tem um grupo como pai", () => {
    expect(graphSliceSchema.safeParse(slice("pkg:a")).success).toBe(true);
    expect(graphSliceSchema.safeParse(slice("pfx:a")).success).toBe(false);
  });

  test("a busca continua pedindo só tipos de elemento", () => {
    const params = {
      analysisId: AN,
      kinds: ["PACKAGE_PREFIX"],
      query: "a",
    };

    expect(METHODS["graph.search"].params.safeParse(params).success).toBe(
      false
    );
  });
});

describe("os parâmetros de graph.slice", () => {
  test("edgeTypes e minResolution saíram do fio", () => {
    const base = { analysisId: AN, level: "PACKAGE" };

    expect(sliceParams.safeParse(base).success).toBe(true);
    expect(
      sliceParams.safeParse({ ...base, edgeTypes: ["CALLS"] }).success
    ).toBe(false);
    expect(
      sliceParams.safeParse({ ...base, minResolution: "RESOLVED" }).success
    ).toBe(false);
  });

  test("limit vai de 1 ao maior teto de nós, 1000, e não há degrau acima", () => {
    const withLimit = (limit: number) => ({
      analysisId: AN,
      level: "TYPE",
      limit,
      parentId: "pkg:a",
    });

    expect(sliceParams.safeParse(withLimit(1)).success).toBe(true);
    expect(sliceParams.safeParse(withLimit(1000)).success).toBe(true);
    expect(sliceParams.safeParse(withLimit(1001)).success).toBe(false);
    expect(sliceParams.safeParse(withLimit(0)).success).toBe(false);
  });
});

const typeDetail = (overrides: Record<string, unknown> = {}) => ({
  elementId: "type:a.Catalog",
  filePath: "core/src/main/java/a/Catalog.java",
  graph: {
    cycle: null,
    fanIn: 2,
    fanOut: 3,
    pagerank: 0.125,
    resolutionMix: { INFERRED: 1, RESOLVED: 4, UNRESOLVED_EXTERNAL: 0 },
  },
  isTest: false,
  kind: "TYPE",
  label: "Catalog",
  lines: { end: 30, start: 5 },
  methods: [
    {
      cc: 2,
      elementId: "method:a.Catalog#add(a.Entry)",
      lines: { end: 12, start: 10 },
      signature: "add(Entry)",
    },
    {
      cc: null,
      elementId: "method:a.Catalog#size()",
      lines: null,
      signature: "size()",
    },
  ],
  methodTotal: 2,
  metrics: {
    ccMax: 2,
    ccMean: 1.5,
    ccP90: 2,
    ccSum: 3,
    loc: 26,
    unitCount: 2,
  },
  modifiers: ["public", "final"],
  packageId: "pkg:a",
  qualifiedName: "a.Catalog",
  typeKind: "CLASS",
  ...overrides,
});

const packageDetail = {
  elementId: "pkg:a.spi",
  filePath: null,
  graph: {
    cycle: { id: 1, size: 2 },
    fanIn: 1,
    fanOut: 1,
    pagerank: 0.5,
    resolutionMix: { INFERRED: 0, RESOLVED: 2, UNRESOLVED_EXTERNAL: 0 },
  },
  isTest: false,
  kind: "PACKAGE",
  label: "a.spi",
  lines: null,
  methods: [],
  methodTotal: 0,
  metrics: { ccMax: 0, ccMean: 0, ccP90: null, ccSum: 0, loc: 9, unitCount: 0 },
  modifiers: [],
  packageId: null,
  qualifiedName: "a.spi",
  typeKind: null,
};

const methodOf = (detail: ReturnType<typeof typeDetail>) =>
  detail.methods[0] as Record<string, unknown>;

const withMethod = (overrides: Record<string, unknown>) => {
  const base = typeDetail();
  return { ...base, methods: [{ ...methodOf(base), ...overrides }] };
};

const withGraph = (overrides: Record<string, unknown>) => {
  const base = typeDetail();
  return { ...base, graph: { ...base.graph, ...overrides } };
};

const withMetrics = (overrides: Record<string, unknown>) => {
  const base = typeDetail();
  return { ...base, metrics: { ...base.metrics, ...overrides } };
};

describe("element.describe: ElementDetail", () => {
  test("um tipo e um pacote do contrato passam", () => {
    expect(elementDetailSchema.safeParse(typeDetail()).success).toBe(true);
    expect(elementDetailSchema.safeParse(packageDetail).success).toBe(true);
  });

  test("os campos que saíram do fio reprovam: um produtor velho falha na ponte", () => {
    const stale: [string, Record<string, unknown>][] = [
      ["simpleName", typeDetail({ simpleName: "Catalog" })],
      ["isEntryPoint", withGraph({ isEntryPoint: false })],
      ["fanOutExternal", withGraph({ fanOutExternal: 1 })],
      ["sccId", withGraph({ sccId: null })],
      ["startLine", typeDetail({ startLine: 5 })],
      ["sourceLines", withMetrics({ sourceLines: 26 })],
      ["commentLines", withMetrics({ commentLines: 0 })],
      ["methodCount", withMetrics({ methodCount: 2 })],
      ["fieldCount", withMetrics({ fieldCount: 1 })],
      ["nestingMax", withMetrics({ nestingMax: 1 })],
    ];
    for (const [field, detail] of stale) {
      expect(elementDetailSchema.safeParse(detail).success, field).toBe(false);
    }
  });

  test("todo campo é obrigatório: o que um pacote não tem é nulo, nunca ausente", () => {
    for (const field of Object.keys(packageDetail)) {
      const { [field]: _dropped, ...rest } = packageDetail as Record<
        string,
        unknown
      >;
      expect(elementDetailSchema.safeParse(rest).success, field).toBe(false);
    }
  });

  test("o cc de um método é nulo sem unidade, e nunca 0 no lugar do nulo", () => {
    expect(
      elementDetailSchema.safeParse(withMethod({ cc: null })).success
    ).toBe(true);
    expect(elementDetailSchema.safeParse(withMethod({ cc: 0 })).success).toBe(
      false
    );
  });

  test("um método da lista só pode ter id de método", () => {
    expect(
      elementDetailSchema.safeParse(withMethod({ elementId: "type:a.Catalog" }))
        .success
    ).toBe(false);
  });

  test("linhas são 1-based e não terminam antes de começar", () => {
    expect(
      elementDetailSchema.safeParse(typeDetail({ lines: { end: 5, start: 5 } }))
        .success
    ).toBe(true);
    expect(
      elementDetailSchema.safeParse(typeDetail({ lines: { end: 4, start: 5 } }))
        .success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(typeDetail({ lines: { end: 3, start: 0 } }))
        .success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(withMethod({ lines: { end: 1 } })).success
    ).toBe(false);
  });

  test("fora de ciclo é null, e um ciclo tem pelo menos dois nós", () => {
    expect(
      elementDetailSchema.safeParse(withGraph({ cycle: { id: 1, size: 1 } }))
        .success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(withGraph({ cycle: { id: 0, size: 2 } }))
        .success
    ).toBe(false);
  });

  test("o pagerank nunca é 0", () => {
    expect(
      elementDetailSchema.safeParse(withGraph({ pagerank: 0 })).success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(withGraph({ pagerank: 1 })).success
    ).toBe(true);
  });

  test("ccP90 é o valor de uma unidade, e por isso inteiro; ccMean não", () => {
    expect(
      elementDetailSchema.safeParse(withMetrics({ ccP90: 2.5 })).success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(withMetrics({ ccMean: 2.3 })).success
    ).toBe(true);
  });

  test("a lista de métodos para em 1 000, e methodTotal diz quantos há", () => {
    const methods = (count: number) =>
      Array.from({ length: count }, (_, index) => ({
        ...methodOf(typeDetail()),
        elementId: `method:a.Catalog#m${index}()`,
      }));
    const capped = typeDetail({ methods: methods(1000), methodTotal: 1001 });

    expect(elementDetailSchema.safeParse(capped).success).toBe(true);
    expect(
      elementDetailSchema.safeParse({ ...capped, methods: methods(1001) })
        .success
    ).toBe(false);
  });
});

const evidenceMethod = {
  elementId: "method:a.Catalog#add(a.Entry)",
  ownerId: "type:a.Catalog",
  ownerLabel: "Catalog",
  signature: "add(Entry)",
};

const evidence = (overrides: Record<string, unknown> = {}) => ({
  filePath: "core/src/main/java/a/Catalog.java",
  fromMethod: evidenceMethod,
  line: 11,
  resolution: "RESOLVED",
  toMethod: null,
  type: "CALLS",
  ...overrides,
});

describe("element.dependencyEvidence", () => {
  test("uma ocorrência com o tipo e os métodos com dono passa", () => {
    expect(relationshipEvidenceSchema.safeParse(evidence()).success).toBe(true);
    expect(
      relationshipEvidenceSchema.safeParse(
        evidence({ fromMethod: null, line: 3, type: "IMPORTS" })
      ).success
    ).toBe(true);
  });

  test("sem type a ocorrência reprova: o tipo é o que torna um toMethod nulo legível", () => {
    const { type: _dropped, ...withoutType } = evidence();

    expect(relationshipEvidenceSchema.safeParse(withoutType).success).toBe(
      false
    );
  });

  test("um método da evidência é uma referência com dono, e não um id solto", () => {
    expect(
      relationshipEvidenceSchema.safeParse(
        evidence({ toMethod: "method:a.Store#put(a.Entry)" })
      ).success
    ).toBe(false);
    expect(
      relationshipEvidenceSchema.safeParse(
        evidence({
          toMethod: { ...evidenceMethod, elementId: "type:a.Store" },
        })
      ).success
    ).toBe(false);
  });

  test("o resultado para em 50 ocorrências e não é página", () => {
    const rows = (count: number) =>
      Array.from({ length: count }, () => evidence());

    expect(
      dependencyEvidenceResultSchema.safeParse({ evidence: rows(50) }).success
    ).toBe(true);
    expect(
      dependencyEvidenceResultSchema.safeParse({ evidence: rows(51) }).success
    ).toBe(false);
    expect(
      dependencyEvidenceResultSchema.safeParse({
        evidence: rows(1),
        nextCursor: null,
      }).success
    ).toBe(false);
  });
});

const entry = (overrides: Record<string, unknown> = {}) => ({
  ambiguous: false,
  edgeId: "e_af63dc4c8601ec8c",
  elementId: "type:a.Store",
  isExternal: false,
  kind: "TYPE",
  label: "Store",
  packageId: "pkg:a",
  qualifiedName: "a.Store",
  resolution: "RESOLVED",
  types: { CALLS: 1, FIELD_TYPE: 1 },
  weight: 5,
  ...overrides,
});

const page = (overrides: Record<string, unknown> = {}) => ({
  items: [entry()],
  nextCursor: null,
  totals: { in: 2, out: 1, outExternal: 1 },
  ...overrides,
});

describe("element.dependencies: DependencyPage", () => {
  test("uma página de tipo, com um alvo externo depois do interno, passa", () => {
    const external = entry({
      elementId: "type:java.lang.String",
      isExternal: true,
      label: "java.lang.String",
      packageId: null,
      qualifiedName: "java.lang.String",
      types: { PARAMETER_TYPE: 1 },
      weight: 1,
    });

    expect(
      dependencyPageSchema.safeParse(page({ items: [entry(), external] }))
        .success
    ).toBe(true);
  });

  test("em uma página de pacote, outExternal é nulo", () => {
    const packagePage = page({
      items: [entry({ elementId: "pkg:b", kind: "PACKAGE", packageId: null })],
      totals: { in: 0, out: 1, outExternal: null },
    });

    expect(dependencyPageSchema.safeParse(packagePage).success).toBe(true);
  });

  test("truncated saiu: ao lado de nextCursor seria um segundo jeito de dizer há mais", () => {
    expect(
      dependencyPageSchema.safeParse(page({ truncated: false })).success
    ).toBe(false);
  });

  test("totals tem as três contagens, todas presentes", () => {
    expect(
      dependencyPageSchema.safeParse(page({ totals: { in: 2, out: 1 } }))
        .success
    ).toBe(false);
    expect(
      dependencyPageSchema.safeParse(
        page({ totals: { IN: 2, OUT: 1, outExternal: 1 } })
      ).success
    ).toBe(false);
  });

  test("toda linha diz se a ponta é externa e se o par é ambíguo", () => {
    for (const field of [
      "ambiguous",
      "isExternal",
      "packageId",
      "qualifiedName",
    ] as const) {
      const { [field]: _dropped, ...rest } = entry();

      expect(
        dependencyPageSchema.safeParse(page({ items: [rest] })).success,
        field
      ).toBe(false);
    }
  });

  test("o peso de uma linha nunca é 0", () => {
    expect(
      dependencyPageSchema.safeParse(page({ items: [entry({ weight: 0 })] }))
        .success
    ).toBe(false);
  });

  test("a página para em 2 000 linhas", () => {
    const items = (count: number) =>
      Array.from({ length: count }, () => entry());

    expect(
      dependencyPageSchema.safeParse(page({ items: items(2000) })).success
    ).toBe(true);
    expect(
      dependencyPageSchema.safeParse(page({ items: items(2001) })).success
    ).toBe(false);
  });
});

describe("os pedidos de element.*", () => {
  const evidenceParams = METHODS["element.dependencyEvidence"].params;
  const dependencyParams = METHODS["element.dependencies"].params;
  const describeParams = METHODS["element.describe"].params;
  const pair = {
    analysisId: AN,
    source: "type:a.Catalog",
    target: "type:java.lang.String",
  };

  test("a evidência se pede pelo par, e não pelo edgeId", () => {
    expect(evidenceParams.safeParse(pair).success).toBe(true);
    expect(
      evidenceParams.safeParse({ analysisId: AN, edgeId: "e_af63dc4c8601ec8c" })
        .success
    ).toBe(false);
    expect(
      evidenceParams.safeParse({ ...pair, edgeId: "e_af63dc4c8601ec8c" })
        .success
    ).toBe(false);
  });

  test("o limit da evidência vai de 1 a 50", () => {
    expect(evidenceParams.safeParse({ ...pair, limit: 50 }).success).toBe(true);
    expect(evidenceParams.safeParse({ ...pair, limit: 51 }).success).toBe(
      false
    );
    expect(evidenceParams.safeParse({ ...pair, limit: 0 }).success).toBe(false);
  });

  test("um grupo de pacotes nunca é argumento de um método de elemento", () => {
    expect(evidenceParams.safeParse({ ...pair, source: "pfx:a" }).success).toBe(
      false
    );
    expect(
      describeParams.safeParse({ analysisId: AN, elementId: "pfx:a" }).success
    ).toBe(false);
    expect(
      dependencyParams.safeParse({
        analysisId: AN,
        direction: "IN",
        elementId: "pfx:a",
      }).success
    ).toBe(false);
  });

  test("o limit das dependências vai de 1 a 2 000", () => {
    const request = (limit: number) => ({
      analysisId: AN,
      direction: "OUT",
      elementId: "type:a.Catalog",
      limit,
    });

    expect(dependencyParams.safeParse(request(2000)).success).toBe(true);
    expect(dependencyParams.safeParse(request(2001)).success).toBe(false);
    expect(dependencyParams.safeParse(request(0)).success).toBe(false);
  });
});
