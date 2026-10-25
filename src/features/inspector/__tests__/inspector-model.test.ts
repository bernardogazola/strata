import { describe, expect, test } from "vitest";
import {
  DependencyPageContractError,
  type DependencyRows,
  flattenDependencyPages,
  isInspectableId,
  packageLineOf,
  verbsOf,
} from "@/features/inspector/inspector-model";
import {
  dependencyTypeLabels,
  dependencyTypePassiveLabels,
} from "@/i18n/labels";
import type { DependencyDirection, DependencyType } from "@/protocol/enums";
import type { DependencyEntry, DependencyPage } from "@/protocol/graph";
import {
  CURSORS,
  externalTypeRow,
  internalTypeRow,
  NEXT_CURSOR,
  pageOf,
} from "@/test/dependency-rows";
import {
  DESCRIBED_IDS,
  dependencyPageOf,
  detailOf,
  packageIdByLabel,
} from "@/test/fixture-graph";

const CORE_ID = packageIdByLabel("core");
const typeIdOf = (label: string): string => {
  const id = DESCRIBED_IDS.find(
    (candidate) =>
      candidate.startsWith("type:") && detailOf(candidate).label === label
  );
  if (id === undefined) {
    throw new Error(`nenhum tipo descrito tem o rótulo ${label}`);
  }
  return id;
};
const CATALOG_ID = typeIdOf("Catalog");
const CATALOG_ENTRY_ID = typeIdOf("CatalogEntry");

const PACKAGE_ID_PREFIX = "pkg:";

const TYPE_EDGE_ORDER: readonly DependencyType[] = [
  "IMPORTS",
  "EXTENDS",
  "IMPLEMENTS",
  "CALLS",
  "FIELD_TYPE",
  "PARAMETER_TYPE",
  "RETURN_TYPE",
  "ANNOTATION_REFERENCE",
];

const rowIdsOf = (rows: DependencyRows): string[] =>
  rows.items.flatMap((item) =>
    item.type === "row" ? [item.entry.elementId] : []
  );

const violationOf = (
  pages: readonly DependencyPage[],
  direction: DependencyDirection
): DependencyPageContractError | undefined => {
  try {
    flattenDependencyPages(pages, direction);
  } catch (error) {
    if (error instanceof DependencyPageContractError) {
      return error;
    }
    throw error;
  }
};

describe("isInspectableId", () => {
  test("aceita todo pacote e todo tipo que o mundo descreve", () => {
    expect(DESCRIBED_IDS.length).toBeGreaterThan(0);
    for (const id of DESCRIBED_IDS) {
      expect(isInspectableId(id), id).toBe(true);
    }
  });

  test("recusa grupo, método, arquivo e a falta de seleção", () => {
    const catalog = detailOf(CATALOG_ID);
    const methodId = catalog.methods[0]?.elementId ?? "";
    const fileId = `file:${catalog.filePath}`;

    expect(methodId.startsWith("method:")).toBe(true);
    expect(isInspectableId("pfx:br.pucpr")).toBe(false);
    expect(isInspectableId(methodId)).toBe(false);
    expect(isInspectableId(fileId)).toBe(false);
    expect(isInspectableId(null)).toBe(false);
    expect(isInspectableId(undefined)).toBe(false);
    expect(isInspectableId("")).toBe(false);
  });
});

describe("flattenDependencyPages: o mundo", () => {
  test("CatalogEntry OUT: a interna, o cabeçalho das externas e java.lang.String", () => {
    const page = dependencyPageOf(CATALOG_ENTRY_ID, "OUT");
    const [kind, string] = page.items;

    const rows = flattenDependencyPages([page], "OUT");

    expect(page.totals.out).toBe(1);
    expect(page.totals.outExternal).toBe(1);
    expect(rows.items).toStrictEqual([
      { entry: kind, index: 0, type: "row" },
      {
        count: page.totals.outExternal,
        index: page.totals.out,
        type: "externals-heading",
      },
      { entry: string, index: 2, type: "row" },
    ]);
    expect(rows.reachable).toBe(
      page.totals.out + (page.totals.outExternal ?? 0) + 1
    );
    expect(rows.totals).toStrictEqual(page.totals);
  });

  test("Catalog OUT: sem externas, sem cabeçalho, as linhas na ordem do fio", () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");

    const rows = flattenDependencyPages([page], "OUT");

    expect(page.totals.outExternal).toBe(0);
    expect(rows.items).toStrictEqual(
      page.items.map((entry, index) => ({ entry, index, type: "row" }))
    );
    expect(rows.reachable).toBe(page.totals.out);
  });

  test("um pacote: OUT conta só `out`, e `outExternal` nulo não vira cabeçalho", () => {
    const page = dependencyPageOf(CORE_ID, "OUT");

    const rows = flattenDependencyPages([page], "OUT");

    expect(page.totals.outExternal).toBeNull();
    expect(rows.items.some((item) => item.type === "externals-heading")).toBe(
      false
    );
    expect(rows.reachable).toBe(page.totals.out);
  });

  test("IN conta `in`, e nunca tem cabeçalho de externas", () => {
    const page = dependencyPageOf(CATALOG_ENTRY_ID, "IN");

    const rows = flattenDependencyPages([page], "IN");

    expect(page.totals.outExternal).toBe(1);
    expect(rowIdsOf(rows)).toStrictEqual(
      page.items.map((entry) => entry.elementId)
    );
    expect(rows.items.some((item) => item.type === "externals-heading")).toBe(
      false
    );
    expect(rows.reachable).toBe(page.totals.in);
  });

  test("uma direção vazia e sem cursor achata em nada", () => {
    const page = dependencyPageOf(typeIdOf("Audited"), "OUT");

    const rows = flattenDependencyPages([page], "OUT");

    expect(page.items).toStrictEqual([]);
    expect(page.nextCursor).toBeNull();
    expect(rows.items).toStrictEqual([]);
    expect(rows.reachable).toBe(0);
  });
});

describe("flattenDependencyPages: a ordem é do Java", () => {
  test("nunca reordena: linhas fora da ordem de peso e de id ficam como vieram", () => {
    const items = [
      internalTypeRow(3, { weight: 1 }),
      internalTypeRow(2, { weight: 5 }),
      internalTypeRow(1, { weight: 9 }),
    ];
    const totals = { in: 3, out: 0, outExternal: null };

    const rows = flattenDependencyPages([pageOf(items, totals)], "IN");

    expect(rowIdsOf(rows)).toStrictEqual(items.map((item) => item.elementId));
  });

  test("páginas seguidas continuam o índice, e o cabeçalho entra uma vez, em `out`", () => {
    const totals = { in: 0, out: 2, outExternal: 2 };
    const pages = [
      pageOf([internalTypeRow(1), internalTypeRow(2)], totals, CURSORS[0]),
      pageOf([externalTypeRow(3)], totals, CURSORS[1]),
      pageOf([externalTypeRow(4)], totals),
    ];

    const rows = flattenDependencyPages(pages, "OUT");

    expect(rows.items.map((item) => [item.type, item.index])).toStrictEqual([
      ["row", 0],
      ["row", 1],
      ["externals-heading", totals.out],
      ["row", 3],
      ["row", 4],
    ]);
    expect(rows.reachable).toBe(totals.out + totals.outExternal + 1);
  });

  test("sem externa carregada ainda, nenhum cabeçalho, mas o alcance já o conta", () => {
    const totals = { in: 0, out: 2, outExternal: 3 };

    const rows = flattenDependencyPages(
      [pageOf([internalTypeRow(1)], totals, NEXT_CURSOR)],
      "OUT"
    );

    expect(rows.items.map((item) => item.type)).toStrictEqual(["row"]);
    expect(rows.reachable).toBe(totals.out + totals.outExternal + 1);
  });
});

describe("flattenDependencyPages: o contrato do fio", () => {
  const totals = { in: 0, out: 2, outExternal: 1 };

  test("totais que mudam entre páginas: TOTALS_CHANGED", () => {
    const pages = [
      pageOf([internalTypeRow(1)], totals, NEXT_CURSOR),
      pageOf([internalTypeRow(2)], { ...totals, out: totals.out + 1 }),
    ];

    expect(violationOf(pages, "OUT")?.violation).toBe("TOTALS_CHANGED");
  });

  test("uma página vazia com cursor: EMPTY_PAGE_WITH_CURSOR, também na primeira", () => {
    const afterRows = [
      pageOf([internalTypeRow(1)], totals, CURSORS[0]),
      pageOf([], totals, CURSORS[1]),
    ];
    const first = [pageOf([], totals, NEXT_CURSOR)];

    expect(violationOf(afterRows, "OUT")?.violation).toBe(
      "EMPTY_PAGE_WITH_CURSOR"
    );
    expect(violationOf(first, "OUT")?.violation).toBe("EMPTY_PAGE_WITH_CURSOR");
  });

  test("uma interna depois de uma externa: INTERNAL_AFTER_EXTERNAL, na página e entre páginas", () => {
    const samePage = [pageOf([externalTypeRow(1), internalTypeRow(2)], totals)];
    const acrossPages = [
      pageOf([internalTypeRow(1), externalTypeRow(2)], totals, NEXT_CURSOR),
      pageOf([internalTypeRow(3)], totals),
    ];

    expect(violationOf(samePage, "OUT")?.violation).toBe(
      "INTERNAL_AFTER_EXTERNAL"
    );
    expect(violationOf(acrossPages, "OUT")?.violation).toBe(
      "INTERNAL_AFTER_EXTERNAL"
    );
  });

  test("o erro é um Error nomeado, com a violação na mensagem", () => {
    const error = violationOf([pageOf([], totals, NEXT_CURSOR)], "OUT");

    expect(error).toBeInstanceOf(Error);
    expect(error?.name).toBe("DependencyPageContractError");
    expect(error?.message).toContain("EMPTY_PAGE_WITH_CURSOR");
  });
});

describe("verbsOf", () => {
  const store = dependencyPageOf(CATALOG_ID, "OUT").items.find(
    (entry) => detailOf(entry.elementId).label === "Store"
  ) as DependencyEntry;
  const STORE_TYPES: readonly DependencyType[] = [
    "IMPORTS",
    "CALLS",
    "FIELD_TYPE",
    "PARAMETER_TYPE",
  ];

  test("a fixture tem os quatro tipos que o teste afirma", () => {
    expect(Object.keys(store.types).sort()).toStrictEqual(
      [...STORE_TYPES].sort()
    );
  });

  test("OUT usa a voz passiva, na ordem do enum, unida por vírgula", () => {
    expect(verbsOf(store.types, "OUT")).toBe(
      STORE_TYPES.map((type) => dependencyTypePassiveLabels[type]).join(", ")
    );
  });

  test("IN usa a voz ativa, na mesma ordem", () => {
    expect(verbsOf(store.types, "IN")).toBe(
      STORE_TYPES.map((type) => dependencyTypeLabels[type]).join(", ")
    );
  });

  test("os oito tipos de aresta, gravados ao contrário, saem na ordem do enum", () => {
    const reversed = Object.fromEntries(
      [...TYPE_EDGE_ORDER].reverse().map((type) => [type, 1])
    ) as DependencyEntry["types"];

    expect(verbsOf(reversed, "OUT")).toBe(
      TYPE_EDGE_ORDER.map((type) => dependencyTypePassiveLabels[type]).join(
        ", "
      )
    );
    expect(verbsOf(reversed, "IN")).toBe(
      TYPE_EDGE_ORDER.map((type) => dependencyTypeLabels[type]).join(", ")
    );
  });

  test("um tipo só é o verbo sozinho, sem vírgula", () => {
    expect(verbsOf({ RETURN_TYPE: 2 }, "OUT")).toBe(
      dependencyTypePassiveLabels.RETURN_TYPE
    );
    expect(verbsOf({ RETURN_TYPE: 2 }, "IN")).toBe(
      dependencyTypeLabels.RETURN_TYPE
    );
  });
});

describe("packageLineOf", () => {
  const catalogPackage = detailOf(CATALOG_ID).packageId;
  const catalogOut = dependencyPageOf(CATALOG_ID, "OUT").items;
  const byLabel = (label: string): DependencyEntry =>
    catalogOut.find(
      (entry) => detailOf(entry.elementId).label === label
    ) as DependencyEntry;

  test("Store, em core.spi, ganha o nome qualificado do pacote dele", () => {
    const store = byLabel("Store");
    const expected = detailOf(store.packageId ?? "").qualifiedName;

    expect(store.packageId).not.toBe(catalogPackage);
    expect(store.packageId).toBe(`${PACKAGE_ID_PREFIX}${expected}`);
    expect(packageLineOf(store, catalogPackage)).toBe(expected);
  });

  test("Audited, no mesmo pacote de Catalog, não tem linha de pacote", () => {
    const audited = byLabel("Audited");

    expect(audited.packageId).toBe(catalogPackage);
    expect(packageLineOf(audited, catalogPackage)).toBeNull();
  });

  test("uma linha de pacote e uma externa nunca têm linha de pacote", () => {
    const [corePackageRow] = dependencyPageOf(CORE_ID, "OUT").items;
    const string = dependencyPageOf(CATALOG_ENTRY_ID, "OUT").items.find(
      (entry) => entry.isExternal
    );

    expect(corePackageRow?.kind).toBe("PACKAGE");
    expect(string?.packageId).toBeNull();
    expect(packageLineOf(corePackageRow as DependencyEntry, null)).toBeNull();
    expect(
      packageLineOf(
        string as DependencyEntry,
        detailOf(CATALOG_ENTRY_ID).packageId
      )
    ).toBeNull();
  });

  test("em todo o mundo: a linha aparece exatamente nas internas de tipo de outro pacote", () => {
    const typeIds = DESCRIBED_IDS.filter((id) => detailOf(id).kind === "TYPE");
    for (const id of typeIds) {
      const inspected = detailOf(id).packageId;
      for (const direction of ["IN", "OUT"] as const) {
        for (const entry of dependencyPageOf(id, direction).items) {
          const crossPackage =
            !entry.isExternal &&
            entry.kind === "TYPE" &&
            entry.packageId !== inspected;
          const expected = crossPackage
            ? detailOf(entry.packageId ?? "").qualifiedName
            : null;

          expect(
            packageLineOf(entry, inspected),
            `${id} ${direction} ${entry.elementId}`
          ).toBe(expected);
        }
      }
    }
  });
});
