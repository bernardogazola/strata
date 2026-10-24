import { describe, expect, test } from "vitest";
import { graphEdgeSchema, graphNodeSchema, graphSliceSchema } from "../graph";
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
