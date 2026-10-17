import { describe, expect, expectTypeOf, test } from "vitest";
import type { z } from "zod";
import {
  type RepositorySearch,
  repositorySearchSchema,
  resolveGraphView,
} from "@/features/shell/repository-search";
import type { GraphLevel } from "@/protocol/enums";

const ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const PACKAGE_ID = "pkg:br.pucpr.orders";
const TYPE_ID = "type:br.pucpr.orders.Order";

describe("repositorySearchSchema", () => {
  test("uma URL sem nenhum param abre o nível PACKAGE, sem análise", () => {
    expect(repositorySearchSchema.parse({})).toStrictEqual({
      level: "PACKAGE",
    });
  });

  test("params válidos passam como vieram", () => {
    const search = {
      analysis: ANALYSIS_ID,
      focus: TYPE_ID,
      level: "TYPE",
      parent: PACKAGE_ID,
    };

    expect(repositorySearchSchema.parse(search)).toStrictEqual(search);
  });

  test("um level inválido cai em PACKAGE sem apagar o parent válido", () => {
    const parsed = repositorySearchSchema.parse({
      analysis: ANALYSIS_ID,
      level: "PACOTE",
      parent: PACKAGE_ID,
    });

    expect(parsed.level).toBe("PACKAGE");
    expect(parsed.parent).toBe(PACKAGE_ID);
    expect(parsed.analysis).toBe(ANALYSIS_ID);
  });

  test("cada campo inválido vira o seu padrão sozinho", () => {
    const parsed = repositorySearchSchema.parse({
      analysis: "an_minúsculo",
      focus: "OrderService",
      level: 3,
      parent: "/etc/passwd",
    });

    expect(parsed).toStrictEqual({
      analysis: undefined,
      focus: undefined,
      level: "PACKAGE",
      parent: undefined,
    });
  });

  test("campos desconhecidos não sobrevivem à validação", () => {
    expect(
      repositorySearchSchema.parse({ path: "/Users/alguem/repo" })
    ).not.toHaveProperty("path");
  });

  test("um link pode omitir todos os campos, level inclusive", () => {
    expectTypeOf<z.input<typeof repositorySearchSchema>>().toEqualTypeOf<{
      analysis?: string | undefined;
      focus?: string | undefined;
      level?: GraphLevel | undefined;
      parent?: string | undefined;
    }>();
    expectTypeOf<RepositorySearch["level"]>().toEqualTypeOf<GraphLevel>();
  });
});

describe("resolveGraphView", () => {
  const search = (patch: Partial<RepositorySearch>): RepositorySearch =>
    repositorySearchSchema.parse({ analysis: ANALYSIS_ID, ...patch });

  test("PACKAGE pede o nível 1 e ignora o parent", () => {
    expect(
      resolveGraphView(search({ level: "PACKAGE", parent: PACKAGE_ID }))
    ).toStrictEqual({ level: "PACKAGE" });
  });

  test("TYPE com parent pede os tipos do pacote", () => {
    expect(
      resolveGraphView(search({ level: "TYPE", parent: PACKAGE_ID }))
    ).toStrictEqual({ level: "TYPE", parentId: PACKAGE_ID });
  });

  test("TYPE_NEIGHBORHOOD com parent cai no nível 1 até haver a vista da vizinhança", () => {
    expect(
      resolveGraphView(search({ level: "TYPE_NEIGHBORHOOD", parent: TYPE_ID }))
    ).toStrictEqual({ level: "PACKAGE" });
  });

  test("abaixo de PACKAGE sem parent cai no nível 1", () => {
    expect(resolveGraphView(search({ level: "TYPE" }))).toStrictEqual({
      level: "PACKAGE",
    });
    expect(
      resolveGraphView(search({ level: "TYPE_NEIGHBORHOOD" }))
    ).toStrictEqual({ level: "PACKAGE" });
  });
});
