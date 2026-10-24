import { describe, expect, test } from "vitest";
import { z } from "zod";
import {
  edgeIdSchema,
  graphNodeIdSchema,
  methodIdSchema,
  packageIdSchema,
  packageOrTypeIdSchema,
  prefixIdSchema,
  rootRelativePathSchema,
  SEARCH_QUERY_MAX_CHARS,
  searchQuerySchema,
  typeIdSchema,
} from "../primitives";

const EMOJI = String.fromCodePoint(0x1_f6_00);

const MAX_ID_CHARS = 4096;

describe("a unidade do .max() do Zod", () => {
  test("conta code points, e não unidades UTF-16: três emoji cabem em max(3)", () => {
    const threeEmoji = EMOJI.repeat(3);
    const maxThree = z.string().max(3);

    const emojiAccepted = maxThree.safeParse(threeEmoji).success;
    const fourAccepted = maxThree.safeParse("aaaa").success;

    expect(threeEmoji).toHaveLength(6);
    expect(emojiAccepted).toBe(true);
    expect(fourAccepted).toBe(false);
  });

  test("a consulta de busca conta como o Java: 128 code points passam e 129 reprovam", () => {
    expect(
      searchQuerySchema.safeParse(EMOJI.repeat(SEARCH_QUERY_MAX_CHARS)).success
    ).toBe(true);
    expect(
      searchQuerySchema.safeParse(
        `${"x".repeat(SEARCH_QUERY_MAX_CHARS)}${EMOJI}`
      ).success
    ).toBe(false);
  });
});

describe("prefixIdSchema", () => {
  test("aceita um prefixo de um segmento e um pontuado", () => {
    expect(prefixIdSchema.safeParse("pfx:a").success).toBe(true);
    expect(prefixIdSchema.safeParse("pfx:org.elasticsearch").success).toBe(
      true
    );
    expect(prefixIdSchema.safeParse("pfx:br.pucpr.ação").success).toBe(true);
  });

  test("recusa o prefixo vazio e todo segmento vazio", () => {
    for (const malformed of ["pfx:", "pfx:.a", "pfx:a.", "pfx:a..b"]) {
      expect(prefixIdSchema.safeParse(malformed).success, malformed).toBe(
        false
      );
    }
  });

  test("recusa um id de elemento: um grupo nunca se passa por pacote", () => {
    expect(prefixIdSchema.safeParse("pkg:a").success).toBe(false);
    expect(prefixIdSchema.safeParse("a").success).toBe(false);
  });

  test("o teto é o do id de elemento", () => {
    const prefix = "pfx:";
    const atCeiling = `${prefix}${"a".repeat(MAX_ID_CHARS - prefix.length)}`;

    expect(atCeiling).toHaveLength(MAX_ID_CHARS);
    expect(prefixIdSchema.safeParse(atCeiling).success).toBe(true);
    expect(prefixIdSchema.safeParse(`${atCeiling}a`).success).toBe(false);
  });
});

describe("graphNodeIdSchema", () => {
  test("aceita os ids de pacote, de tipo e de grupo", () => {
    for (const id of [
      "pkg:",
      "pkg:br.pucpr.legacy.core",
      "type:a.T",
      "pfx:a",
    ]) {
      expect(graphNodeIdSchema.safeParse(id).success, id).toBe(true);
    }
  });

  test("recusa um grupo malformado e um prefixo de id desconhecido", () => {
    expect(graphNodeIdSchema.safeParse("pfx:").success).toBe(false);
    expect(graphNodeIdSchema.safeParse("grp:a").success).toBe(false);
  });
});

describe("methodIdSchema", () => {
  test("aceita um método, um construtor e um método sem parâmetro", () => {
    for (const id of [
      "method:br.pucpr.orders.OrderService#pay(br.pucpr.orders.Order,java.util.UUID)",
      "method:br.pucpr.orders.Order#<init>(java.util.UUID)",
      "method:br.pucpr.orders.OrderService#total()",
    ]) {
      expect(methodIdSchema.safeParse(id).success, id).toBe(true);
    }
  });

  test("recusa todo outro prefixo: um tipo ou um pacote ali é divergência de versão", () => {
    for (const id of [
      "type:a.T",
      "pkg:a",
      "file:a/T.java",
      "pfx:a",
      "a.T#m()",
    ]) {
      expect(methodIdSchema.safeParse(id).success, id).toBe(false);
    }
  });

  test("o teto é o do id de elemento", () => {
    const prefix = "method:a.T#m(";
    const atCeiling = `${prefix}${"a".repeat(MAX_ID_CHARS - prefix.length - 1)})`;

    expect(atCeiling).toHaveLength(MAX_ID_CHARS);
    expect(methodIdSchema.safeParse(atCeiling).success).toBe(true);
    expect(methodIdSchema.safeParse(`${atCeiling}a`).success).toBe(false);
  });
});

describe("edgeIdSchema", () => {
  test("aceita o vetor de teste do FNV-1a-64: e_ e 16 hex minúsculos", () => {
    expect(edgeIdSchema.safeParse("e_af63dc4c8601ec8c").success).toBe(true);
  });

  test("recusa toda outra forma: o id é chave de junção, e um id torto não junta nada", () => {
    for (const malformed of [
      "e_AF63DC4C8601EC8C",
      "e_af63dc4c8601ec8",
      "e_af63dc4c8601ec8c0",
      "e-af63dc4c8601ec8c",
      "af63dc4c8601ec8c",
      "e_af63dc4c8601ec8g",
      "e_",
      "",
    ]) {
      expect(edgeIdSchema.safeParse(malformed).success, malformed).toBe(false);
    }
  });
});

describe("os ids estreitos de elemento", () => {
  const PACKAGE = "pkg:br.pucpr.legacy.core";
  const TYPE = "type:br.pucpr.legacy.core.Catalog";
  const METHOD = "method:br.pucpr.legacy.core.Catalog#size()";
  const FILE = "file:core/src/main/java/br/pucpr/legacy/core/Catalog.java";
  const GROUP = "pfx:br.pucpr";

  test.each([
    ["packageIdSchema", packageIdSchema, [PACKAGE, "pkg:"]],
    ["typeIdSchema", typeIdSchema, [TYPE]],
    ["packageOrTypeIdSchema", packageOrTypeIdSchema, [PACKAGE, "pkg:", TYPE]],
  ] as const)("%s aceita só o que o nome diz", (_name, schema, accepted) => {
    const all = [PACKAGE, "pkg:", TYPE, METHOD, FILE, GROUP, "a.T"];

    expect(all.filter((id) => schema.safeParse(id).success)).toStrictEqual(
      accepted
    );
  });

  test("o teto continua o do id de elemento", () => {
    const prefix = "type:";
    const atCeiling = `${prefix}${"a".repeat(MAX_ID_CHARS - prefix.length)}`;

    expect(typeIdSchema.safeParse(atCeiling).success).toBe(true);
    expect(typeIdSchema.safeParse(`${atCeiling}a`).success).toBe(false);
    expect(packageOrTypeIdSchema.safeParse(`${atCeiling}a`).success).toBe(
      false
    );
  });
});

describe("rootRelativePathSchema", () => {
  test("recusa uma barra inicial: um caminho absoluto nunca entra na WebView", () => {
    for (const absolute of ["/", "/Users/x/repo/A.java", "//server/share"]) {
      expect(rootRelativePathSchema.safeParse(absolute).success, absolute).toBe(
        false
      );
    }
  });

  test("recusa só a barra inicial: C:x, uma barra invertida e um ponto são nomes legais no Unix", () => {
    for (const relative of [
      "core/src/main/java/a/Catalog.java",
      "C:x",
      "C:/x/A.java",
      "a\\b.java",
      "./A.java",
      "A.java",
    ]) {
      expect(rootRelativePathSchema.safeParse(relative).success, relative).toBe(
        true
      );
    }
  });

  test("o teto é o de filePathSchema", () => {
    const atCeiling = "a".repeat(1024);

    expect(rootRelativePathSchema.safeParse(atCeiling).success).toBe(true);
    expect(rootRelativePathSchema.safeParse(`${atCeiling}a`).success).toBe(
      false
    );
  });
});
