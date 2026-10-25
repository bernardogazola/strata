import { describe, expect, test } from "vitest";
import { dependencyTypeSchema } from "@/protocol/enums";
import { dependencyTypeLabels, dependencyTypePassiveLabels } from "../labels";

const firstWord = (label: string): string => label.split(" ")[0] ?? "";

const PARTICIPLE = /(?:ado|ido)$/u;

const DEVOLVER = /devolv/iu;

describe("os verbos na voz passiva", () => {
  test("há uma forma para cada tipo de dependência", () => {
    expect(Object.keys(dependencyTypePassiveLabels).sort()).toStrictEqual(
      [...dependencyTypeSchema.options].sort()
    );
  });

  test("são os particípios aprovados para as oito arestas entre tipos", () => {
    expect(dependencyTypePassiveLabels).toMatchObject({
      ANNOTATION_REFERENCE: "referenciado como anotação",
      CALLS: "chamado",
      EXTENDS: "estendido",
      FIELD_TYPE: "usado como tipo de campo",
      IMPLEMENTS: "implementado",
      IMPORTS: "importado",
      PARAMETER_TYPE: "recebido como parâmetro",
      RETURN_TYPE: "retornado",
    });
  });

  test("toda forma passiva abre com um particípio, e nenhuma ativa abre assim", () => {
    for (const type of dependencyTypeSchema.options) {
      expect(firstWord(dependencyTypePassiveLabels[type]), type).toMatch(
        PARTICIPLE
      );
      expect(firstWord(dependencyTypeLabels[type]), type).not.toMatch(
        PARTICIPLE
      );
    }
  });

  test("nenhum tipo tem a mesma forma nas duas vozes", () => {
    for (const type of dependencyTypeSchema.options) {
      expect(dependencyTypePassiveLabels[type], type).not.toBe(
        dependencyTypeLabels[type]
      );
    }
  });
});

describe("os verbos na voz ativa", () => {
  test("RETURN_TYPE é 'retorna', e nenhum verbo diz 'devolve'", () => {
    expect(dependencyTypeLabels.RETURN_TYPE).toBe("retorna");
    for (const label of [
      ...Object.values(dependencyTypeLabels),
      ...Object.values(dependencyTypePassiveLabels),
    ]) {
      expect(label).not.toMatch(DEVOLVER);
    }
  });
});
