import { describe, expect, test } from "vitest";
import type { z } from "zod";
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
  return {
    ...base,
    methods: [{ ...methodOf(base), ...overrides }, ...base.methods.slice(1)],
  };
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

const LABEL_MAX = 512;
const MAX_ID_CHARS = 4096;
const EMOJI = String.fromCodePoint(0x1_f6_00);

describe("element.describe: o tipo do elemento decide o que é nulo", () => {
  test("em um pacote, o que só um tipo tem é nulo: typeKind, packageId, filePath, lines e ccP90", () => {
    const packageWith: [string, Record<string, unknown>][] = [
      ["typeKind", { ...packageDetail, typeKind: "CLASS" }],
      ["packageId", { ...packageDetail, packageId: "pkg:a" }],
      ["filePath", { ...packageDetail, filePath: "a/package-info.java" }],
      ["lines", { ...packageDetail, lines: { end: 1, start: 1 } }],
      [
        "ccP90",
        { ...packageDetail, metrics: { ...packageDetail.metrics, ccP90: 0 } },
      ],
    ];

    for (const [field, detail] of packageWith) {
      expect(elementDetailSchema.safeParse(detail).success, field).toBe(false);
    }
  });

  test("em um pacote, methods é [] e methodTotal é 0", () => {
    const withOneMethod = {
      ...packageDetail,
      methods: [methodOf(typeDetail())],
      methodTotal: 1,
    };

    expect(elementDetailSchema.safeParse(withOneMethod).success).toBe(false);
  });

  test("em um tipo, typeKind, packageId, filePath e ccP90 nunca são nulos; lines pode ser", () => {
    const typeWithout: [string, Record<string, unknown>][] = [
      ["typeKind", typeDetail({ typeKind: null })],
      ["packageId", typeDetail({ packageId: null })],
      ["filePath", typeDetail({ filePath: null })],
      ["ccP90", withMetrics({ ccP90: null })],
    ];

    for (const [field, detail] of typeWithout) {
      expect(elementDetailSchema.safeParse(detail).success, field).toBe(false);
    }
    expect(
      elementDetailSchema.safeParse(typeDetail({ lines: null })).success
    ).toBe(true);
  });

  test("o packageId de um tipo é um id de pacote", () => {
    expect(
      elementDetailSchema.safeParse(typeDetail({ packageId: "type:a.Outer" }))
        .success
    ).toBe(false);
  });

  test("kind é o do prefixo do id, e só PACKAGE ou TYPE se descrevem", () => {
    const mismatched: [string, Record<string, unknown>][] = [
      ["kind PACKAGE com id type:", typeDetail({ kind: "PACKAGE" })],
      ["kind METHOD", typeDetail({ kind: "METHOD" })],
      ["kind FILE", typeDetail({ kind: "FILE" })],
      ["kind fora do enum", typeDetail({ kind: "CLASS" })],
      ["id pkg: em um TYPE", typeDetail({ elementId: "pkg:a" })],
      ["id method:", typeDetail({ elementId: "method:a.Catalog#size()" })],
      ["id file:", typeDetail({ elementId: "file:a/Catalog.java" })],
      ["id pfx:", typeDetail({ elementId: "pfx:a" })],
      ["id sem prefixo", typeDetail({ elementId: "a.Catalog" })],
    ];

    for (const [label, detail] of mismatched) {
      expect(elementDetailSchema.safeParse(detail).success, label).toBe(false);
    }
  });

  test("um método ou um arquivo não se descreve, nem com o kind que o id diz", () => {
    expect(
      elementDetailSchema.safeParse(
        typeDetail({ elementId: "method:a.Catalog#size()", kind: "METHOD" })
      ).success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(
        typeDetail({ elementId: "file:a/Catalog.java", kind: "FILE" })
      ).success
    ).toBe(false);
  });

  test("o id descrito tem o teto de todo id: 4 096", () => {
    const atCeiling = `type:${"a".repeat(MAX_ID_CHARS - "type:".length)}`;

    expect(
      elementDetailSchema.safeParse(typeDetail({ elementId: atCeiling }))
        .success
    ).toBe(true);
    expect(
      elementDetailSchema.safeParse(typeDetail({ elementId: `${atCeiling}a` }))
        .success
    ).toBe(false);
  });

  test("methods tem min(methodTotal, 1 000) linhas: abaixo do teto não há corte", () => {
    expect(
      elementDetailSchema.safeParse(typeDetail({ methodTotal: 3 })).success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(typeDetail({ methodTotal: 1 })).success
    ).toBe(false);
  });

  test("um modificador é uma palavra: nunca vazio, nunca acima de 32", () => {
    expect(
      elementDetailSchema.safeParse(typeDetail({ modifiers: [""] })).success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(typeDetail({ modifiers: ["x".repeat(33)] }))
        .success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(typeDetail({ modifiers: ["x".repeat(32)] }))
        .success
    ).toBe(true);
  });

  test("rótulo e assinatura cortam em 512 code points, e não em unidades UTF-16", () => {
    expect(
      elementDetailSchema.safeParse(
        typeDetail({ label: EMOJI.repeat(LABEL_MAX) })
      ).success
    ).toBe(true);
    expect(
      elementDetailSchema.safeParse(
        typeDetail({ label: "x".repeat(LABEL_MAX + 1) })
      ).success
    ).toBe(false);
    expect(
      elementDetailSchema.safeParse(
        withMethod({ signature: "x".repeat(LABEL_MAX + 1) })
      ).success
    ).toBe(false);
  });

  test("typeKind é do vocabulário do Java", () => {
    expect(
      elementDetailSchema.safeParse(typeDetail({ typeKind: "WIDGET" })).success
    ).toBe(false);
  });

  test("o arquivo é relativo à raiz: uma barra inicial reprova", () => {
    expect(
      elementDetailSchema.safeParse(
        typeDetail({ filePath: "/Users/x/repo/core/A.java" })
      ).success
    ).toBe(false);
  });
});

const methodRef = (overrides: Record<string, unknown> = {}) => ({
  ...evidenceMethod,
  ...overrides,
});

describe("element.dependencyEvidence: a ocorrência afirma o que o Java afirma", () => {
  test("só uma ocorrência CALLS nomeia o método chamado", () => {
    const callee = methodRef({
      elementId: "method:a.Store#put(a.Entry)",
      ownerId: "type:a.Store",
      ownerLabel: "Store",
      signature: "put(Entry)",
    });

    expect(
      relationshipEvidenceSchema.safeParse(evidence({ toMethod: callee }))
        .success
    ).toBe(true);
    expect(
      relationshipEvidenceSchema.safeParse(
        evidence({ toMethod: callee, type: "FIELD_TYPE" })
      ).success
    ).toBe(false);
  });

  test("o tipo é uma aresta entre tipos do vocabulário: MODULE_DEPENDENCY e o desconhecido reprovam", () => {
    for (const type of ["MODULE_DEPENDENCY", "USES"]) {
      expect(
        relationshipEvidenceSchema.safeParse(evidence({ type })).success,
        type
      ).toBe(false);
    }
  });

  test("a linha é 1-based, ou nula", () => {
    expect(
      relationshipEvidenceSchema.safeParse(evidence({ line: 0 })).success
    ).toBe(false);
    expect(
      relationshipEvidenceSchema.safeParse(evidence({ line: null })).success
    ).toBe(true);
  });

  test("a qualidade é do vocabulário", () => {
    expect(
      relationshipEvidenceSchema.safeParse(evidence({ resolution: "UNKNOWN" }))
        .success
    ).toBe(false);
  });

  test("o dono de um método é um tipo", () => {
    for (const ownerId of [
      "pkg:a",
      "method:a.Catalog#add(a.Entry)",
      "a.Catalog",
    ]) {
      expect(
        relationshipEvidenceSchema.safeParse(
          evidence({ fromMethod: methodRef({ ownerId }) })
        ).success,
        ownerId
      ).toBe(false);
    }
  });

  test("assinatura e rótulo do dono cortam em 512 code points", () => {
    for (const field of ["ownerLabel", "signature"]) {
      expect(
        relationshipEvidenceSchema.safeParse(
          evidence({
            fromMethod: methodRef({ [field]: "x".repeat(LABEL_MAX + 1) }),
          })
        ).success,
        field
      ).toBe(false);
    }
  });

  test("o arquivo é relativo à raiz: uma barra inicial reprova", () => {
    expect(
      relationshipEvidenceSchema.safeParse(
        evidence({ filePath: "/Users/x/repo/core/A.java" })
      ).success
    ).toBe(false);
  });

  test("o resultado nunca é vazio: um par sem linha é uma recusa, não []", () => {
    expect(
      dependencyEvidenceResultSchema.safeParse({ evidence: [] }).success
    ).toBe(false);
  });
});

const externalEntry = (overrides: Record<string, unknown> = {}) =>
  entry({
    elementId: "type:Foo",
    isExternal: true,
    label: "Foo",
    packageId: null,
    qualifiedName: "Foo",
    resolution: "UNRESOLVED_EXTERNAL",
    ...overrides,
  });

const packageEntry = (overrides: Record<string, unknown> = {}) =>
  entry({
    elementId: "pkg:b",
    kind: "PACKAGE",
    label: "b",
    packageId: null,
    qualifiedName: "b",
    ...overrides,
  });

const entryAccepted = (row: Record<string, unknown>): boolean =>
  dependencyPageSchema.safeParse(page({ items: [row] })).success;

describe("element.dependencies: a linha afirma o que o Java afirma da ponta", () => {
  test("os três casos de base passam: interno, externo e de pacote", () => {
    expect(entryAccepted(entry())).toBe(true);
    expect(entryAccepted(externalEntry())).toBe(true);
    expect(entryAccepted(packageEntry())).toBe(true);
  });

  test("ambiguous só em uma ponta externa UNRESOLVED_EXTERNAL", () => {
    expect(entryAccepted(externalEntry({ ambiguous: true }))).toBe(true);
    expect(entryAccepted(entry({ ambiguous: true }))).toBe(false);
    expect(
      entryAccepted(externalEntry({ ambiguous: true, resolution: "INFERRED" }))
    ).toBe(false);
  });

  test("uma ponta externa é um tipo sem pacote", () => {
    expect(entryAccepted(externalEntry({ packageId: "pkg:java.lang" }))).toBe(
      false
    );
    expect(entryAccepted(packageEntry({ isExternal: true }))).toBe(false);
  });

  test("uma ponta interna nunca é UNRESOLVED_EXTERNAL", () => {
    expect(entryAccepted(entry({ resolution: "UNRESOLVED_EXTERNAL" }))).toBe(
      false
    );
    expect(
      entryAccepted(packageEntry({ resolution: "UNRESOLVED_EXTERNAL" }))
    ).toBe(false);
  });

  test("o pacote de uma ponta interna: um tipo tem o dele, um pacote não tem", () => {
    expect(entryAccepted(entry({ packageId: null }))).toBe(false);
    expect(entryAccepted(entry({ packageId: "type:a.Outer" }))).toBe(false);
    expect(entryAccepted(packageEntry({ packageId: "pkg:a" }))).toBe(false);
  });

  test("kind é o do prefixo do id, e uma ponta é só pacote ou tipo", () => {
    const mismatched: [string, Record<string, unknown>][] = [
      ["kind PACKAGE com id type:", entry({ kind: "PACKAGE" })],
      ["kind METHOD", entry({ kind: "METHOD" })],
      ["kind fora do enum", entry({ kind: "CLASS" })],
      ["id pkg: em um TYPE", entry({ elementId: "pkg:a" })],
      ["id pfx:", entry({ elementId: "pfx:a" })],
      ["id method:", entry({ elementId: "method:a.Store#put(a.Entry)" })],
      ["id file:", entry({ elementId: "file:a/Store.java" })],
    ];

    for (const [label, row] of mismatched) {
      expect(entryAccepted(row), label).toBe(false);
    }
  });

  test("um método ou um arquivo não é ponta, nem com o kind que o id diz", () => {
    expect(
      entryAccepted(
        entry({ elementId: "method:a.Store#put(a.Entry)", kind: "METHOD" })
      )
    ).toBe(false);
    expect(
      entryAccepted(entry({ elementId: "file:a/Store.java", kind: "FILE" }))
    ).toBe(false);
  });

  test("a outra ponta tem o teto de todo id: 4 096", () => {
    const atCeiling = `type:${"a".repeat(MAX_ID_CHARS - "type:".length)}`;

    expect(entryAccepted(entry({ elementId: atCeiling }))).toBe(true);
    expect(entryAccepted(entry({ elementId: `${atCeiling}a` }))).toBe(false);
  });

  test("types tem ao menos um tipo de aresta entre tipos, cada um com 1 ou mais", () => {
    for (const types of [{}, { MODULE_DEPENDENCY: 1 }, { CALLS: 0 }]) {
      expect(entryAccepted(entry({ types })), JSON.stringify(types)).toBe(
        false
      );
    }
  });

  test("o edgeId tem a forma do FNV do par", () => {
    for (const edgeId of ["e_AF63DC4C8601EC8C", "edge-1"]) {
      expect(entryAccepted(entry({ edgeId })), edgeId).toBe(false);
    }
  });

  test("o rótulo corta em 512 code points", () => {
    expect(entryAccepted(entry({ label: EMOJI.repeat(LABEL_MAX) }))).toBe(true);
    expect(entryAccepted(entry({ label: "x".repeat(LABEL_MAX + 1) }))).toBe(
      false
    );
  });

  test("a qualidade é do vocabulário", () => {
    expect(entryAccepted(entry({ resolution: "UNKNOWN" }))).toBe(false);
  });
});

const withEachKeyDropped = (
  value: Record<string, unknown>
): [string, Record<string, unknown>][] =>
  Object.keys(value).map((key) => {
    const { [key]: _dropped, ...rest } = value;
    return [key, rest];
  });

describe("nulo, nunca ausente, em toda profundidade", () => {
  test("ElementDetail: toda chave aninhada é obrigatória", () => {
    const base = typeDetail();
    const dropped: [string, Record<string, unknown>][] = [
      ...withEachKeyDropped(base.graph).map(
        ([key, graph]): [string, Record<string, unknown>] => [
          `graph.${key}`,
          { ...base, graph },
        ]
      ),
      ...withEachKeyDropped(base.metrics).map(
        ([key, metrics]): [string, Record<string, unknown>] => [
          `metrics.${key}`,
          { ...base, metrics },
        ]
      ),
      ...withEachKeyDropped(methodOf(base)).map(
        ([key, method]): [string, Record<string, unknown>] => [
          `methods[].${key}`,
          { ...base, methods: [method, base.methods[1]] },
        ]
      ),
    ];

    for (const [at, detail] of dropped) {
      expect(elementDetailSchema.safeParse(detail).success, at).toBe(false);
    }
  });

  test("RelationshipEvidence: toda chave, e as da referência de método, é obrigatória", () => {
    const dropped = [
      ...withEachKeyDropped(evidence()),
      ...withEachKeyDropped(evidenceMethod).map(
        ([key, fromMethod]): [string, Record<string, unknown>] => [
          `fromMethod.${key}`,
          evidence({ fromMethod }),
        ]
      ),
    ];

    for (const [at, row] of dropped) {
      expect(relationshipEvidenceSchema.safeParse(row).success, at).toBe(false);
    }
  });

  test("DependencyPage: toda chave da linha e dos totais é obrigatória", () => {
    for (const [key, row] of withEachKeyDropped(entry())) {
      expect(entryAccepted(row), `items[].${key}`).toBe(false);
    }
    for (const [key, totals] of withEachKeyDropped(page().totals)) {
      expect(
        dependencyPageSchema.safeParse(page({ totals })).success,
        `totals.${key}`
      ).toBe(false);
    }
  });
});

describe("os pedidos de element.* são tão estreitos quanto o Java", () => {
  const evidenceParams = METHODS["element.dependencyEvidence"].params;
  const dependencyParams = METHODS["element.dependencies"].params;
  const describeParams = METHODS["element.describe"].params;
  const describeRequest = { analysisId: AN, elementId: "type:a.Catalog" };
  const dependencyRequest = {
    analysisId: AN,
    direction: "IN",
    elementId: "type:a.Catalog",
  };
  const pair = {
    analysisId: AN,
    source: "type:a.Catalog",
    target: "type:a.Store",
  };

  test("describe e dependencies pedem um pacote ou um tipo: um método ou um arquivo reprova", () => {
    for (const elementId of ["pkg:a", "type:a.Catalog"]) {
      expect(
        describeParams.safeParse({ ...describeRequest, elementId }).success,
        elementId
      ).toBe(true);
      expect(
        dependencyParams.safeParse({ ...dependencyRequest, elementId }).success,
        elementId
      ).toBe(true);
    }
    for (const elementId of [
      "method:a.Catalog#size()",
      "file:a/Catalog.java",
    ]) {
      expect(
        describeParams.safeParse({ ...describeRequest, elementId }).success,
        elementId
      ).toBe(false);
      expect(
        dependencyParams.safeParse({ ...dependencyRequest, elementId }).success,
        elementId
      ).toBe(false);
    }
  });

  test("as duas pontas da evidência são tipos: pacote, método, arquivo e grupo reprovam", () => {
    for (const end of [
      "pkg:a",
      "method:a.Catalog#size()",
      "file:a/Catalog.java",
      "pfx:a",
    ]) {
      expect(
        evidenceParams.safeParse({ ...pair, source: end }).success,
        `source ${end}`
      ).toBe(false);
      expect(
        evidenceParams.safeParse({ ...pair, target: end }).success,
        `target ${end}`
      ).toBe(false);
    }
  });

  test("um alvo externo é um tipo como outro qualquer", () => {
    expect(
      evidenceParams.safeParse({ ...pair, target: "type:java.lang.String" })
        .success
    ).toBe(true);
  });

  test("as pontas da evidência são diferentes: o Java não guarda par de um tipo consigo", () => {
    expect(
      evidenceParams.safeParse({ ...pair, target: pair.source }).success
    ).toBe(false);
  });

  test("todo campo obrigatório é obrigatório, e um nulo explícito não o supre", () => {
    const required: [string, z.ZodType, Record<string, unknown>][] = [
      ["element.describe", describeParams, describeRequest],
      ["element.dependencies", dependencyParams, dependencyRequest],
      ["element.dependencyEvidence", evidenceParams, pair],
    ];

    for (const [method, schema, request] of required) {
      for (const [key, rest] of withEachKeyDropped(request)) {
        expect(schema.safeParse(rest).success, `${method} sem ${key}`).toBe(
          false
        );
        expect(
          schema.safeParse({ ...rest, [key]: null }).success,
          `${method} com ${key} nulo`
        ).toBe(false);
      }
    }
  });

  test("a direção é do vocabulário", () => {
    expect(
      dependencyParams.safeParse({ ...dependencyRequest, direction: "BOTH" })
        .success
    ).toBe(false);
  });
});
