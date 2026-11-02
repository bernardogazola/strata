import { describe, expect, test } from "vitest";
import {
  DependencyPageContractError,
  flattenDependencyPages,
} from "@/features/inspector/inspector-model";
import { dependencyPagesQueryOptions } from "@/queries/options";
import { NEXT_CURSOR, pageOf } from "@/test/dependency-rows";
import { WORLD_ANALYSIS_ID } from "@/test/inspector";
import { inTotals, syntheticRows } from "./paging-transport";

const violationOf = (
  run: () => unknown
): DependencyPageContractError["violation"] | null => {
  try {
    run();
    return null;
  } catch (error) {
    if (error instanceof DependencyPageContractError) {
      return error.violation;
    }
    throw error;
  }
};

describe("DUPLICATE_ROW", () => {
  test("uma linha da página 1 que volta na página 2 é erro de contrato", () => {
    const pages = [
      pageOf(syntheticRows(0, 10), inTotals(15), NEXT_CURSOR),
      pageOf(syntheticRows(9, 5), inTotals(15)),
    ];

    expect(violationOf(() => flattenDependencyPages(pages, "IN"))).toBe(
      "DUPLICATE_ROW"
    );
  });

  test("a mesma linha duas vezes em uma página só também", () => {
    const [first] = syntheticRows(0, 1);
    const pages = [
      pageOf(first === undefined ? [] : [first, first], inTotals(2)),
    ];

    expect(violationOf(() => flattenDependencyPages(pages, "IN"))).toBe(
      "DUPLICATE_ROW"
    );
  });

  test("páginas sem repetição passam, na ordem do fio", () => {
    const pages = [
      pageOf(syntheticRows(0, 10), inTotals(15), NEXT_CURSOR),
      pageOf(syntheticRows(10, 5), inTotals(15)),
    ];

    const { items } = flattenDependencyPages(pages, "IN");

    expect(
      items.map((item) => (item.type === "row" ? item.entry.label : null))
    ).toStrictEqual(syntheticRows(0, 15).map((entry) => entry.label));
  });
});

describe("o select das páginas", () => {
  test("é a mesma função em toda chamada da fábrica, por direção", () => {
    const out = dependencyPagesQueryOptions(
      WORLD_ANALYSIS_ID,
      "type:a.A",
      "OUT"
    );
    const otherOut = dependencyPagesQueryOptions("an_other", "type:b.B", "OUT");
    const inbound = dependencyPagesQueryOptions(
      WORLD_ANALYSIS_ID,
      "type:a.A",
      "IN"
    );

    expect(out.select).toBe(otherOut.select);
    expect(out.select).not.toBe(inbound.select);
  });

  test("cada direção conta o alcance da sua", () => {
    const totals = { in: 2, out: 3, outExternal: 4 };
    const data = { pageParams: [null], pages: [pageOf([], totals)] };
    const out = dependencyPagesQueryOptions(
      WORLD_ANALYSIS_ID,
      "type:a.A",
      "OUT"
    );
    const inbound = dependencyPagesQueryOptions(
      WORLD_ANALYSIS_ID,
      "type:a.A",
      "IN"
    );

    expect(out.select?.(data).reachable).toBe(
      totals.out + totals.outExternal + 1
    );
    expect(inbound.select?.(data).reachable).toBe(totals.in);
  });
});
