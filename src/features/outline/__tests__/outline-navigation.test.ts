import { describe, expect, test } from "vitest";
import {
  EMPTY_TYPEAHEAD,
  extendTypeahead,
  followTarget,
  intentOf,
  isTypeaheadKey,
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

const API = "pkg:br.pucpr.legacy.api";
const CORE = "pkg:br.pucpr.legacy.core";
const SPI = "pkg:br.pucpr.legacy.core.spi";
const EMPTY_PACKAGE = "pkg:br.pucpr.legacy.empty";
const CATALOG = "type:br.pucpr.legacy.core.Catalog";
const CATALOG_ENTRY = "type:br.pucpr.legacy.core.CatalogEntry";

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
  packageNode(API, "api", 2),
  packageNode(CORE, "core", 2),
  packageNode(EMPTY_PACKAGE, "empty", 0),
  packageNode(SPI, "core.spi", 2),
];

const settled = (nodes: readonly OutlineNode[] | undefined) => ({
  failure: null,
  hasNextPage: false,
  nodes,
});

const OPEN_ROWS = flattenOutline(
  { ...settled(ROOT), hasNextPage: true },
  {
    ...settled([
      typeNode(CATALOG, "Catalog"),
      typeNode(CATALOG_ENTRY, "CatalogEntry"),
    ]),
    parentId: CORE,
  }
);

const LOADING_ROWS = flattenOutline(settled(ROOT), {
  ...settled(undefined),
  parentId: CORE,
});

const FAILED_ROWS = flattenOutline(settled(ROOT), {
  ...settled(undefined),
  failure: "load",
  parentId: CORE,
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
    const spi = OPEN_ROWS.findIndex((row) => row.key === SPI);

    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("ArrowDown", OPEN_ROWS, 0, CORE)))
    ).toBe(CORE);
    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("ArrowDown", OPEN_ROWS, spi, CORE)))
    ).toBe("more:root");
    expect(
      intentOf("ArrowDown", OPEN_ROWS, OPEN_ROWS.length - 1, CORE)
    ).toBeNull();
    expect(intentOf("ArrowUp", OPEN_ROWS, 0, CORE)).toBeNull();
  });

  test("as linhas só visuais ficam de fora: ↓ do pacote aberto pula os tipos a caminho", () => {
    expect(
      keyAt(
        LOADING_ROWS,
        moveTarget(intentOf("ArrowDown", LOADING_ROWS, 1, CORE))
      )
    ).toBe(EMPTY_PACKAGE);
    expect(
      keyAt(
        LOADING_ROWS,
        moveTarget(intentOf("ArrowUp", LOADING_ROWS, 3, CORE))
      )
    ).toBe(CORE);
  });

  test("Home vai à primeira linha e End à última, que pode ser o carregar mais", () => {
    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("Home", OPEN_ROWS, 4, CORE)))
    ).toBe(API);
    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("End", OPEN_ROWS, 0, CORE)))
    ).toBe("more:root");
  });

  test("Enter e Espaço ativam; o resto, inclusive o *, não pede nada", () => {
    expect(intentOf("Enter", OPEN_ROWS, 0, CORE)).toStrictEqual({
      type: "activate",
    });
    expect(intentOf(" ", OPEN_ROWS, 0, CORE)).toStrictEqual({
      type: "activate",
    });
    expect(intentOf("*", OPEN_ROWS, 0, CORE)).toBeNull();
    expect(intentOf("PageDown", OPEN_ROWS, 0, CORE)).toBeNull();
  });
});

describe("intentOf: → e ←", () => {
  test("→ em um pacote fechado com tipos pede para abrir; em um pacote sem tipos, nada", () => {
    expect(intentOf("ArrowRight", OPEN_ROWS, 0, CORE)).toStrictEqual({
      packageId: API,
      type: "expand",
    });
    const empty = OPEN_ROWS.findIndex((row) => row.key === EMPTY_PACKAGE);
    expect(intentOf("ArrowRight", OPEN_ROWS, empty, CORE)).toBeNull();
  });

  test("→ no pacote aberto vai ao primeiro filho; com os tipos a caminho, fica", () => {
    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("ArrowRight", OPEN_ROWS, 1, CORE)))
    ).toBe(CATALOG);
    expect(intentOf("ArrowRight", LOADING_ROWS, 1, CORE)).toBeNull();
    expect(
      keyAt(
        FAILED_ROWS,
        moveTarget(intentOf("ArrowRight", FAILED_ROWS, 1, CORE))
      )
    ).toBe(`error:${CORE}`);
  });

  test("→ em um tipo ou em uma pseudo-linha não faz nada", () => {
    expect(intentOf("ArrowRight", OPEN_ROWS, 2, CORE)).toBeNull();
    expect(
      intentOf("ArrowRight", OPEN_ROWS, OPEN_ROWS.length - 1, CORE)
    ).toBeNull();
  });

  test("← em um filho, real ou pseudo-linha, volta ao pacote", () => {
    expect(
      keyAt(OPEN_ROWS, moveTarget(intentOf("ArrowLeft", OPEN_ROWS, 3, CORE)))
    ).toBe(CORE);
    expect(
      keyAt(
        FAILED_ROWS,
        moveTarget(intentOf("ArrowLeft", FAILED_ROWS, 2, CORE))
      )
    ).toBe(CORE);
  });

  test("← no pacote aberto pede para fechar; em um pacote fechado ou na raiz, nada", () => {
    expect(intentOf("ArrowLeft", OPEN_ROWS, 1, CORE)).toStrictEqual({
      type: "collapse",
    });
    expect(intentOf("ArrowLeft", OPEN_ROWS, 0, CORE)).toBeNull();
    expect(
      intentOf("ArrowLeft", OPEN_ROWS, OPEN_ROWS.length - 1, CORE)
    ).toBeNull();
  });
});

describe("toggleIntent: a seta clicada", () => {
  test("fecha o aberto, abre o fechado com tipos, e ignora o pacote sem tipos", () => {
    expect(toggleIntent(nodeRow(OPEN_ROWS, CORE), CORE)).toStrictEqual({
      type: "collapse",
    });
    expect(toggleIntent(nodeRow(OPEN_ROWS, SPI), CORE)).toStrictEqual({
      packageId: SPI,
      type: "expand",
    });
    expect(toggleIntent(nodeRow(OPEN_ROWS, EMPTY_PACKAGE), CORE)).toBeNull();
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
    const api = 0;
    const spi = OPEN_ROWS.findIndex((row) => row.key === SPI);

    expect(
      keyAt(OPEN_ROWS, moveTarget(typeaheadIntent(OPEN_ROWS, api, buffer("e"))))
    ).toBe(EMPTY_PACKAGE);
    expect(
      keyAt(OPEN_ROWS, moveTarget(typeaheadIntent(OPEN_ROWS, spi, buffer("a"))))
    ).toBe(API);
    expect(
      keyAt(
        OPEN_ROWS,
        moveTarget(typeaheadIntent(OPEN_ROWS, api, buffer("ap")))
      )
    ).toBe(API);
    expect(typeaheadIntent(OPEN_ROWS, api, buffer("z"))).toBeNull();
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
        resolveTabbable(OPEN_ROWS, indexByKey, [CATALOG, SPI, CORE]) ??
          undefined
      )
    ).toBe(CATALOG);
    expect(
      keyAt(
        OPEN_ROWS,
        resolveTabbable(OPEN_ROWS, indexByKey, [null, "type:missing", CORE]) ??
          undefined
      )
    ).toBe(CORE);
  });

  test("sem candidato, a primeira linha focável; sem linhas, nenhuma", () => {
    expect(resolveTabbable(OPEN_ROWS, indexByKey, [null, null])).toBe(0);
    expect(
      keyAt(
        LOADING_ROWS,
        resolveTabbable(LOADING_ROWS, indexByKeyOf(LOADING_ROWS), [
          `loading:${CORE}`,
        ]) ?? undefined
      )
    ).toBe(API);
    expect(resolveTabbable([], new Map(), [CORE])).toBeNull();
  });
});

describe("resolveTarget", () => {
  test("uma chave conhecida é o índice dela; uma que não chegou ainda espera", () => {
    const indexByKey = indexByKeyOf(OPEN_ROWS);

    expect(resolveTarget({ key: SPI }, OPEN_ROWS, indexByKey)).toBe(5);
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
    const target = { after: CORE, waitingOn: "more:root" };

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
      { failure: "nextPage", hasNextPage: true, nodes: ROOT.slice(0, 2) },
      null
    );
    const emptyPage = flattenOutline(settled(ROOT.slice(0, 2)), null);
    const target = { after: CORE, waitingOn: "more:root" };

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
    ).toBe(CORE);
  });
});

describe("followTarget: pedir de novo os tipos que falharam", () => {
  const retry = { after: CORE, waitingOn: `error:${CORE}` };

  test("o alvo segue a linha a caminho que trocou a de erro, e vai à de erro que volta", () => {
    expect(
      resolveTarget(retry, FAILED_ROWS, indexByKeyOf(FAILED_ROWS))
    ).toBeNull();
    expect(followTarget(retry, FAILED_ROWS, indexByKeyOf(FAILED_ROWS))).toBe(
      retry
    );

    const followed = followTarget(
      retry,
      LOADING_ROWS,
      indexByKeyOf(LOADING_ROWS)
    );
    expect(followed).toStrictEqual({
      after: CORE,
      waitingOn: `loading:${CORE}`,
    });
    expect(
      resolveTarget(followed, LOADING_ROWS, indexByKeyOf(LOADING_ROWS))
    ).toBeNull();
    expect(
      followTarget(followed, LOADING_ROWS, indexByKeyOf(LOADING_ROWS))
    ).toBe(followed);

    expect(
      keyAt(
        FAILED_ROWS,
        resolveTarget(followed, FAILED_ROWS, indexByKeyOf(FAILED_ROWS)) ??
          undefined
      )
    ).toBe(`error:${CORE}`);
    expect(
      keyAt(
        OPEN_ROWS,
        resolveTarget(followed, OPEN_ROWS, indexByKeyOf(OPEN_ROWS)) ?? undefined
      )
    ).toBe(CATALOG);
  });

  test("os tipos pedidos de novo vêm vazios: o foco fica no pacote, sem esperar a linha do vazio", () => {
    const empty = flattenOutline(settled(ROOT), {
      ...settled([]),
      parentId: CORE,
    });
    const followed = { after: CORE, waitingOn: `loading:${CORE}` };

    expect(
      keyAt(
        empty,
        resolveTarget(followed, empty, indexByKeyOf(empty)) ?? undefined
      )
    ).toBe(CORE);
  });

  test("uma chave conhecida, ou um alvo sem linha a caminho depois dele, não muda", () => {
    const moved = { key: SPI };
    const more = { after: CORE, waitingOn: "more:root" };

    expect(followTarget(moved, LOADING_ROWS, indexByKeyOf(LOADING_ROWS))).toBe(
      moved
    );
    expect(followTarget(more, OPEN_ROWS, indexByKeyOf(OPEN_ROWS))).toBe(more);
  });
});
