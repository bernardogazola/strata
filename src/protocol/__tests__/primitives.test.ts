import { describe, expect, test } from "vitest";
import { z } from "zod";
import {
  graphNodeIdSchema,
  prefixIdSchema,
  SEARCH_QUERY_MAX_CHARS,
  searchQuerySchema,
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
