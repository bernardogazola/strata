import { describe, expect, test } from "vitest";
import { queryKeys } from "@/queries/keys";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const CATALOG = "type:a.Catalog";
const STORE = "type:a.Store";

describe("as chaves de element.*", () => {
  test("a evidência fica sob structure.all, que STRUCTURAL_GRAPH invalida", () => {
    const queryKey = queryKeys.structure.dependencyEvidence(
      AN,
      CATALOG,
      STORE,
      50
    );

    expect(queryKey.slice(0, 3)).toStrictEqual(queryKeys.structure.all(AN));
  });

  test("a chave da evidência é o par ordenado, as pontas do pedido", () => {
    expect(
      queryKeys.structure.dependencyEvidence(AN, CATALOG, STORE, 50)
    ).toStrictEqual([
      "analysis",
      AN,
      "structure",
      "edge",
      CATALOG,
      STORE,
      "evidence",
      50,
    ]);
  });

  test("dois limits de evidência são duas entradas: o rodízio escolhe outras linhas", () => {
    expect(
      queryKeys.structure.dependencyEvidence(AN, CATALOG, STORE, 2)
    ).not.toStrictEqual(
      queryKeys.structure.dependencyEvidence(AN, CATALOG, STORE, 50)
    );
  });

  test("o par trocado é outra entrada de cache: A -> B não é B -> A", () => {
    expect(
      queryKeys.structure.dependencyEvidence(AN, STORE, CATALOG, 50)
    ).not.toStrictEqual(
      queryKeys.structure.dependencyEvidence(AN, CATALOG, STORE, 50)
    );
  });

  test("IN e OUT são duas entradas, cada uma com o seu cursor, sob o elemento", () => {
    const element = queryKeys.structure.element(AN, CATALOG);
    const incoming = queryKeys.structure.dependencies(AN, CATALOG, "IN", 500);
    const outgoing = queryKeys.structure.dependencies(AN, CATALOG, "OUT", 500);

    expect(incoming).not.toStrictEqual(outgoing);
    expect(incoming.slice(0, element.length)).toStrictEqual(element);
    expect(outgoing.slice(0, element.length)).toStrictEqual(element);
  });

  test("dois limits são duas entradas: a página de 50 não é a de 500", () => {
    expect(
      queryKeys.structure.dependencies(AN, CATALOG, "IN", 50)
    ).not.toStrictEqual(
      queryKeys.structure.dependencies(AN, CATALOG, "IN", 500)
    );
  });

  test("a chave das dependências é elemento, direção e limit, sem o cursor", () => {
    expect(
      queryKeys.structure.dependencies(AN, CATALOG, "OUT", 500)
    ).toStrictEqual([
      "analysis",
      AN,
      "structure",
      "element",
      CATALOG,
      "deps",
      "OUT",
      500,
    ]);
  });
});
