import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { beforeAll, describe, expect, test } from "vitest";
import { t } from "@/i18n/messages";
import {
  GRAPH_SLICE_BUDGETS,
  type GraphSlice,
  graphEdgeSchema,
  graphNodeSchema,
  graphSliceSchema,
} from "@/protocol/graph";
import {
  GRAPH_LABEL_MAX_CHARS,
  GraphSliceViolationError,
  toGraphElements,
  toGraphView,
} from "../adapter";

const FIXTURES = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../../protocol/fixtures"
);

const readSliceFixture = async (stem: string): Promise<GraphSlice> => {
  const text = await readFile(path.join(FIXTURES, `${stem}.jsonl`), "utf8");
  const response = text
    .split("\n")
    .filter((line) => line.length > 0)
    .map((line) => JSON.parse(line) as { kind: string; payload: unknown })
    .find((frame) => frame.kind === "response");
  return graphSliceSchema.parse(response?.payload);
};

let packageSlice: GraphSlice;
let typeSlice: GraphSlice;

beforeAll(async () => {
  packageSlice = await readSliceFixture("graph.slice.package");
  typeSlice = await readSliceFixture("graph.slice.type");
});

const packageSliceOf = (count: number): GraphSlice => {
  const nodes = Array.from({ length: count }, (_, index) => ({
    fanIn: 0,
    fanOut: 0,
    id: `pkg:synthetic.p${String(index).padStart(4, "0")}`,
    kind: "PACKAGE" as const,
    label: `p${index}`,
    loc: 100,
    pagerank: 1 / count,
    resolutionMix: { INFERRED: 0, RESOLVED: 1, UNRESOLVED_EXTERNAL: 0 },
    typeCount: 1,
  }));
  return {
    aggregated: false,
    budget: { ...GRAPH_SLICE_BUDGETS.PACKAGE },
    edges: [],
    level: "PACKAGE",
    nodes,
    parentId: null,
    totals: { edges: 0, nodes: count },
    truncated: false,
  };
};

const edgesOf = (slice: GraphSlice, count: number): GraphSlice["edges"] => {
  const [from, to] = slice.nodes;
  return Array.from({ length: count }, (_, index) => ({
    id: `e_${String(index).padStart(16, "0")}`,
    resolution: "RESOLVED" as const,
    source: from?.id ?? "",
    target: to?.id ?? "",
    types: { IMPORTS: 1 },
    weight: 1,
  }));
};

const violationOf = (slice: GraphSlice): string | undefined => {
  try {
    toGraphElements(slice);
  } catch (error) {
    if (error instanceof GraphSliceViolationError) {
      return error.violation;
    }
    throw error;
  }
};

const RLO = String.fromCodePoint(0x20_2e);
const PDF = String.fromCodePoint(0x20_2c);

const MENTIONS_CLIENT_TEXT = /segredo/;

const NODE_KEYS = Object.keys(graphNodeSchema.shape).sort();
const EDGE_KEYS = Object.keys(graphEdgeSchema.shape).sort();

describe("toGraphElements: nunca derruba um elemento", () => {
  test("cada nó e cada aresta da fatia de pacotes viram exatamente um elemento", () => {
    const elements = toGraphElements(packageSlice);

    expect(elements.nodes).toHaveLength(packageSlice.nodes.length);
    expect(elements.edges).toHaveLength(packageSlice.edges.length);
  });

  test("a ordem da fatia é preservada, porque o concentric desempata pela ordem de inserção", () => {
    const elements = toGraphElements(typeSlice);

    expect(elements.nodes.map((node) => node.data.id)).toEqual(
      typeSlice.nodes.map((node) => node.id)
    );
    expect(elements.edges.map((edge) => edge.data.id)).toEqual(
      typeSlice.edges.map((edge) => edge.id)
    );
  });

  test("nós e arestas declaram o grupo, e uma aresta liga os mesmos ids da fatia", () => {
    const elements = toGraphElements(packageSlice);

    for (const node of elements.nodes) {
      expect(node.group).toBe("nodes");
    }
    for (const [index, edge] of elements.edges.entries()) {
      expect(edge.group).toBe("edges");
      expect(edge.data.source).toBe(packageSlice.edges[index]?.source);
      expect(edge.data.target).toBe(packageSlice.edges[index]?.target);
    }
  });

  test("uma fatia truncada é desenhada inteira, sem mais cortes", () => {
    const slice: GraphSlice = {
      ...packageSliceOf(300),
      totals: { edges: 0, nodes: 1800 },
      truncated: true,
    };

    expect(toGraphElements(slice).nodes).toHaveLength(300);
  });
});

describe("toGraphElements: o que pode morar em um nó", () => {
  test("o data de um nó tem exatamente as chaves do graphNodeSchema", () => {
    for (const node of toGraphElements(packageSlice).nodes) {
      expect(Object.keys(node.data).sort()).toEqual(NODE_KEYS);
    }
  });

  test("o data de uma aresta tem exatamente as chaves do graphEdgeSchema", () => {
    for (const edge of toGraphElements(packageSlice).edges) {
      expect(Object.keys(edge.data).sort()).toEqual(EDGE_KEYS);
    }
  });

  test("nada produzido depois de STRUCTURAL_GRAPH chega ao nó, mesmo que alguém o pendure na fatia", () => {
    const [first, ...rest] = packageSlice.nodes;
    const contaminated = {
      ...packageSlice,
      nodes: [
        { ...first, cochangeWeight: 7, hotspotBand: "HIGH", tourStep: 1 },
        ...rest,
      ],
    } as unknown as GraphSlice;

    const data = toGraphElements(contaminated).nodes[0]?.data ?? {};

    expect(data).not.toHaveProperty("hotspotBand");
    expect(data).not.toHaveProperty("cochangeWeight");
    expect(data).not.toHaveProperty("tourStep");
  });

  test("o rótulo é texto do repositório: controles bidi saem e o tamanho é limitado", () => {
    const long = "x".repeat(GRAPH_LABEL_MAX_CHARS + 20);
    const base = packageSliceOf(2);
    const slice: GraphSlice = {
      ...base,
      nodes: [
        { ...base.nodes[0], label: `${RLO}evil${PDF}` },
        { ...base.nodes[1], label: long },
      ] as GraphSlice["nodes"],
    };

    const [spoofed, clamped] = toGraphElements(slice).nodes;

    expect(spoofed?.data.label).toBe("evil");
    expect(Array.from(clamped?.data.label ?? "")).toHaveLength(
      GRAPH_LABEL_MAX_CHARS + 1
    );
    expect(clamped?.data.label.endsWith("…")).toBe(true);
  });

  test("o pacote padrão, de rótulo vazio, ganha um nome em vez de virar um nó sem nome", () => {
    const base = packageSliceOf(1);
    const slice: GraphSlice = {
      ...base,
      nodes: [
        { ...base.nodes[0], id: "pkg:", label: "" },
      ] as GraphSlice["nodes"],
    };

    expect(toGraphElements(slice).nodes[0]?.data.label).toBe(
      t.graph.defaultPackage
    );
  });
});

describe("toGraphElements: afirma o orçamento e lança", () => {
  test("um nó acima do orçamento da fatia lança", () => {
    const slice: GraphSlice = {
      ...packageSliceOf(301),
      totals: { edges: 0, nodes: 301 },
    };

    expect(violationOf(slice)).toBe("NODES_OVER_BUDGET");
  });

  test("uma aresta acima do orçamento da fatia lança", () => {
    const base = packageSliceOf(2);
    const slice: GraphSlice = {
      ...base,
      budget: { edges: 2, nodes: 300 },
      edges: edgesOf(base, 3),
      totals: { edges: 3, nodes: 2 },
    };

    expect(violationOf(slice)).toBe("EDGES_OVER_BUDGET");
  });

  test("um orçamento de arestas acima do teto do nível lança, com os nós dentro do teto", () => {
    const slice: GraphSlice = {
      ...packageSliceOf(3),
      budget: {
        edges: GRAPH_SLICE_BUDGETS.PACKAGE.edges + 1,
        nodes: GRAPH_SLICE_BUDGETS.PACKAGE.nodes,
      },
    };

    expect(violationOf(slice)).toBe("BUDGET_OVER_LEVEL_CEILING");
  });

  test("um orçamento declarado acima do teto do nível lança, mesmo com a fatia pequena", () => {
    const slice: GraphSlice = {
      ...packageSliceOf(3),
      budget: { edges: 30_000, nodes: 5000 },
    };

    expect(violationOf(slice)).toBe("BUDGET_OVER_LEVEL_CEILING");
  });

  test("o orçamento exato do nível passa", () => {
    expect(violationOf(packageSliceOf(300))).toBeUndefined();
  });

  test("mais elementos do que o total declarado lança", () => {
    const slice: GraphSlice = {
      ...packageSliceOf(5),
      totals: { edges: 0, nodes: 4 },
    };

    expect(violationOf(slice)).toBe("COUNTS_OVER_TOTALS");
  });

  test("mais arestas do que o total declarado lança, com os nós no total", () => {
    const base = packageSliceOf(2);
    const slice: GraphSlice = {
      ...base,
      edges: edgesOf(base, 3),
      totals: { edges: 2, nodes: 2 },
    };

    expect(violationOf(slice)).toBe("COUNTS_OVER_TOTALS");
  });

  test("menos elementos do que o total, sem truncated nem aggregated, lança", () => {
    const slice: GraphSlice = {
      ...packageSliceOf(5),
      totals: { edges: 0, nodes: 9 },
    };

    expect(violationOf(slice)).toBe("COUNTS_BELOW_TOTALS");
  });

  test("menos arestas do que o total, com os nós no total e sem bandeira, lança", () => {
    const base = packageSliceOf(2);
    const slice: GraphSlice = {
      ...base,
      edges: edgesOf(base, 1),
      totals: { edges: 3, nodes: 2 },
    };

    expect(violationOf(slice)).toBe("COUNTS_BELOW_TOTALS");
  });

  test("menos elementos do que o total com aggregated passa: é o degrau seguinte da escada", () => {
    const slice: GraphSlice = {
      ...packageSliceOf(40),
      aggregated: true,
      totals: { edges: 0, nodes: 1800 },
    };

    expect(violationOf(slice)).toBeUndefined();
  });

  test("um id repetido entre dois nós lança, porque o Cytoscape o descartaria calado", () => {
    const base = packageSliceOf(2);
    const slice: GraphSlice = {
      ...base,
      nodes: [base.nodes[0], { ...base.nodes[1], id: base.nodes[0]?.id }],
    } as GraphSlice;

    expect(violationOf(slice)).toBe("DUPLICATE_ID");
  });

  test("uma aresta com o id de um nó lança: nós e arestas dividem um só espaço de ids", () => {
    const base = packageSliceOf(2);
    const [from, to] = base.nodes;
    const slice: GraphSlice = {
      ...base,
      edges: [
        {
          id: from?.id ?? "",
          resolution: "RESOLVED",
          source: from?.id ?? "",
          target: to?.id ?? "",
          types: { IMPORTS: 1 },
          weight: 1,
        },
      ],
      totals: { edges: 1, nodes: 2 },
    };

    expect(violationOf(slice)).toBe("DUPLICATE_ID");
  });

  test("uma aresta para um nó fora da fatia lança, porque o Cytoscape lançaria no meio e deixaria meio grafo", () => {
    const base = packageSliceOf(1);
    const slice: GraphSlice = {
      ...base,
      edges: [
        {
          id: "e_00000000000000ff",
          resolution: "RESOLVED",
          source: base.nodes[0]?.id ?? "",
          target: "pkg:fora.da.fatia",
          types: { IMPORTS: 1 },
          weight: 1,
        },
      ],
      totals: { edges: 1, nodes: 1 },
    };

    expect(violationOf(slice)).toBe("DANGLING_ENDPOINT");
  });

  test("nível 1 com pai, ou nível de tipos sem pai, lança", () => {
    const withParent: GraphSlice = {
      ...packageSliceOf(1),
      parentId: "pkg:qualquer",
    };
    const orphanTypes: GraphSlice = { ...typeSlice, parentId: null };

    expect(violationOf(withParent)).toBe("PARENT_MISMATCH");
    expect(violationOf(orphanTypes)).toBe("PARENT_MISMATCH");
  });

  test("a mensagem do erro não carrega texto do repositório", () => {
    const base = packageSliceOf(1);
    const slice: GraphSlice = {
      ...base,
      edges: [
        {
          id: "e_00000000000000ff",
          resolution: "RESOLVED",
          source: base.nodes[0]?.id ?? "",
          target: "pkg:segredo.do.cliente",
          types: { IMPORTS: 1 },
          weight: 1,
        },
      ],
      totals: { edges: 1, nodes: 1 },
    };

    expect(() => toGraphElements(slice)).toThrow(GraphSliceViolationError);
    expect(() => toGraphElements(slice)).not.toThrow(MENTIONS_CLIENT_TEXT);
  });
});

describe("toGraphView", () => {
  test("devolve a fatia e os elementos, para o select da query", () => {
    const view = toGraphView(typeSlice);

    expect(view.slice).toBe(typeSlice);
    expect(view.elements.nodes).toHaveLength(typeSlice.nodes.length);
  });
});

const deepFreeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null) {
    for (const nested of Object.values(value)) {
      deepFreeze(nested);
    }
    Object.freeze(value);
  }
  return value;
};

describe("toGraphElements: mais garantias do contrato", () => {
  test("não toca na fatia, que é o dado em cache da query", () => {
    const before = structuredClone(packageSlice);
    const frozen = deepFreeze(structuredClone(packageSlice));

    const elements = toGraphElements(frozen);

    expect(frozen).toEqual(before);
    expect(elements.edges[0]?.data.types).not.toBe(frozen.edges[0]?.types);
    expect(elements.nodes[0]?.data.resolutionMix).not.toBe(
      frozen.nodes[0]?.resolutionMix
    );
  });

  test("duas arestas com o mesmo id lançam", () => {
    const [first] = packageSlice.edges;
    const slice: GraphSlice = {
      ...packageSlice,
      edges: [first, { ...first, target: "pkg:br.pucpr.billing" }],
      totals: { edges: 2, nodes: packageSlice.nodes.length },
    };

    expect(violationOf(slice)).toBe("DUPLICATE_ID");
  });

  test("a vizinhança de um tipo sem pai lança, como o nível de tipos", () => {
    const slice: GraphSlice = {
      ...typeSlice,
      budget: { ...GRAPH_SLICE_BUDGETS.TYPE_NEIGHBORHOOD },
      level: "TYPE_NEIGHBORHOOD",
      parentId: null,
    };

    expect(violationOf(slice)).toBe("PARENT_MISMATCH");
  });

  test("a unicidade é conferida antes das pontas, em uma fatia com os dois defeitos", () => {
    const [first, second] = packageSlice.edges;
    const slice: GraphSlice = {
      ...packageSlice,
      edges: [
        { ...first, target: "pkg:fora.da.fatia" },
        { ...second, id: first.id },
      ],
      totals: { edges: 2, nodes: packageSlice.nodes.length },
    };

    expect(violationOf(slice)).toBe("DUPLICATE_ID");
  });

  test("o nome do pacote padrão é só do pacote: um tipo de rótulo vazio continua vazio", () => {
    const slice: GraphSlice = {
      ...typeSlice,
      nodes: typeSlice.nodes.map((node, index) =>
        index === 0 ? { ...node, label: RLO } : node
      ),
    };

    expect(toGraphElements(slice).nodes[0]?.data.label).toBe("");
  });
});
