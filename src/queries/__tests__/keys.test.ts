import { describe, expect, test } from "vitest";
import { queryKeys } from "@/queries/keys";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const CATALOG = "type:a.Catalog";
const STORE = "type:a.Store";

describe("as chaves de element.*", () => {
  test("a evidência fica sob structure.all, que STRUCTURAL_GRAPH invalida", () => {
    const queryKey = queryKeys.structure.dependencyEvidence(AN, CATALOG, STORE);

    expect(queryKey.slice(0, 3)).toStrictEqual(queryKeys.structure.all(AN));
  });

  test("a chave da evidência é o par ordenado, as pontas do pedido", () => {
    expect(
      queryKeys.structure.dependencyEvidence(AN, CATALOG, STORE)
    ).toStrictEqual([
      "analysis",
      AN,
      "structure",
      "edge",
      CATALOG,
      STORE,
      "evidence",
    ]);
  });

  test("o par trocado é outra entrada de cache: A -> B não é B -> A", () => {
    expect(
      queryKeys.structure.dependencyEvidence(AN, STORE, CATALOG)
    ).not.toStrictEqual(
      queryKeys.structure.dependencyEvidence(AN, CATALOG, STORE)
    );
  });

  test("IN e OUT são duas entradas, cada uma com o seu cursor, sob o elemento", () => {
    const element = queryKeys.structure.element(AN, CATALOG);
    const incoming = queryKeys.structure.dependencies(AN, CATALOG, "IN");
    const outgoing = queryKeys.structure.dependencies(AN, CATALOG, "OUT");

    expect(incoming).not.toStrictEqual(outgoing);
    expect(incoming.slice(0, element.length)).toStrictEqual(element);
    expect(outgoing.slice(0, element.length)).toStrictEqual(element);
  });
});
