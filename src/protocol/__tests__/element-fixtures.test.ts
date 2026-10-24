import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";
import {
  DESCRIBED_IDS,
  dependencyPageOf,
  detailOf,
  EVIDENCE_PAIRS,
  evidenceOf,
  outlinePageOf,
  PACKAGE_IDS,
  PACKAGE_SLICE,
  ROOT_ITEMS,
  SEARCH_HITS,
  typeSliceOf,
} from "@/test/fixture-graph";
import {
  type DependencyDirection,
  type DependencyType,
  dependencyDirectionSchema,
  dependencyTypeSchema,
  type ResolutionQuality,
} from "../enums";
import {
  DEPENDENCY_EVIDENCE_RESULTS_MAX,
  type DependencyEntry,
  type ElementDetail,
  type GraphEdge,
  type GraphNode,
  type GraphSlice,
  type RelationshipEvidence,
} from "../graph";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "../../..");
const FIXTURES_DIR = path.resolve(REPO_ROOT, "protocol/fixtures");
const WORLD_REPOSITORY = path.resolve(
  REPO_ROOT,
  "fixtures/repositories/multi-module"
);
const JAVA_DEPENDENCY_TYPE = path.resolve(
  REPO_ROOT,
  "analyzer/src/main/java/br/pucpr/tcc/strata/analyzer/sourceanalysis/model/DependencyType.java"
);

const DIRECTIONS = dependencyDirectionSchema.options;
const OPPOSITE = {
  IN: "OUT",
  OUT: "IN",
} as const satisfies Record<DependencyDirection, DependencyDirection>;

const TYPE_PREFIX = "type:";
const METHOD_PREFIX = "method:";
const JSONL_SUFFIX = ".jsonl";
const NUL = String.fromCodePoint(0);
const DRIVE_LETTER = /^[A-Za-z]:/;
const JAVA_COMMENT = /\/\*[\s\S]*?\*\/|\/\/[^\n]*/g;
const JAVA_DEPENDENCY_TYPE_BODY = /enum DependencyType\s*\{([^;]*);/;
const GRAPH_VIEW_STEM = /^(graph\.slice\.|outline\.children\.|graph\.search\.)/;
const DESCRIBE_STEM = /^element\.describe\./;
const TRAILING_NEWLINE = /\n$/;

const EVIDENCE_STORED_PER_ROW = 20;

const WEIGHT_OF = {
  ANNOTATION_REFERENCE: 1,
  CALLS: 2,
  EXTENDS: 4,
  FIELD_TYPE: 3,
  IMPLEMENTS: 4,
  IMPORTS: 1,
  PARAMETER_TYPE: 1,
  RETURN_TYPE: 2,
} as const satisfies Record<
  Exclude<DependencyType, "MODULE_DEPENDENCY">,
  number
>;

const STRENGTH = {
  INFERRED: 1,
  RESOLVED: 2,
  UNRESOLVED_EXTERNAL: 0,
} as const satisfies Record<ResolutionQuality, number>;

const fnv1a64 = (text: string): string => {
  const offsetBasis = 0xcb_f2_9c_e4_84_22_23_25n;
  const prime = 0x1_00_00_00_01_b3n;
  let hash = offsetBasis;
  for (const byte of new TextEncoder().encode(text)) {
    // biome-ignore lint/suspicious/noBitwiseOperators: o FNV-1a usa XOR por definição
    hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * prime);
  }
  return hash.toString(16).padStart(16, "0");
};

const pairEdgeId = (source: string, target: string): string =>
  `e_${fnv1a64(`${source}${NUL}${target}`)}`;

const compareCodePoints = (left: string, right: string): number => {
  const leftPoints = Array.from(left, (char) => char.codePointAt(0) ?? 0);
  const rightPoints = Array.from(right, (char) => char.codePointAt(0) ?? 0);
  const shared = Math.min(leftPoints.length, rightPoints.length);
  for (let index = 0; index < shared; index += 1) {
    const difference = (leftPoints[index] ?? 0) - (rightPoints[index] ?? 0);
    if (difference !== 0) {
      return difference;
    }
  }
  return leftPoints.length - rightPoints.length;
};

const compareLines = (left: number | null, right: number | null): number => {
  if (left === right) {
    return 0;
  }
  if (left === null) {
    return 1;
  }
  if (right === null) {
    return -1;
  }
  return left - right;
};

const compareMethodIds = (
  left: string | undefined,
  right: string | undefined
): number => {
  if (left === right) {
    return 0;
  }
  if (left === undefined) {
    return -1;
  }
  if (right === undefined) {
    return 1;
  }
  return compareCodePoints(left, right);
};

const ccMeanOracle = (ccSum: number, unitCount: number): number =>
  unitCount === 0
    ? 0
    : Math.floor((20 * ccSum + unitCount) / (2 * unitCount)) / 10;

const weakest = (qualities: readonly ResolutionQuality[]): ResolutionQuality =>
  qualities.reduce((weaker, quality) =>
    STRENGTH[quality] < STRENGTH[weaker] ? quality : weaker
  );

const typeEntries = (
  types: Partial<Record<DependencyType, number>>
): [DependencyType, number][] =>
  Object.entries(types) as [DependencyType, number][];

const weightedSum = (types: Partial<Record<DependencyType, number>>): number =>
  typeEntries(types).reduce((sum, [type, occurrences]) => {
    const weight = (WEIGHT_OF as Partial<Record<DependencyType, number>>)[type];
    return sum + (weight ?? Number.NaN) * occurrences;
  }, 0);

const sumTypes = (
  maps: readonly Partial<Record<DependencyType, number>>[]
): Partial<Record<DependencyType, number>> =>
  Object.fromEntries(
    dependencyTypeSchema.options.flatMap((type) => {
      const total = maps.reduce((sum, map) => sum + (map[type] ?? 0), 0);
      return total > 0 ? [[type, total]] : [];
    })
  );

const rowFacts = (row: DependencyEntry) => ({
  id: row.edgeId,
  resolution: row.resolution,
  types: row.types,
  weight: row.weight,
});

const edgeFacts = (edge: GraphEdge) => ({
  id: edge.id,
  resolution: edge.resolution,
  types: edge.types,
  weight: edge.weight,
});

const TYPE_SLICES: readonly GraphSlice[] = PACKAGE_IDS.map((id) =>
  typeSliceOf(id)
);
const WORLD_NODES: readonly GraphNode[] = [
  ...PACKAGE_SLICE.nodes,
  ...TYPE_SLICES.flatMap((slice) => slice.nodes),
];
const DRAWN_EDGES: readonly GraphEdge[] = [
  ...PACKAGE_SLICE.edges,
  ...TYPE_SLICES.flatMap((slice) => slice.edges),
];
const DETAILS: readonly ElementDetail[] = DESCRIBED_IDS.map((id) =>
  detailOf(id)
);
const TYPE_DETAILS = DETAILS.filter((detail) => detail.kind === "TYPE");
const PACKAGE_DETAILS = DETAILS.filter((detail) => detail.kind === "PACKAGE");

interface WorldRow {
  readonly direction: DependencyDirection;
  readonly elementId: string;
  readonly row: DependencyEntry;
}

const WORLD_ROWS: readonly WorldRow[] = DESCRIBED_IDS.flatMap((elementId) =>
  DIRECTIONS.flatMap((direction) =>
    dependencyPageOf(elementId, direction).items.map((row) => ({
      direction,
      elementId,
      row,
    }))
  )
);
const INTERNAL_ROWS = WORLD_ROWS.filter(({ row }) => !row.isExternal);
const EXTERNAL_ROWS = WORLD_ROWS.filter(({ row }) => row.isExternal);

const pairOf = ({
  direction,
  elementId,
  row,
}: WorldRow): { readonly source: string; readonly target: string } =>
  direction === "OUT"
    ? { source: elementId, target: row.elementId }
    : { source: row.elementId, target: elementId };

const rowOf = (
  elementId: string,
  direction: DependencyDirection,
  other: string
): DependencyEntry | undefined =>
  dependencyPageOf(elementId, direction).items.find(
    (row) => row.elementId === other
  );

const sliceDrawing = (
  source: string,
  target: string
): GraphSlice | undefined => {
  const from = detailOf(source);
  const to = detailOf(target);
  if (from.kind === "PACKAGE" && to.kind === "PACKAGE") {
    return PACKAGE_SLICE;
  }
  if (from.packageId !== null && from.packageId === to.packageId) {
    return typeSliceOf(from.packageId);
  }
  return undefined;
};

const methodPrefixOf = (typeId: string): string =>
  `${METHOD_PREFIX}${typeId.slice(TYPE_PREFIX.length)}#`;

const reachableFrom = (start: string): ReadonlySet<string> => {
  const seen = new Set([start]);
  const frontier = [start];
  while (frontier.length > 0) {
    const current = frontier.pop() ?? start;
    for (const row of dependencyPageOf(current, "OUT").items) {
      if (!(row.isExternal || seen.has(row.elementId))) {
        seen.add(row.elementId);
        frontier.push(row.elementId);
      }
    }
  }
  return seen;
};

const componentOf = (elementId: string): readonly string[] => {
  const forward = reachableFrom(elementId);
  return DESCRIBED_IDS.filter(
    (other) => forward.has(other) && reachableFrom(other).has(elementId)
  );
};

const worldFile = (filePath: string): string =>
  path.join(WORLD_REPOSITORY, ...filePath.split("/"));

const lineCountOf = (filePath: string): number =>
  readFileSync(worldFile(filePath), "utf8")
    .replace(TRAILING_NEWLINE, "")
    .split("\n").length;

const isRootRelative = (filePath: string): boolean =>
  !(filePath.includes("\\") || DRIVE_LETTER.test(filePath)) &&
  filePath
    .split("/")
    .every((segment) => segment !== "" && segment !== "." && segment !== "..");

const collectStrings = (value: unknown, into: string[]): string[] => {
  if (typeof value === "string") {
    into.push(value);
  } else if (Array.isArray(value)) {
    for (const item of value) {
      collectStrings(item, into);
    }
  } else if (typeof value === "object" && value !== null) {
    for (const item of Object.values(value)) {
      collectStrings(item, into);
    }
  }
  return into;
};

const stringsOfFixture = (stem: string): string[] =>
  readFileSync(path.join(FIXTURES_DIR, `${stem}${JSONL_SUFFIX}`), "utf8")
    .split("\n")
    .filter((line) => line.trim() !== "")
    .flatMap((line) => collectStrings(JSON.parse(line), []));

const fixtureStems = (topic: RegExp): string[] =>
  readdirSync(FIXTURES_DIR)
    .filter((name) => name.endsWith(JSONL_SUFFIX))
    .map((name) => name.slice(0, -JSONL_SUFFIX.length))
    .filter((stem) => topic.test(stem))
    .sort();

const javaDependencyTypes = (): string[] => {
  const source = readFileSync(JAVA_DEPENDENCY_TYPE, "utf8").replace(
    JAVA_COMMENT,
    ""
  );
  return (JAVA_DEPENDENCY_TYPE_BODY.exec(source)?.[1] ?? "")
    .split(",")
    .map((constant) => constant.trim())
    .filter((constant) => constant !== "");
};

const sumOver = <T>(items: readonly T[], count: (item: T) => number): number =>
  items.reduce((sum, item) => sum + count(item), 0);

const pageTotals = (elementId: string) =>
  dependencyPageOf(elementId, "OUT").totals;

describe("a cobertura do mundo do inspetor", () => {
  test("todo pacote e todo tipo desenhado se descreve, e nada fora deles", () => {
    expect([...DESCRIBED_IDS].sort()).toStrictEqual(
      WORLD_NODES.map((node) => node.id).sort()
    );
    expect(DESCRIBED_IDS.length).toBeGreaterThan(0);
  });

  test.each(DESCRIBED_IDS)(
    "%s: a fixture responde pelo elemento pedido, e as duas direções existem",
    (elementId) => {
      expect(detailOf(elementId).elementId).toBe(elementId);
      for (const direction of DIRECTIONS) {
        expect(
          () => dependencyPageOf(elementId, direction),
          direction
        ).not.toThrow();
      }
    }
  );

  test("nenhuma página do mundo continua: todo nextCursor é nulo", () => {
    const cursors = DESCRIBED_IDS.flatMap((elementId) =>
      DIRECTIONS.map(
        (direction) => dependencyPageOf(elementId, direction).nextCursor
      )
    );

    expect(cursors).toHaveLength(DESCRIBED_IDS.length * DIRECTIONS.length);
    expect(new Set(cursors)).toStrictEqual(new Set([null]));
  });

  test("as fatias do mundo não cortam: o que não está desenhado não existe no nível", () => {
    for (const slice of [PACKAGE_SLICE, ...TYPE_SLICES]) {
      expect([slice.parentId, slice.truncated, slice.aggregated]).toStrictEqual(
        [slice.parentId, false, false]
      );
    }
  });

  test("há os três pares de evidência que E20 pede, um deles de alvo externo", () => {
    expect(EVIDENCE_PAIRS).toHaveLength(3);
    expect(
      EVIDENCE_PAIRS.filter(({ target }) => !DESCRIBED_IDS.includes(target))
    ).toHaveLength(1);
  });
});

describe("o detalhe lê como o nó desenhado, a linha da estrutura e o resultado da busca", () => {
  test.each(DESCRIBED_IDS)(
    "%s: rótulo, tipo, fans, PageRank, mistura e loc são os do nó",
    (elementId) => {
      const detail = detailOf(elementId);
      const node = WORLD_NODES.find((candidate) => candidate.id === elementId);

      expect({
        fanIn: detail.graph.fanIn,
        fanOut: detail.graph.fanOut,
        kind: detail.kind,
        label: detail.label,
        loc: detail.metrics.loc,
        resolutionMix: detail.graph.resolutionMix,
      }).toStrictEqual({
        fanIn: node?.fanIn,
        fanOut: node?.fanOut,
        kind: node?.kind,
        label: node?.label,
        loc: node?.loc,
        resolutionMix: node?.resolutionMix,
      });
      expect(Object.is(detail.graph.pagerank, node?.pagerank)).toBe(true);
    }
  );

  test.each(TYPE_DETAILS.map((detail) => detail.elementId))(
    "%s: o packageId é o pacote cuja fatia desenha o tipo",
    (elementId) => {
      const { packageId } = detailOf(elementId);
      const drawnIn = PACKAGE_IDS.filter((id) =>
        typeSliceOf(id).nodes.some((node) => node.id === elementId)
      );

      expect(drawnIn).toStrictEqual([packageId]);
    }
  );

  test("toda linha da estrutura de um elemento descrito tem o mesmo rótulo e tipo", () => {
    const outlineRows = [
      ...ROOT_ITEMS,
      ...PACKAGE_IDS.flatMap((id) => outlinePageOf(id).items),
    ];

    expect(outlineRows).toHaveLength(DESCRIBED_IDS.length);
    for (const row of outlineRows) {
      const detail = detailOf(row.id);
      expect([row.id, row.label, row.kind]).toStrictEqual([
        row.id,
        detail.label,
        detail.kind,
      ]);
    }
  });

  test("todo resultado da busca é um elemento descrito, com o mesmo rótulo, pacote e nome", () => {
    expect(SEARCH_HITS.length).toBeGreaterThan(0);
    for (const hit of SEARCH_HITS) {
      const detail = detailOf(hit.elementId);
      expect({
        kind: hit.kind,
        label: hit.label,
        packageId: hit.packageId,
        qualifiedName: hit.qualifiedName,
      }).toStrictEqual({
        kind: detail.kind,
        label: detail.label,
        packageId: detail.packageId,
        qualifiedName: detail.qualifiedName,
      });
    }
  });

  test.each(PACKAGE_DETAILS.map((detail) => detail.elementId))(
    "%s: o que um pacote não tem é nulo ou vazio, nunca zero fabricado",
    (elementId) => {
      const detail = detailOf(elementId);

      expect({
        ccP90: detail.metrics.ccP90,
        filePath: detail.filePath,
        lines: detail.lines,
        methods: detail.methods,
        methodTotal: detail.methodTotal,
        packageId: detail.packageId,
        typeKind: detail.typeKind,
      }).toStrictEqual({
        ccP90: null,
        filePath: null,
        lines: null,
        methods: [],
        methodTotal: 0,
        packageId: null,
        typeKind: null,
      });
    }
  );

  test.each(TYPE_DETAILS.map((detail) => detail.elementId))(
    "%s: um tipo tem arquivo, tipo de declaração, pacote e quantil",
    (elementId) => {
      const detail = detailOf(elementId);

      expect(
        [
          detail.filePath,
          detail.typeKind,
          detail.packageId,
          detail.metrics.ccP90,
        ].includes(null)
      ).toBe(false);
    }
  );
});

describe("as métricas do detalhe", () => {
  test("o oráculo da média é HALF_UP a uma casa", () => {
    expect([
      ccMeanOracle(2, 1),
      ccMeanOracle(9, 4),
      ccMeanOracle(7, 3),
      ccMeanOracle(0, 0),
      ccMeanOracle(1, 4),
      ccMeanOracle(3, 40),
    ]).toStrictEqual([2, 2.3, 2.3, 0, 0.3, 0.1]);
  });

  test.each(DESCRIBED_IDS)(
    "%s: ccMean é ccSum / unitCount arredondado HALF_UP a uma casa",
    (elementId) => {
      const { metrics } = detailOf(elementId);

      expect(metrics.ccMean).toBe(
        ccMeanOracle(metrics.ccSum, metrics.unitCount)
      );
    }
  );

  test.each(DESCRIBED_IDS)(
    "%s: o maior não passa da soma, o quantil não passa do maior, sem unidade é tudo 0",
    (elementId) => {
      const { metrics } = detailOf(elementId);
      const noUnits = metrics.unitCount === 0;

      expect(metrics.ccMax).toBeLessThanOrEqual(metrics.ccSum);
      expect(metrics.ccP90 ?? 0).toBeLessThanOrEqual(metrics.ccMax);
      expect(noUnits && metrics.ccSum + metrics.ccMax > 0).toBe(false);
    }
  );

  test.each(TYPE_DETAILS.map((detail) => detail.elementId))(
    "%s: o loc de um tipo cabe no intervalo de linhas dele",
    (elementId) => {
      const { lines, metrics } = detailOf(elementId);

      expect(lines).not.toBeNull();
      expect(metrics.loc).toBeLessThanOrEqual(
        (lines?.end ?? 0) - (lines?.start ?? 0) + 1
      );
    }
  );

  test.each(PACKAGE_DETAILS.map((detail) => detail.elementId))(
    "%s: unitCount, ccSum e ccMax do pacote são o rolo dos tipos dele, e a mistura também",
    (packageId) => {
      const detail = detailOf(packageId);
      const members = TYPE_DETAILS.filter(
        (type) => type.packageId === packageId
      );
      const mixOf = (quality: ResolutionQuality): number =>
        sumOver(members, (type) => type.graph.resolutionMix[quality]);

      expect(members.map((type) => type.elementId).sort()).toStrictEqual(
        typeSliceOf(packageId)
          .nodes.map((node) => node.id)
          .sort()
      );
      expect({
        ccMax: detail.metrics.ccMax,
        ccSum: detail.metrics.ccSum,
        resolutionMix: detail.graph.resolutionMix,
        unitCount: detail.metrics.unitCount,
      }).toStrictEqual({
        ccMax: Math.max(0, ...members.map((type) => type.metrics.ccMax)),
        ccSum: sumOver(members, (type) => type.metrics.ccSum),
        resolutionMix: {
          INFERRED: mixOf("INFERRED"),
          RESOLVED: mixOf("RESOLVED"),
          UNRESOLVED_EXTERNAL: mixOf("UNRESOLVED_EXTERNAL"),
        },
        unitCount: sumOver(members, (type) => type.metrics.unitCount),
      });
    }
  );
});

describe("os métodos do detalhe", () => {
  test.each(TYPE_DETAILS.map((detail) => detail.elementId))(
    "%s: cada método é do próprio tipo, uma vez, e a lista não foi cortada",
    (elementId) => {
      const { methodTotal, methods } = detailOf(elementId);
      const ids = methods.map((method) => method.elementId);

      for (const id of ids) {
        expect(id.startsWith(methodPrefixOf(elementId)), id).toBe(true);
      }
      expect(new Set(ids).size).toBe(ids.length);
      expect(methodTotal).toBe(methods.length);
    }
  );

  test.each(TYPE_DETAILS.map((detail) => detail.elementId))(
    "%s: os métodos vêm por linha, os sem posição por último, então pelo id por code point",
    (elementId) => {
      const { methods } = detailOf(elementId);

      for (const [index, method] of methods.entries()) {
        const previous = methods[index - 1];
        if (previous !== undefined) {
          const order =
            compareLines(
              previous.lines?.start ?? null,
              method.lines?.start ?? null
            ) || compareCodePoints(previous.elementId, method.elementId);
          expect(order, method.elementId).toBeLessThan(0);
        }
      }
    }
  );

  test.each(TYPE_DETAILS.map((detail) => detail.elementId))(
    "%s: um método com posição está dentro do tipo, e o cc dos métodos não passa do do tipo",
    (elementId) => {
      const { lines, methods, metrics } = detailOf(elementId);
      const measured = methods.flatMap((method) =>
        method.cc === null ? [] : [method.cc]
      );

      for (const method of methods) {
        if (method.lines !== null) {
          expect(
            [
              method.lines.start >= (lines?.start ?? 0),
              method.lines.end <= (lines?.end ?? 0),
            ],
            method.elementId
          ).toStrictEqual([true, true]);
        }
      }
      expect(sumOver(measured, (cc) => cc)).toBeLessThanOrEqual(metrics.ccSum);
      expect(measured.length).toBeLessThanOrEqual(metrics.unitCount);
    }
  );
});

describe("o ciclo do detalhe é o componente fortemente conexo das páginas", () => {
  test.each(DESCRIBED_IDS)(
    "%s: cycle é nulo fora de componente, e size é o tamanho do componente",
    (elementId) => {
      const component = componentOf(elementId);
      const { cycle } = detailOf(elementId).graph;

      expect(cycle?.size ?? null).toBe(
        component.length >= 2 ? component.length : null
      );
    }
  );

  test("dois elementos do mesmo nível dividem o id do ciclo se e só se dividem o componente", () => {
    const cyclic = DETAILS.filter((detail) => detail.graph.cycle !== null);

    expect(cyclic.length).toBeGreaterThan(0);
    for (const left of cyclic) {
      for (const right of cyclic.filter(
        (detail) => detail.kind === left.kind
      )) {
        expect(
          left.graph.cycle?.id === right.graph.cycle?.id,
          `${left.elementId} ~ ${right.elementId}`
        ).toBe(componentOf(left.elementId).includes(right.elementId));
      }
    }
  });
});

describe("os totais são os fans", () => {
  test.each(DESCRIBED_IDS)(
    "%s: totals é o mesmo nas duas direções, in = fanIn, out = fanOut, externos à parte",
    (elementId) => {
      const detail = detailOf(elementId);
      const inPage = dependencyPageOf(elementId, "IN");
      const outPage = dependencyPageOf(elementId, "OUT");
      const external = outPage.items.filter((row) => row.isExternal);

      expect(inPage.totals).toStrictEqual(outPage.totals);
      expect([outPage.totals.in, outPage.totals.out]).toStrictEqual([
        detail.graph.fanIn,
        detail.graph.fanOut,
      ]);
      expect(outPage.totals.outExternal === null).toBe(
        detail.kind === "PACKAGE"
      );
      expect([
        inPage.items.length,
        outPage.items.length - external.length,
        external.length,
      ]).toStrictEqual([
        outPage.totals.in,
        outPage.totals.out,
        outPage.totals.outExternal ?? 0,
      ]);
    }
  );

  test("IN e OUT contam as mesmas arestas: Σ fanIn = Σ fanOut em cada nível", () => {
    for (const kind of ["PACKAGE", "TYPE"] as const) {
      const level = DETAILS.filter((detail) => detail.kind === kind);
      expect(
        sumOver(level, (detail) => detail.graph.fanIn),
        kind
      ).toBe(sumOver(level, (detail) => detail.graph.fanOut));
    }
  });
});

describe("a linha da página é a aresta", () => {
  test("o oráculo FNV reproduz o vetor de teste e o exemplo de domain-model.md", () => {
    expect(fnv1a64("a")).toBe("af63dc4c8601ec8c");
    expect(
      pairEdgeId("pkg:br.pucpr.legacy.api", "pkg:br.pucpr.legacy.core")
    ).toBe("e_8a135394c938e16e");
  });

  test("toda linha leva o id do par na direção da dependência", () => {
    const expectedRows = sumOver(
      DESCRIBED_IDS,
      (id) =>
        pageTotals(id).in +
        pageTotals(id).out +
        (pageTotals(id).outExternal ?? 0)
    );

    expect(WORLD_ROWS).toHaveLength(expectedRows);
    expect(expectedRows).toBeGreaterThan(0);
    for (const worldRow of WORLD_ROWS) {
      const { source, target } = pairOf(worldRow);
      expect(worldRow.row.edgeId, `${source} -> ${target}`).toBe(
        pairEdgeId(source, target)
      );
    }
  });

  test("toda linha cujas pontas uma fatia desenha é a aresta dela, campo a campo", () => {
    let joined = 0;
    for (const worldRow of INTERNAL_ROWS) {
      const { source, target } = pairOf(worldRow);
      const slice = sliceDrawing(source, target);
      if (slice !== undefined) {
        const edge = slice.edges.find(
          (candidate) =>
            candidate.source === source && candidate.target === target
        );
        expect(edge, `${source} -> ${target}`).toBeDefined();
        expect(rowFacts(worldRow.row)).toStrictEqual(
          edge === undefined ? undefined : edgeFacts(edge)
        );
        joined += 1;
      }
    }

    expect(DRAWN_EDGES.length).toBeGreaterThan(0);
    expect(joined).toBe(2 * DRAWN_EDGES.length);
  });

  test("toda linha interna tem o espelho na outra ponta: o mesmo par, os mesmos fatos", () => {
    for (const { direction, elementId, row } of INTERNAL_ROWS) {
      const mirror = rowOf(row.elementId, OPPOSITE[direction], elementId);
      expect(
        mirror,
        `${direction} ${elementId} -> ${row.elementId}`
      ).toBeDefined();
      expect({ ...rowFacts(row), ambiguous: row.ambiguous }).toStrictEqual(
        mirror === undefined
          ? undefined
          : { ...rowFacts(mirror), ambiguous: mirror.ambiguous }
      );
    }

    expect(INTERNAL_ROWS).toHaveLength(
      2 * sumOver(DESCRIBED_IDS, (id) => pageTotals(id).out)
    );
  });

  test("uma ponta interna lê como o detalhe dela", () => {
    for (const { row } of INTERNAL_ROWS) {
      const other = detailOf(row.elementId);
      expect({
        kind: row.kind,
        label: row.label,
        packageId: row.packageId,
        qualifiedName: row.qualifiedName,
      }).toStrictEqual({
        kind: other.kind,
        label: other.label,
        packageId: other.packageId,
        qualifiedName: other.qualifiedName,
      });
    }
  });

  test("uma ponta externa só existe em OUT de um tipo: rótulo = nome qualificado, sem pacote", () => {
    expect(EXTERNAL_ROWS).toHaveLength(
      sumOver(DESCRIBED_IDS, (id) => pageTotals(id).outExternal ?? 0)
    );
    expect(EXTERNAL_ROWS.length).toBeGreaterThan(0);
    for (const { direction, elementId, row } of EXTERNAL_ROWS) {
      expect({
        described: DESCRIBED_IDS.includes(row.elementId),
        direction,
        kind: row.kind,
        label: row.label,
        owner: detailOf(elementId).kind,
        packageId: row.packageId,
      }).toStrictEqual({
        described: false,
        direction: "OUT",
        kind: "TYPE",
        label: row.qualifiedName,
        owner: "TYPE",
        packageId: null,
      });
    }
  });

  test("ambiguous só em uma linha externa UNRESOLVED_EXTERNAL", () => {
    for (const { row } of WORLD_ROWS.filter(
      (candidate) => candidate.row.ambiguous
    )) {
      expect([row.isExternal, row.resolution]).toStrictEqual([
        true,
        "UNRESOLVED_EXTERNAL",
      ]);
    }
  });

  test.each(TYPE_DETAILS.map((detail) => detail.elementId))(
    "%s: a mistura do tipo são as ocorrências das linhas OUT dele, as externas incluídas",
    (elementId) => {
      const { resolutionMix } = detailOf(elementId).graph;
      const occurrences = sumOver(
        dependencyPageOf(elementId, "OUT").items,
        (row) => sumOver(typeEntries(row.types), ([, count]) => count)
      );

      expect(occurrences).toBe(
        resolutionMix.INFERRED +
          resolutionMix.RESOLVED +
          resolutionMix.UNRESOLVED_EXTERNAL
      );
    }
  );

  test("o peso de uma linha de tipos é Σ peso(tipo) × ocorrências", () => {
    const typeRows = WORLD_ROWS.filter(
      ({ elementId }) => detailOf(elementId).kind === "TYPE"
    );

    expect(typeRows.length).toBeGreaterThan(0);
    for (const { row } of typeRows) {
      expect(row.weight, row.edgeId).toBe(weightedSum(row.types));
    }
  });

  test.each(PACKAGE_DETAILS.map((detail) => detail.elementId))(
    "%s: cada linha OUT do pacote é o rolo das linhas de tipo que cruzam para o outro",
    (packageId) => {
      const crossing = TYPE_DETAILS.filter(
        (type) => type.packageId === packageId
      ).flatMap((type) =>
        dependencyPageOf(type.elementId, "OUT").items.filter(
          (row) =>
            !row.isExternal &&
            row.packageId !== null &&
            row.packageId !== packageId
        )
      );
      const targets = [...new Set(crossing.map((row) => row.packageId))];
      const rolledUp = Object.fromEntries(
        targets.map((target) => {
          const pairs = crossing.filter((row) => row.packageId === target);
          return [
            target,
            {
              resolution: weakest(pairs.map((row) => row.resolution)),
              types: sumTypes(pairs.map((row) => row.types)),
              weight: pairs.length,
            },
          ];
        })
      );

      expect(
        Object.fromEntries(
          dependencyPageOf(packageId, "OUT").items.map((row) => [
            row.elementId,
            {
              resolution: row.resolution,
              types: row.types,
              weight: row.weight,
            },
          ])
        )
      ).toStrictEqual(rolledUp);
    }
  );
});

describe("a ordem da página é a do Java", () => {
  test("o comparador é por code point, e não pela unidade UTF-16", () => {
    const bmp = String.fromCodePoint(0xff_61);
    const astral = String.fromCodePoint(0x1_f6_00);

    expect(compareCodePoints(bmp, astral)).toBeLessThan(0);
    expect(bmp < astral).toBe(false);
  });

  test.each(
    DESCRIBED_IDS.flatMap((id) => DIRECTIONS.map((d) => [id, d] as const))
  )(
    "%s %s: internos antes dos externos, peso decrescente, então o id por code point",
    (elementId, direction) => {
      const { items } = dependencyPageOf(elementId, direction);

      for (const [index, row] of items.entries()) {
        const previous = items[index - 1];
        if (previous !== undefined) {
          const order =
            Number(previous.isExternal) - Number(row.isExternal) ||
            row.weight - previous.weight ||
            compareCodePoints(previous.elementId, row.elementId);
          expect(order, row.elementId).toBeLessThan(0);
        }
      }
    }
  );

  test("o mundo exercita o desempate pelo id: há linhas vizinhas de mesmo peso no mesmo bloco", () => {
    const ties = DESCRIBED_IDS.flatMap((elementId) =>
      DIRECTIONS.flatMap((direction) => {
        const { items } = dependencyPageOf(elementId, direction);
        return items.filter((row, index) => {
          const previous = items[index - 1];
          return (
            previous !== undefined &&
            previous.weight === row.weight &&
            previous.isExternal === row.isExternal
          );
        });
      })
    );

    expect(ties.length).toBeGreaterThan(0);
  });
});

describe("métodos aparecem no inspetor e em lugar nenhum do grafo", () => {
  test("nenhum id de método e nenhum tipo METHOD em fatia, estrutura ou busca", () => {
    const stems = fixtureStems(GRAPH_VIEW_STEM);

    expect(stems).toEqual(
      expect.arrayContaining([
        "graph.search.hit",
        "graph.slice.package",
        "graph.slice.package-aggregated",
        "outline.children.root",
      ])
    );
    for (const stem of stems) {
      const strings = stringsOfFixture(stem);
      expect(
        strings.filter(
          (value) => value.startsWith(METHOD_PREFIX) || value === "METHOD"
        ),
        stem
      ).toStrictEqual([]);
    }
  });

  test("a varredura enxerga um método quando ele está lá: o detalhe de um tipo tem", () => {
    const withMethods = fixtureStems(DESCRIBE_STEM).filter((stem) =>
      stringsOfFixture(stem).some((value) => value.startsWith(METHOD_PREFIX))
    );

    expect(withMethods.length).toBeGreaterThan(0);
  });
});

describe("todo filePath é um arquivo do multi-module, relativo à raiz", () => {
  const filePaths = [
    ...TYPE_DETAILS.map((detail) => detail.filePath),
    ...EVIDENCE_PAIRS.flatMap(({ source, target }) =>
      evidenceOf(source, target).map((evidence) => evidence.filePath)
    ),
  ].filter((filePath): filePath is string => filePath !== null);

  test("há um filePath por tipo e por evidência que tem arquivo", () => {
    expect(filePaths.length).toBeGreaterThanOrEqual(TYPE_DETAILS.length);
  });

  test.each([...new Set(filePaths)])(
    "%s existe sob fixtures/repositories/multi-module",
    (filePath) => {
      expect(isRootRelative(filePath)).toBe(true);
      expect(existsSync(worldFile(filePath))).toBe(true);
      expect(statSync(worldFile(filePath)).isFile()).toBe(true);
    }
  );

  test("a guarda de raiz relativa recusa as formas que escapam da raiz", () => {
    for (const bad of [
      "/core/A.java",
      "C:/core/A.java",
      "core\\A.java",
      "./core/A.java",
      "core/../A.java",
      "core//A.java",
    ]) {
      expect(isRootRelative(bad), bad).toBe(false);
    }
    expect(isRootRelative("core/src/main/java/A.java")).toBe(true);
  });

  test.each(TYPE_DETAILS.map((detail) => detail.elementId))(
    "%s: o arquivo declara o pacote do tipo e contém as linhas dele e dos métodos",
    (elementId) => {
      const { filePath, lines, methods, packageId } = detailOf(elementId);
      const source = readFileSync(worldFile(filePath ?? ""), "utf8");
      const lastLine = lineCountOf(filePath ?? "");
      const ends = [lines, ...methods.map((method) => method.lines)].flatMap(
        (range) => (range === null ? [] : [range.end])
      );

      expect(
        source.includes(`package ${detailOf(packageId ?? "").qualifiedName};`)
      ).toBe(true);
      expect(Math.max(...ends)).toBeLessThanOrEqual(lastLine);
    }
  );
});

describe("a evidência de um par", () => {
  const rowOfPair = (source: string, target: string) =>
    rowOf(source, "OUT", target);

  test("o enum de tipos do TypeScript tem a ordem de declaração do Java", () => {
    expect(dependencyTypeSchema.options).toStrictEqual(javaDependencyTypes());
    expect(dependencyTypeSchema.options).toHaveLength(9);
  });

  test.each(EVIDENCE_PAIRS)(
    "$source -> $target: a linha do par existe, e os tipos da evidência são as chaves de types",
    ({ source, target }) => {
      const row = rowOfPair(source, target);
      const types = new Set(
        evidenceOf(source, target).map((item) => item.type)
      );

      expect(row).toBeDefined();
      expect([...types].sort()).toStrictEqual(
        Object.keys(row?.types ?? {}).sort()
      );
    }
  );

  test.each(EVIDENCE_PAIRS)(
    "$source -> $target: agrupada por tipo na ordem do enum, e dentro do tipo pelo conteúdo",
    ({ source, target }) => {
      const evidence = evidenceOf(source, target);
      const rank = (item: RelationshipEvidence): number =>
        dependencyTypeSchema.options.indexOf(item.type);

      for (const [index, item] of evidence.entries()) {
        const previous = evidence[index - 1];
        if (previous !== undefined) {
          const order =
            rank(previous) - rank(item) ||
            compareLines(previous.line, item.line) ||
            compareMethodIds(
              previous.fromMethod?.elementId,
              item.fromMethod?.elementId
            ) ||
            compareMethodIds(
              previous.toMethod?.elementId,
              item.toMethod?.elementId
            );
          expect(order, `${item.type} ${item.line}`).toBeLessThanOrEqual(0);
        }
      }
    }
  );

  test.each(EVIDENCE_PAIRS)(
    "$source -> $target: quando tudo cabe, cada tipo traz min(ocorrências, 20)",
    ({ source, target }) => {
      const row = rowOfPair(source, target);
      const stored = typeEntries(row?.types ?? {}).map(
        ([type, occurrences]) =>
          [type, Math.min(occurrences, EVIDENCE_STORED_PER_ROW)] as const
      );
      const sent = evidenceOf(source, target);

      expect(sumOver(stored, ([, count]) => count)).toBeLessThanOrEqual(
        DEPENDENCY_EVIDENCE_RESULTS_MAX
      );
      expect(
        Object.fromEntries(
          stored.map(([type]) => [
            type,
            sent.filter((item) => item.type === type).length,
          ])
        )
      ).toStrictEqual(Object.fromEntries(stored));
    }
  );

  test.each(EVIDENCE_PAIRS)(
    "$source -> $target: nenhuma ocorrência é mais fraca que o par, e com tudo no fio a mais fraca é a do par",
    ({ source, target }) => {
      const row = rowOfPair(source, target);
      const evidence = evidenceOf(source, target);
      const everyOccurrenceSent =
        evidence.length ===
        sumOver(typeEntries(row?.types ?? {}), ([, n]) => n);

      for (const item of evidence) {
        expect(STRENGTH[item.resolution]).toBeGreaterThanOrEqual(
          STRENGTH[row?.resolution ?? "RESOLVED"]
        );
      }
      expect(everyOccurrenceSent).toBe(true);
      expect(weakest(evidence.map((item) => item.resolution))).toBe(
        row?.resolution
      );
    }
  );

  test.each(EVIDENCE_PAIRS)(
    "$source -> $target: a evidência está no arquivo da origem",
    ({ source, target }) => {
      for (const item of evidenceOf(source, target)) {
        expect(item.filePath).toBe(detailOf(source).filePath);
      }
    }
  );

  test.each(EVIDENCE_PAIRS)(
    "$source -> $target: fromMethod é da origem e contém a linha; nulo, a linha está fora de todo método",
    ({ source, target }) => {
      const { methods } = detailOf(source);
      for (const item of evidenceOf(source, target)) {
        const listed = methods.find(
          (method) => method.elementId === item.fromMethod?.elementId
        );
        const containing = methods.filter(
          (method) =>
            item.line !== null &&
            method.lines !== null &&
            method.lines.start <= item.line &&
            item.line <= method.lines.end
        );
        expect(
          containing.map((method) => method.elementId),
          `${item.type} ${item.line}`
        ).toStrictEqual(listed === undefined ? [] : [listed.elementId]);
        expect(item.fromMethod === null || listed !== undefined).toBe(true);
        expect(item.fromMethod?.ownerId ?? source).toBe(source);
      }
    }
  );

  test.each(EVIDENCE_PAIRS)(
    "$source -> $target: um método citado traz o dono, o rótulo e a assinatura do detalhe do dono",
    ({ source, target }) => {
      for (const item of evidenceOf(source, target)) {
        for (const method of [item.fromMethod, item.toMethod]) {
          if (method !== null) {
            const owner = detailOf(method.ownerId);
            expect({
              belongs: method.elementId.startsWith(
                methodPrefixOf(method.ownerId)
              ),
              ownerLabel: method.ownerLabel,
              signature: method.signature,
            }).toStrictEqual({
              belongs: true,
              ownerLabel: owner.label,
              signature: owner.methods.find(
                (listed) => listed.elementId === method.elementId
              )?.signature,
            });
          }
        }
        expect(item.toMethod === null || item.type === "CALLS").toBe(true);
      }
    }
  );

  test("o mundo tem um toMethod preenchido e um CALLS sem toMethod: nulo não é 'não chama'", () => {
    const calls = EVIDENCE_PAIRS.flatMap(({ source, target }) =>
      evidenceOf(source, target).filter((item) => item.type === "CALLS")
    );

    expect(calls.some((item) => item.toMethod !== null)).toBe(true);
    expect(calls.some((item) => item.toMethod === null)).toBe(true);
  });
});
