import { describe, expect, test } from "vitest";
import {
  EMPTY_TYPEAHEAD,
  extendTypeahead,
  intentOf,
  isTypeaheadKey,
  pinIndexes,
  resolveTabbable,
  resolveTarget,
  TYPEAHEAD_RESET_MS,
  toggleIntent,
  typeaheadIntent,
} from "@/features/outline/outline-navigation";
import {
  flattenOutline,
  type OutlineNodeRow,
  type OutlineRow,
} from "@/features/outline/use-outline";
import type { OutlineNode } from "@/protocol/graph";

const BILLING = "pkg:br.pucpr.billing";
const ORDERS = "pkg:br.pucpr.orders";
const SHARED = "pkg:br.pucpr.shared";
const EMPTY_PACKAGE = "pkg:br.pucpr.empty";
const ORDER = "type:br.pucpr.orders.Order";
const ORDER_SERVICE = "type:br.pucpr.orders.OrderService";

const RIGHT_TO_LEFT_OVERRIDE = String.fromCodePoint(0x20_2e);

const packageNode = (
  id: string,
  label: string,
  childCount: number
): OutlineNode => ({ childCount, id, kind: "PACKAGE", label });

const typeNode = (id: string, label: string): OutlineNode => ({
  childCount: 0,
  id,
  kind: "TYPE",
  label,
});

const ROOT = [
  packageNode(BILLING, "billing", 12),
  packageNode(ORDERS, "orders", 2),
  packageNode(EMPTY_PACKAGE, "empty", 0),
  packageNode(SHARED, "shared", 5),
];

const settled = (nodes: readonly OutlineNode[] | undefined) => ({
  failed: false,
  hasNextPage: false,
  nodes,
});

const OPEN_ROWS = flattenOutline(
  { ...settled(ROOT), hasNextPage: true },
  {
    ...settled([
      typeNode(ORDER, "Order"),
      typeNode(ORDER_SERVICE, "OrderService"),
    ]),
    parentId: ORDERS,
  }
);

const LOADING_ROWS = flattenOutline(settled(ROOT), {
  ...settled(undefined),
  parentId: ORDERS,
});

const FAILED_ROWS = flattenOutline(settled(ROOT), {
  ...settled(undefined),
  failed: true,
  parentId: ORDERS,
});

const indexByKeyOf = (rows: readonly OutlineRow[]) =>
  new Map(rows.map((row, index) => [row.key, index]));

const keyAt = (rows: readonly OutlineRow[], index: number | undefined) =>
  index === undefined ? undefined : rows[index]?.key;

const nodeRow = (rows: readonly OutlineRow[], key: string): OutlineNodeRow => {
  const row = rows.find((candidate) => candidate.key === key);
  if (row?.type !== "node") {
    throw new Error(`sem linha de nó ${key}`);
  }
  return row;
};

const moveTarget = (intent: ReturnType<typeof intentOf>) =>
  intent?.type === "move" ? intent.index : undefined;

describe("intentOf: setas, Home e End", () => {
  test("↓ e ↑ andam uma linha focável, e passam pelas pseudo-linhas focáveis", () => {
    const shared = OPEN_ROWS.findIndex((row) => row.key === SHARED);

    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("ArrowDown", OPEN_ROWS, 0, ORDERS)))
    ).toBe(ORDERS);
    expect(
      keyAt(
        OPEN_ROWS,
        moveTarget(intentOf("ArrowDown", OPEN_ROWS, shared, ORDERS))
      )
    ).toBe("more:root");
    expect(
      intentOf("ArrowDown", OPEN_ROWS, OPEN_ROWS.length - 1, ORDERS)
    ).toBeNull();
    expect(intentOf("ArrowUp", OPEN_ROWS, 0, ORDERS)).toBeNull();
  });

  test("as linhas só visuais ficam de fora: ↓ do pacote aberto pula os tipos a caminho", () => {
    expect(
      keyAt(
        LOADING_ROWS,
        moveTarget(intentOf("ArrowDown", LOADING_ROWS, 1, ORDERS))
      )
    ).toBe(EMPTY_PACKAGE);
    expect(
      keyAt(
        LOADING_ROWS,
        moveTarget(intentOf("ArrowUp", LOADING_ROWS, 3, ORDERS))
      )
    ).toBe(ORDERS);
  });

  test("Home vai à primeira linha e End à última, que pode ser o carregar mais", () => {
    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("Home", OPEN_ROWS, 4, ORDERS)))
    ).toBe(BILLING);
    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("End", OPEN_ROWS, 0, ORDERS)))
    ).toBe("more:root");
  });

  test("Enter e Espaço ativam; o resto, inclusive o *, não pede nada", () => {
    expect(intentOf("Enter", OPEN_ROWS, 0, ORDERS)).toStrictEqual({
      type: "activate",
    });
    expect(intentOf(" ", OPEN_ROWS, 0, ORDERS)).toStrictEqual({
      type: "activate",
    });
    expect(intentOf("*", OPEN_ROWS, 0, ORDERS)).toBeNull();
    expect(intentOf("PageDown", OPEN_ROWS, 0, ORDERS)).toBeNull();
  });
});

describe("intentOf: → e ←", () => {
  test("→ em um pacote fechado com tipos pede para abrir; em um pacote sem tipos, nada", () => {
    expect(intentOf("ArrowRight", OPEN_ROWS, 0, ORDERS)).toStrictEqual({
      packageId: BILLING,
      type: "expand",
    });
    const empty = OPEN_ROWS.findIndex((row) => row.key === EMPTY_PACKAGE);
    expect(intentOf("ArrowRight", OPEN_ROWS, empty, ORDERS)).toBeNull();
  });

  test("→ no pacote aberto vai ao primeiro filho; com os tipos a caminho, fica", () => {
    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("ArrowRight", OPEN_ROWS, 1, ORDERS)))
    ).toBe(ORDER);
    expect(intentOf("ArrowRight", LOADING_ROWS, 1, ORDERS)).toBeNull();
    expect(
      keyAt(
        FAILED_ROWS,
        moveTarget(intentOf("ArrowRight", FAILED_ROWS, 1, ORDERS))
      )
    ).toBe(`error:${ORDERS}`);
  });

  test("→ em um tipo ou em uma pseudo-linha não faz nada", () => {
    expect(intentOf("ArrowRight", OPEN_ROWS, 2, ORDERS)).toBeNull();
    expect(
      intentOf("ArrowRight", OPEN_ROWS, OPEN_ROWS.length - 1, ORDERS)
    ).toBeNull();
  });

  test("← em um filho, real ou pseudo-linha, volta ao pacote", () => {
    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("ArrowLeft", OPEN_ROWS, 3, ORDERS)))
    ).toBe(ORDERS);
    expect(
      keyAt(
        FAILED_ROWS,
        moveTarget(intentOf("ArrowLeft", FAILED_ROWS, 2, ORDERS))
      )
    ).toBe(ORDERS);
  });

  test("← no pacote aberto pede para fechar; em um pacote fechado ou na raiz, nada", () => {
    expect(intentOf("ArrowLeft", OPEN_ROWS, 1, ORDERS)).toStrictEqual({
      type: "collapse",
    });
    expect(intentOf("ArrowLeft", OPEN_ROWS, 0, ORDERS)).toBeNull();
    expect(
      intentOf("ArrowLeft", OPEN_ROWS, OPEN_ROWS.length - 1, ORDERS)
    ).toBeNull();
  });
});

describe("toggleIntent: a seta clicada", () => {
  test("fecha o aberto, abre o fechado com tipos, e ignora o pacote sem tipos", () => {
    expect(toggleIntent(nodeRow(OPEN_ROWS, ORDERS), ORDERS)).toStrictEqual({
      type: "collapse",
    });
    expect(toggleIntent(nodeRow(OPEN_ROWS, SHARED), ORDERS)).toStrictEqual({
      packageId: SHARED,
      type: "expand",
    });
    expect(toggleIntent(nodeRow(OPEN_ROWS, EMPTY_PACKAGE), ORDERS)).toBeNull();
  });
});

describe("type-ahead", () => {
  const press = (
    key: string,
    modifiers: Partial<Record<"altKey" | "ctrlKey" | "metaKey", boolean>> = {}
  ) => ({
    altKey: false,
    ctrlKey: false,
    key,
    metaKey: false,
    ...modifiers,
  });

  test("só um caractere imprimível sem modificador; Espaço e * têm outro papel", () => {
    expect(isTypeaheadKey(press("a"))).toBe(true);
    expect(isTypeaheadKey(press("Ç"))).toBe(true);
    expect(isTypeaheadKey(press(" "))).toBe(false);
    expect(isTypeaheadKey(press("*"))).toBe(false);
    expect(isTypeaheadKey(press("ArrowDown"))).toBe(false);
    expect(isTypeaheadKey(press("a", { ctrlKey: true }))).toBe(false);
    expect(isTypeaheadKey(press("a", { metaKey: true }))).toBe(false);
  });

  test("o buffer junta teclas próximas, em minúsculas pt-BR, e zera depois da pausa", () => {
    const first = extendTypeahead(EMPTY_TYPEAHEAD, "Ç", 1000);
    const second = extendTypeahead(first, "A", 1000 + TYPEAHEAD_RESET_MS);
    const third = extendTypeahead(second, "b", 1001 + 2 * TYPEAHEAD_RESET_MS);

    expect(first).toStrictEqual({ at: 1000, text: "ç" });
    expect(second.text).toBe("ça");
    expect(third.text).toBe("b");
  });

  test("uma letra procura a partir da próxima linha e dá a volta; um prefixo inclui a atual", () => {
    const buffer = (text: string) => ({ at: 0, text });
    const billing = 0;
    const shared = OPEN_ROWS.findIndex((row) => row.key === SHARED);

    expect(
      keyAt(
        OPEN_ROWS,
        moveTarget(typeaheadIntent(OPEN_ROWS, billing, buffer("s")))
      )
    ).toBe(SHARED);
    expect(
      keyAt(
        OPEN_ROWS,
        moveTarget(typeaheadIntent(OPEN_ROWS, shared, buffer("b")))
      )
    ).toBe(BILLING);
    expect(
      keyAt(
        OPEN_ROWS,
        moveTarget(typeaheadIntent(OPEN_ROWS, billing, buffer("bi")))
      )
    ).toBe(BILLING);
    expect(typeaheadIntent(OPEN_ROWS, billing, buffer("z"))).toBeNull();
  });

  test("casa o rótulo que a linha mostra: o controle bidi não conta, e pseudo-linhas nunca casam", () => {
    const rows = flattenOutline(
      {
        ...settled([
          packageNode("pkg:a", "alfa", 1),
          packageNode("pkg:b", `${RIGHT_TO_LEFT_OVERRIDE}Beta`, 1),
        ]),
        hasNextPage: true,
      },
      null
    );

    expect(
      keyAt(rows, moveTarget(typeaheadIntent(rows, 0, { at: 0, text: "b" })))
    ).toBe("pkg:b");
    expect(typeaheadIntent(rows, 0, { at: 0, text: "c" })).toBeNull();
  });
});

describe("resolveTabbable", () => {
  const indexByKey = indexByKeyOf(OPEN_ROWS);

  test("o primeiro candidato carregado e focável vence, na ordem dada", () => {
    expect(
      keyAt(
        OPEN_ROWS,
        resolveTabbable(OPEN_ROWS, indexByKey, [ORDER, SHARED, ORDERS]) ??
          undefined
      )
    ).toBe(ORDER);
    expect(
      keyAt(
        OPEN_ROWS,
        resolveTabbable(OPEN_ROWS, indexByKey, [
          null,
          "type:missing",
          ORDERS,
        ]) ?? undefined
      )
    ).toBe(ORDERS);
  });

  test("sem candidato, a primeira linha focável; sem linhas, nenhuma", () => {
    expect(resolveTabbable(OPEN_ROWS, indexByKey, [null, null])).toBe(0);
    expect(
      keyAt(
        LOADING_ROWS,
        resolveTabbable(LOADING_ROWS, indexByKeyOf(LOADING_ROWS), [
          `loading:${ORDERS}`,
        ]) ?? undefined
      )
    ).toBe(BILLING);
    expect(resolveTabbable([], new Map(), [ORDERS])).toBeNull();
  });
});

describe("resolveTarget", () => {
  test("uma chave conhecida é o índice dela; uma que não chegou ainda espera", () => {
    const indexByKey = indexByKeyOf(OPEN_ROWS);

    expect(resolveTarget({ key: SHARED }, OPEN_ROWS, indexByKey)).toBe(5);
    expect(
      resolveTarget({ key: "pkg:later" }, OPEN_ROWS, indexByKey)
    ).toBeNull();
  });

  test("depois de um carregar mais: espera enquanto a linha seguinte ainda é ele, e então é a primeira nova", () => {
    const before = flattenOutline(
      { ...settled(ROOT.slice(0, 2)), hasNextPage: true },
      null
    );
    const after = flattenOutline(settled(ROOT), null);
    const target = { after: ORDERS, waitingOn: "more:root" };

    expect(resolveTarget(target, before, indexByKeyOf(before))).toBeNull();
    expect(
      keyAt(
        after,
        resolveTarget(target, after, indexByKeyOf(after)) ?? undefined
      )
    ).toBe(EMPTY_PACKAGE);
  });

  test("a página que falha leva o foco à linha de erro; a que vem vazia fica na última que havia", () => {
    const failed = flattenOutline(
      { failed: true, hasNextPage: true, nodes: ROOT.slice(0, 2) },
      null
    );
    const emptyPage = flattenOutline(settled(ROOT.slice(0, 2)), null);
    const target = { after: ORDERS, waitingOn: "more:root" };

    expect(
      keyAt(
        failed,
        resolveTarget(target, failed, indexByKeyOf(failed)) ?? undefined
      )
    ).toBe("error:root");
    expect(
      keyAt(
        emptyPage,
        resolveTarget(target, emptyPage, indexByKeyOf(emptyPage)) ?? undefined
      )
    ).toBe(ORDERS);
  });
});

describe("pinIndexes", () => {
  test("a janela sozinha quando as presas já estão nela", () => {
    expect(pinIndexes([3, 4, 5], [4, null])).toStrictEqual([3, 4, 5]);
  });

  test("as presas fora da janela entram em ordem de leitura, sem repetir", () => {
    expect(pinIndexes([10, 11, 12], [999, 0])).toStrictEqual([
      0, 10, 11, 12, 999,
    ]);
    expect(pinIndexes([10, 11], [3, 3])).toStrictEqual([3, 10, 11]);
  });
});
