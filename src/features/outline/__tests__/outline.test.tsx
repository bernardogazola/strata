import { type QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { StrictMode } from "react";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  type MockInstance,
  test,
  vi,
} from "vitest";
import { FakeBridgeError } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import { ArchitectureOutline } from "@/features/outline/architecture-outline";
import { ROW_HEIGHT } from "@/features/outline/outline-row";
import {
  type ExpandedOutlineLevel,
  flattenOutline,
  type OutlineLevel,
} from "@/features/outline/use-outline";
import { formatProtocolError } from "@/i18n/errors";
import { interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import type { ElementKind } from "@/protocol/enums";
import type { OutlineNode } from "@/protocol/graph";
import { createQueryClient } from "@/queries/client";
import { queryKeys } from "@/queries/keys";
import {
  type GraphViewParams,
  outlineChildrenQueryOptions,
} from "@/queries/options";
import {
  DRILLED_CHILDREN,
  DRILLED_PACKAGE_ID,
  outlinePageOf,
  ROOT_ITEMS,
} from "@/test/fixture-graph";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const ROOT_IDS = ROOT_ITEMS.map((item) => item.id);
const DRILLED = DRILLED_PACKAGE_ID;
const DRILLED_TYPE_IDS = DRILLED_CHILDREN.map((item) => item.id);
const FIRST_PACKAGE = ROOT_IDS[0] ?? "";
const LAST_PACKAGE = ROOT_IDS.at(-1) ?? "";
const SELECTED_TYPE = DRILLED_TYPE_IDS[1] ?? "";

const NEXT_CURSOR = "Y3Vyc29yLTI";

const RIGHT_TO_LEFT_OVERRIDE = String.fromCodePoint(0x20_2e);

const LEVEL_ONE: GraphViewParams = { level: "PACKAGE" };
const drilledTypes: GraphViewParams = { level: "TYPE", parentId: DRILLED };

const VIEWPORT = { height: 400, width: 320 } as const;

beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(
    VIEWPORT.height
  );
  vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(
    VIEWPORT.width
  );
});

afterEach(() => {
  vi.restoreAllMocks();
});

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

const PAGE_ONE = ROOT_ITEMS.slice(0, 2);
const PAGE_TWO = ROOT_ITEMS.slice(2);
const FIRST_OF_PAGE_TWO = PAGE_TWO[0]?.id ?? "";

const withChildren = (
  parentId: string,
  childIds: readonly string[]
): string[] =>
  ROOT_IDS.flatMap((id) => (id === parentId ? [id, ...childIds] : [id]));

const rootAria = (open: string | null = null) =>
  ROOT_ITEMS.map((item, index) => ({
    expanded: String(item.id === open),
    id: item.id,
    level: "1",
    posinset: String(index + 1),
    selected: "false",
    setsize: String(ROOT_ITEMS.length),
  }));

const drilledTypeAria = () =>
  DRILLED_TYPE_IDS.map((id, index) => ({
    expanded: null,
    id,
    level: "2",
    posinset: String(index + 1),
    selected: "false",
    setsize: String(DRILLED_TYPE_IDS.length),
  }));

const overrideOutline = (
  entries: readonly (readonly [string | null, unknown])[]
): void => {
  vi.stubGlobal(
    "__STRATA_FAKE_TRANSCRIPTS__",
    entries.map(([parentId, result]) => ({
      method: "outline.children",
      request: { parentId },
      result,
    })) satisfies readonly FakeTranscriptOverride[]
  );
};

const failChildrenOf = (packageId: string): void => {
  overrideOutline([[packageId, { notAPage: true }]]);
};

const serveFixtures = (): void => {
  overrideOutline([]);
};

const mockPagedOutline = (
  pages: Readonly<
    Record<string, { readonly first: unknown; readonly next: unknown }>
  >
) =>
  vi
    .spyOn(transport, "request")
    .mockImplementation((method: string, payload: unknown) => {
      const { cursor, parentId } = payload as {
        readonly cursor?: string;
        readonly parentId: string | null;
      };
      const page = pages[parentId ?? "root"];
      if (method !== "outline.children" || page === undefined) {
        return Promise.reject(
          new Error(`pedido inesperado: ${method} ${parentId}`)
        );
      }
      return Promise.resolve(cursor === undefined ? page.first : page.next);
    });

const PAGE_CURSORS: readonly string[] = [
  "Y3Vyc29yLTE",
  "Y3Vyc29yLTI",
  "Y3Vyc29yLTM",
];

const mockInstantPages = (
  levels: Readonly<Record<string, readonly OutlineNode[]>>,
  failing?: string
) =>
  vi
    .spyOn(transport, "request")
    .mockImplementation((method: string, payload: unknown) => {
      const { cursor, parentId } = payload as {
        readonly cursor?: string;
        readonly parentId: string | null;
      };
      const nodes = levels[parentId ?? "root"];
      if (method !== "outline.children" || nodes === undefined) {
        return Promise.reject(
          new Error(`pedido inesperado: ${method} ${parentId}`)
        );
      }
      if (cursor !== undefined && cursor === failing) {
        return Promise.reject(new Error("a página caiu"));
      }
      const page = cursor === undefined ? 0 : PAGE_CURSORS.indexOf(cursor) + 1;
      return Promise.resolve({
        items: nodes.slice(page, page + 1),
        nextCursor:
          page + 1 < nodes.length ? (PAGE_CURSORS[page] ?? null) : null,
      });
    });

const SETTLE_MS = 50;
const settle = () =>
  act(
    () =>
      new Promise<void>((resolve) => {
        setTimeout(resolve, SETTLE_MS);
      })
  );

const deferred = () => {
  let settleWith: {
    reject: (error: Error) => void;
    resolve: (value: unknown) => void;
  } = {
    reject: () => undefined,
    resolve: () => undefined,
  };
  const promise = new Promise<unknown>((resolve, reject) => {
    settleWith = { reject, resolve };
  });
  return {
    promise,
    reject: (error: Error) => settleWith.reject(error),
    resolve: (value: unknown) => settleWith.resolve(value),
  };
};

const renderOutline = ({
  client = createQueryClient(),
  focus = null,
  view = LEVEL_ONE,
}: {
  readonly client?: QueryClient;
  readonly focus?: string | null;
  readonly view?: GraphViewParams;
} = {}) => {
  const onCollapse = vi.fn<() => void>();
  const onExpand = vi.fn<(packageId: string) => void>();
  const onSelect = vi.fn<(elementId: string, kind: ElementKind) => void>();
  render(
    <QueryClientProvider client={client}>
      <ArchitectureOutline
        analysisId={AN}
        focus={focus}
        onCollapse={onCollapse}
        onExpand={onExpand}
        onSelect={onSelect}
        view={view}
      />
    </QueryClientProvider>
  );
  return { onCollapse, onExpand, onSelect };
};

const treeItems = (): HTMLElement[] => screen.queryAllByRole("treeitem");

const waitForRows = async (count: number): Promise<HTMLElement[]> => {
  await waitFor(() => expect(treeItems()).toHaveLength(count));
  return treeItems();
};

const rowById = (id: string): HTMLElement => {
  const row = treeItems().find((item) => item.dataset.elementId === id);
  if (row === undefined) {
    throw new Error(`sem linha para ${id}`);
  }
  return row;
};

const ariaOf = (row: HTMLElement) => ({
  expanded: row.getAttribute("aria-expanded"),
  id: row.dataset.elementId ?? null,
  level: row.getAttribute("aria-level"),
  posinset: row.getAttribute("aria-posinset"),
  selected: row.getAttribute("aria-selected"),
  setsize: row.getAttribute("aria-setsize"),
});

const labelOf = (row: HTMLElement): string | null =>
  row.querySelector("bdi")?.textContent ?? null;

const outlineCalls = (request: MockInstance<typeof transport.request>) =>
  request.mock.calls.filter(([method]) => method === "outline.children");

const cursorsOf = (
  request: MockInstance<typeof transport.request>,
  parentId: string | null = null
) =>
  outlineCalls(request)
    .map(
      ([, payload]) =>
        payload as { readonly cursor?: string; readonly parentId: unknown }
    )
    .filter((payload) => payload.parentId === parentId)
    .map((payload) => payload.cursor);

const idOrText = (row: HTMLElement): string | null =>
  row.dataset.elementId ?? row.textContent;

describe("ArchitectureOutline: o caminho de dados", () => {
  test("pede só outline.children da raiz, com o limite máximo e sem cursor; nunca graph.slice", async () => {
    const request = vi.spyOn(transport, "request");
    renderOutline();

    await waitForRows(ROOT_IDS.length);

    expect(request.mock.calls.map(([method]) => method)).toStrictEqual([
      "outline.children",
    ]);
    expect(request.mock.calls[0]?.[1]).toStrictEqual({
      analysisId: AN,
      cursor: undefined,
      limit: 500,
      parentId: null,
    });
  });

  test("nível 1: os pacotes da fixture, na ordem do Java, com o ARIA exato", async () => {
    renderOutline();

    const rows = await waitForRows(ROOT_IDS.length);

    expect(screen.getByRole("tree", { name: t.outline.title })).toBeDefined();
    expect(
      screen.getByRole("heading", { level: 2, name: t.outline.title })
    ).toBeDefined();
    expect(rows.map(ariaOf)).toStrictEqual(rootAria());
    expect(rows.map(labelOf)).toStrictEqual(
      ROOT_ITEMS.map((item) => item.label)
    );
  });

  test("o título nomeia a árvore, e a estrutura não abre um landmark com o mesmo nome dentro do aside", async () => {
    renderOutline();

    await waitForRows(ROOT_IDS.length);

    expect(screen.getByRole("tree", { name: t.outline.title })).toBeDefined();
    expect(screen.queryAllByRole("region")).toStrictEqual([]);
  });

  test("a dica de teclado fica logo sob o título, no recuo dele, e descreve a árvore", async () => {
    renderOutline();

    await waitForRows(ROOT_IDS.length);

    const tree = screen.getByRole("tree", { name: t.outline.title });
    expect(tree).toHaveAccessibleDescription(t.outline.keyboardHint);
    const hint = screen.getByText(t.outline.keyboardHint);
    expect(hint.id).toBe(tree.getAttribute("aria-describedby"));
    expect(
      screen.getByRole("heading", { name: t.outline.title }).nextElementSibling
    ).toBe(hint);
    expect(hint.className.split(" ")).toStrictEqual(
      expect.arrayContaining(["px-2", "text-muted-foreground", "text-xs"])
    );
    expect(hint.hasAttribute("tabindex")).toBe(false);
  });

  test("a contagem de tipos é visível ao olho e dita por extenso ao leitor de tela", async () => {
    renderOutline();

    await waitForRows(ROOT_IDS.length);
    const drilled = rowById(DRILLED);
    const count = String(DRILLED_TYPE_IDS.length);

    const visible = within(drilled).getByText(count);
    expect(visible.closest('[aria-hidden="true"]')).not.toBeNull();
    expect(within(drilled).getByText(`${count} tipos`).className).toContain(
      "sr-only"
    );
  });

  test("nível TYPE do pacote aberto: os filhos logo abaixo dele, e os níveis certos", async () => {
    const request = vi.spyOn(transport, "request");
    renderOutline({ view: drilledTypes });

    const rows = await waitForRows(ROOT_IDS.length + DRILLED_TYPE_IDS.length);

    const roots = rootAria(DRILLED);
    const at = ROOT_IDS.indexOf(DRILLED) + 1;
    expect(rows.map(ariaOf)).toStrictEqual([
      ...roots.slice(0, at),
      ...drilledTypeAria(),
      ...roots.slice(at),
    ]);
    expect(request.mock.calls.map(([method]) => method)).toStrictEqual([
      "outline.children",
      "outline.children",
    ]);
    expect(
      request.mock.calls.map(
        ([, payload]) => (payload as { readonly parentId: unknown }).parentId
      )
    ).toStrictEqual([null, DRILLED]);
  });

  test("aria-selected é o focus da URL, em toda linha real", async () => {
    renderOutline({ focus: SELECTED_TYPE, view: drilledTypes });

    const rows = await waitForRows(ROOT_IDS.length + DRILLED_TYPE_IDS.length);

    expect(
      rows.filter((row) => row.getAttribute("aria-selected") === "true")
    ).toStrictEqual([rowById(SELECTED_TYPE)]);
    expect(
      rows.every((row) => row.getAttribute("aria-selected") !== null)
    ).toBe(true);
  });

  test("as raias: o tipo recua 20 px e guarda a seta vazia, e o ícone dele cai sob o nome do pacote", async () => {
    renderOutline({ focus: SELECTED_TYPE, view: drilledTypes });
    await waitForRows(ROOT_IDS.length + DRILLED_TYPE_IDS.length);

    const [chevron, packageIcon, packageName] = rowById(DRILLED).children;
    const [emptyChevron, typeIcon, typeName] = rowById(SELECTED_TYPE).children;
    expect(chevron?.hasAttribute("data-chevron")).toBe(true);
    expect(chevron?.classList.contains("w-3.5")).toBe(true);
    expect(emptyChevron?.hasAttribute("data-chevron")).toBe(false);
    expect(emptyChevron?.classList.contains("w-3.5")).toBe(true);
    for (const icon of [packageIcon, typeIcon]) {
      expect(icon?.getAttribute("aria-hidden")).toBe("true");
      expect(icon?.classList.contains("w-3")).toBe(true);
    }
    expect(packageName?.tagName).toBe("BDI");
    expect(typeName?.tagName).toBe("BDI");
    expect(rowById(DRILLED).classList.contains("pl-2")).toBe(true);
    expect(rowById(SELECTED_TYPE).classList.contains("pl-7")).toBe(true);

    expect(screen.getByRole("tree").querySelector(".font-mono")).toBeNull();
  });

  test("a linha selecionada sobe e marca o ícone no acento; nenhuma outra o faz", async () => {
    renderOutline({ focus: SELECTED_TYPE, view: drilledTypes });
    const rows = await waitForRows(ROOT_IDS.length + DRILLED_TYPE_IDS.length);

    const accented = rows.filter(
      (row) => row.querySelector(".border-accent-strong") !== null
    );
    expect(accented).toStrictEqual([rowById(SELECTED_TYPE)]);
  });
});

describe("ArchitectureOutline: páginas", () => {
  const PAGED_ROOT = {
    first: { items: PAGE_ONE, nextCursor: NEXT_CURSOR },
    next: { items: PAGE_TWO, nextCursor: null },
  };

  test("a raiz com cursor mostra carregar mais; ativá-lo pede a página 2 pelo cursor e foca a primeira linha nova", async () => {
    const request = mockPagedOutline({ root: PAGED_ROOT });
    renderOutline();

    const rows = await waitForRows(PAGE_ONE.length + 1);
    const [, , more] = rows;
    if (more === undefined) {
      throw new Error("sem linha de carregar mais");
    }

    expect(rows.slice(0, 2).map((row) => ariaOf(row).setsize)).toStrictEqual([
      "-1",
      "-1",
    ]);
    expect(more.textContent).toBe(t.outline.loadMore.PACKAGE);
    expect(ariaOf(more)).toStrictEqual({
      expanded: null,
      id: null,
      level: "1",
      posinset: "3",
      selected: null,
      setsize: "-1",
    });

    fireEvent.click(more);

    await waitFor(() =>
      expect(treeItems().map((row) => row.dataset.elementId)).toStrictEqual(
        ROOT_IDS
      )
    );
    expect(treeItems().map((row) => ariaOf(row).setsize)).toStrictEqual(
      ROOT_IDS.map(() => String(ROOT_IDS.length))
    );
    expect(outlineCalls(request).map(([, payload]) => payload)).toStrictEqual([
      { analysisId: AN, cursor: undefined, limit: 500, parentId: null },
      { analysisId: AN, cursor: NEXT_CURSOR, limit: 500, parentId: null },
    ]);
    await waitFor(() =>
      expect(document.activeElement).toBe(rowById(FIRST_OF_PAGE_TWO))
    );
  });

  test("um pacote com mais tipos que a página: setsize é o childCount e o carregar mais diz quantos", async () => {
    mockPagedOutline({
      [DRILLED]: {
        first: { items: DRILLED_CHILDREN.slice(0, 2), nextCursor: NEXT_CURSOR },
        next: { items: DRILLED_CHILDREN.slice(2), nextCursor: null },
      },
      root: { first: { items: ROOT_ITEMS, nextCursor: null }, next: null },
    });
    renderOutline({ view: drilledTypes });

    const rows = await waitForRows(ROOT_IDS.length + 3);
    const at = ROOT_IDS.indexOf(DRILLED) + 1;
    const total = String(DRILLED_TYPE_IDS.length);
    const more = rows[at + 2];

    expect(
      rows.slice(at, at + 2).map((row) => ariaOf(row).setsize)
    ).toStrictEqual([total, total]);
    expect(more?.textContent).toBe(
      interpolate(t.outline.loadMore.TYPE, {
        shown: 2,
        total: DRILLED_TYPE_IDS.length,
      })
    );
    expect(more === undefined ? null : ariaOf(more)).toStrictEqual({
      expanded: null,
      id: null,
      level: "2",
      posinset: "3",
      selected: null,
      setsize: total,
    });
  });

  test("busca até achar: um focus na página 2 traz a página 2 sozinho e a marca selecionada", async () => {
    const request = mockPagedOutline({ root: PAGED_ROOT });
    renderOutline({ focus: LAST_PACKAGE });

    await waitFor(() =>
      expect(rowById(LAST_PACKAGE).getAttribute("aria-selected")).toBe("true")
    );
    expect(treeItems()).toHaveLength(ROOT_IDS.length);
    expect(outlineCalls(request)).toHaveLength(2);
  });

  test("busca até achar: o pacote aberto na página 2 traz a página 2 e os filhos dele", async () => {
    const lastChildren = outlinePageOf(LAST_PACKAGE);
    mockPagedOutline({
      [LAST_PACKAGE]: { first: lastChildren, next: null },
      root: PAGED_ROOT,
    });
    renderOutline({ view: { level: "TYPE", parentId: LAST_PACKAGE } });

    const rows = await waitForRows(ROOT_IDS.length + lastChildren.items.length);

    expect(rows.map((row) => row.dataset.elementId)).toStrictEqual(
      withChildren(
        LAST_PACKAGE,
        lastChildren.items.map((item) => item.id)
      )
    );
    expect(ariaOf(rowById(LAST_PACKAGE)).expanded).toBe("true");
  });

  test("carregar mais durante uma atualização espera ela terminar, então pede a página 2 e foca o primeiro pacote novo", async () => {
    const client = createQueryClient();
    const refresh = deferred();
    const request = mockPagedOutline({ root: PAGED_ROOT });
    renderOutline({ client });
    const [, , more] = await waitForRows(PAGE_ONE.length + 1);
    if (more === undefined) {
      throw new Error("sem linha de carregar mais");
    }

    request.mockImplementationOnce(() => refresh.promise);
    const refreshed = client.invalidateQueries({
      queryKey: queryKeys.structure.all(AN),
    });
    await waitFor(() =>
      expect(screen.getByRole("tree").getAttribute("aria-busy")).toBe("true")
    );
    act(() => more.focus());
    fireEvent.keyDown(more, { key: "Enter" });
    expect(cursorsOf(request)).toStrictEqual([undefined, undefined]);

    await act(async () => {
      refresh.resolve(PAGED_ROOT.first);
      await refreshed;
    });

    await waitFor(() =>
      expect(document.activeElement).toBe(rowById(FIRST_OF_PAGE_TWO))
    );
    expect(cursorsOf(request)).toStrictEqual([
      undefined,
      undefined,
      NEXT_CURSOR,
    ]);
  });

  test("carregar mais com a página seguinte já a caminho não a pede de novo: o foco espera por ela", async () => {
    const nextPage = deferred();
    const request = vi
      .spyOn(transport, "request")
      .mockImplementation((_method: string, payload: unknown) =>
        (payload as { readonly cursor?: string }).cursor === undefined
          ? Promise.resolve(PAGED_ROOT.first)
          : nextPage.promise
      );
    renderOutline();
    const [, , more] = await waitForRows(PAGE_ONE.length + 1);
    if (more === undefined) {
      throw new Error("sem linha de carregar mais");
    }
    act(() => more.focus());

    fireEvent.keyDown(more, { key: "Enter" });
    await waitFor(() =>
      expect(screen.getByRole("tree").getAttribute("aria-busy")).toBe("true")
    );
    fireEvent.keyDown(more, { key: "Enter" });
    await act(async () => {
      nextPage.resolve(PAGED_ROOT.next);
      await nextPage.promise;
    });

    await waitFor(() =>
      expect(document.activeElement).toBe(rowById(FIRST_OF_PAGE_TWO))
    );
    await settle();
    expect(cursorsOf(request)).toStrictEqual([undefined, NEXT_CURSOR]);
  });
});

describe("ArchitectureOutline: busca até achar, com o bridge que responde na hora", () => {
  const FOUR_PACKAGES = ["p0", "p1", "p2", "p3"].map((label) =>
    packageNode(`pkg:${label}`, label, label === "p2" ? 1 : 0)
  );
  const MONEY = typeNode("type:p2.Money", "Money");
  const FOUR_DRILLED_TYPES = ["T0", "T1", "T2", "T3"].map((label) =>
    typeNode(`type:${DRILLED.slice("pkg:".length)}.${label}`, label)
  );
  const DRILLED_ALONE = ROOT_ITEMS.filter((item) => item.id === DRILLED).map(
    (item) => ({ ...item, childCount: FOUR_DRILLED_TYPES.length })
  );

  test("um focus na página 4 da raiz pede as páginas 2, 3 e 4, cada cursor uma vez, e a marca selecionada", async () => {
    const request = mockInstantPages({ root: FOUR_PACKAGES });
    renderOutline({ focus: "pkg:p3" });

    await waitFor(() =>
      expect(rowById("pkg:p3").getAttribute("aria-selected")).toBe("true")
    );
    await settle();

    expect(cursorsOf(request)).toStrictEqual([undefined, ...PAGE_CURSORS]);
    expect(treeItems()).toHaveLength(4);
  });

  test("o pacote aberto na página 3 da raiz traz as páginas até ele, e então os filhos dele", async () => {
    const request = mockInstantPages({
      "pkg:p2": [MONEY],
      root: FOUR_PACKAGES,
    });
    renderOutline({ view: { level: "TYPE", parentId: "pkg:p2" } });

    await waitFor(() =>
      expect(treeItems().map(idOrText)).toStrictEqual([
        "pkg:p0",
        "pkg:p1",
        "pkg:p2",
        MONEY.id,
        t.outline.loadMore.PACKAGE,
      ])
    );
    await settle();

    expect(cursorsOf(request)).toStrictEqual([
      undefined,
      PAGE_CURSORS[0],
      PAGE_CURSORS[1],
    ]);
  });

  test("um tipo na página 4 dos filhos: os tipos são pedidos até ele", async () => {
    const request = mockInstantPages({
      [DRILLED]: FOUR_DRILLED_TYPES,
      root: DRILLED_ALONE,
    });
    const lastType = FOUR_DRILLED_TYPES.at(-1)?.id ?? "";
    renderOutline({ focus: lastType, view: drilledTypes });

    await waitFor(() =>
      expect(rowById(lastType).getAttribute("aria-selected")).toBe("true")
    );
    await settle();

    expect(cursorsOf(request, DRILLED)).toStrictEqual([
      undefined,
      ...PAGE_CURSORS,
    ]);
  });

  test("um pacote que não está em página nenhuma: cada página uma vez, e o laço para quando o cursor acaba", async () => {
    const request = mockInstantPages({ root: FOUR_PACKAGES });
    renderOutline({ focus: "pkg:absent" });

    await waitForRows(4);
    await settle();

    expect(cursorsOf(request)).toStrictEqual([undefined, ...PAGE_CURSORS]);
    expect(treeItems().map(idOrText)).toStrictEqual(
      FOUR_PACKAGES.map((node) => node.id)
    );
  });

  test("a página que falha para o laço: nenhum pedido a mais, e a linha de erro no lugar do carregar mais", async () => {
    const request = mockInstantPages({ root: FOUR_PACKAGES }, PAGE_CURSORS[1]);
    renderOutline({ focus: "pkg:p3" });

    await waitFor(() =>
      expect(treeItems().at(-1)?.textContent).toBe(t.outline.failedRoot)
    );
    await settle();

    expect(cursorsOf(request)).toStrictEqual([
      undefined,
      PAGE_CURSORS[0],
      PAGE_CURSORS[1],
    ]);
    expect(treeItems().map((row) => row.dataset.elementId)).toStrictEqual([
      "pkg:p0",
      "pkg:p1",
      undefined,
    ]);
  });

  test("sob StrictMode, com a raiz já no cache, o efeito montado duas vezes não pede o mesmo cursor duas vezes", async () => {
    const client = createQueryClient();
    const request = mockInstantPages({ root: FOUR_PACKAGES });
    await client.prefetchInfiniteQuery(outlineChildrenQueryOptions(AN, null));
    render(
      <StrictMode>
        <QueryClientProvider client={client}>
          <ArchitectureOutline
            analysisId={AN}
            focus="pkg:p3"
            onCollapse={vi.fn<() => void>()}
            onExpand={vi.fn<(packageId: string) => void>()}
            onSelect={vi.fn<(elementId: string, kind: ElementKind) => void>()}
            view={LEVEL_ONE}
          />
        </QueryClientProvider>
      </StrictMode>
    );

    await waitFor(() =>
      expect(rowById("pkg:p3").getAttribute("aria-selected")).toBe("true")
    );
    await settle();

    expect(cursorsOf(request)).toStrictEqual([undefined, ...PAGE_CURSORS]);
  });

  test("no nível 1, um focus de tipo nunca está na raiz: nenhuma página além da primeira", async () => {
    const request = mockInstantPages({ root: FOUR_PACKAGES });
    renderOutline({ focus: "type:never.at.Root" });

    await waitFor(() =>
      expect(treeItems().map(idOrText)).toStrictEqual([
        "pkg:p0",
        t.outline.loadMore.PACKAGE,
      ])
    );
    await settle();

    expect(cursorsOf(request)).toStrictEqual([undefined]);
  });

  test.each([
    ["um grupo pfx:", "pfx:p2"],
    ["um pacote", "pkg:p0"],
  ])(
    "no nível TYPE, %s no focus nunca é filho: nenhuma página de tipos além da primeira",
    async (_case, focus) => {
      const request = mockInstantPages({
        [DRILLED]: FOUR_DRILLED_TYPES,
        root: DRILLED_ALONE,
      });
      renderOutline({ focus, view: drilledTypes });

      const [firstType] = FOUR_DRILLED_TYPES;
      await waitFor(() => expect(rowById(firstType?.id ?? "")).toBeDefined());
      await settle();

      expect(cursorsOf(request, DRILLED)).toStrictEqual([undefined]);
    }
  );
});

describe("ArchitectureOutline: estados", () => {
  test("a página seguinte de pacotes que falha vira a linha de erro da raiz, e ativá-la pede de novo", async () => {
    let nextPageFails = true;
    const request = vi
      .spyOn(transport, "request")
      .mockImplementation((_method: string, payload: unknown) => {
        const { cursor } = payload as { readonly cursor?: string };
        if (cursor === undefined) {
          return Promise.resolve({
            items: PAGE_ONE,
            nextCursor: NEXT_CURSOR,
          });
        }
        if (nextPageFails) {
          nextPageFails = false;
          return Promise.reject(new Error("a página 2 caiu"));
        }
        return Promise.resolve({
          items: PAGE_TWO,
          nextCursor: null,
        });
      });
    renderOutline();

    const [, , more] = await waitForRows(PAGE_ONE.length + 1);
    if (more === undefined) {
      throw new Error("sem linha de carregar mais");
    }
    fireEvent.click(more);

    await waitFor(() =>
      expect(treeItems().at(-1)?.textContent).toBe(t.outline.failedRoot)
    );
    const failed = treeItems().at(-1);
    if (failed === undefined) {
      throw new Error("sem linha de erro");
    }
    fireEvent.click(failed);

    await waitFor(() =>
      expect(treeItems().map((row) => row.dataset.elementId)).toStrictEqual(
        ROOT_IDS
      )
    );
    expect(
      outlineCalls(request).map(
        ([, payload]) => (payload as { readonly cursor?: string }).cursor
      )
    ).toStrictEqual([undefined, NEXT_CURSOR, NEXT_CURSOR]);
  });

  test("a linha de erro quebra o texto em vez de cortá-lo: sem truncate, e altura mínima, não fixa", async () => {
    failChildrenOf(FIRST_PACKAGE);
    renderOutline({ view: { level: "TYPE", parentId: FIRST_PACKAGE } });

    const [, failed] = await waitForRows(ROOT_IDS.length + 1);
    const text = failed?.querySelector("span:last-child");

    expect(text?.textContent).toBe(t.outline.failed);
    expect(text?.className.split(" ")).not.toContain("truncate");
    expect(text?.className.split(" ")).toContain("break-words");
    expect(failed?.style.height).toBe("");
    expect(failed?.style.minHeight).toBe(`${ROW_HEIGHT}px`);
  });

  test("os filhos que falham viram uma linha de erro no nível 2; ativá-la pede de novo", async () => {
    const request = vi.spyOn(transport, "request");
    failChildrenOf(FIRST_PACKAGE);
    renderOutline({ view: { level: "TYPE", parentId: FIRST_PACKAGE } });

    const rows = await waitForRows(ROOT_IDS.length + 1);
    const [, failed] = rows;
    if (failed === undefined) {
      throw new Error("sem linha de erro");
    }

    expect(failed.textContent).toBe(t.outline.failed);
    expect(ariaOf(failed)).toMatchObject({
      expanded: null,
      id: null,
      level: "2",
      selected: null,
    });

    serveFixtures();
    fireEvent.click(failed);

    await waitFor(() =>
      expect(treeItems().map((row) => row.dataset.elementId)).toStrictEqual(
        withChildren(
          FIRST_PACKAGE,
          outlinePageOf(FIRST_PACKAGE).items.map((item) => item.id)
        )
      )
    );
    expect(
      outlineCalls(request).filter(
        ([, payload]) =>
          (payload as { readonly parentId: unknown }).parentId === FIRST_PACKAGE
      )
    ).toHaveLength(2);
  });

  test("Enter na linha de erro da raiz: a página que chega leva o foco ao primeiro pacote novo, e não ao <body>", async () => {
    const request = mockPagedOutline({
      root: {
        first: { items: PAGE_ONE, nextCursor: NEXT_CURSOR },
        next: { items: PAGE_TWO, nextCursor: null },
      },
    });
    renderOutline();
    const [, , more] = await waitForRows(PAGE_ONE.length + 1);
    if (more === undefined) {
      throw new Error("sem linha de carregar mais");
    }
    request.mockImplementationOnce(() =>
      Promise.reject(new Error("a página 2 caiu"))
    );
    fireEvent.click(more);
    await waitFor(() =>
      expect(treeItems().at(-1)?.textContent).toBe(t.outline.failedRoot)
    );
    const failed = treeItems().at(-1);
    if (failed === undefined) {
      throw new Error("sem linha de erro");
    }
    act(() => failed.focus());

    fireEvent.keyDown(failed, { key: "Enter" });

    await waitFor(() =>
      expect(document.activeElement).toBe(rowById(FIRST_OF_PAGE_TWO))
    );
  });

  test("Enter na linha de erro dos tipos: o pedido que dá certo leva o foco ao primeiro tipo, e não ao <body>", async () => {
    failChildrenOf(FIRST_PACKAGE);
    renderOutline({ view: { level: "TYPE", parentId: FIRST_PACKAGE } });
    const [, failed] = await waitForRows(ROOT_IDS.length + 1);
    if (failed === undefined) {
      throw new Error("sem linha de erro");
    }
    act(() => failed.focus());

    serveFixtures();
    fireEvent.keyDown(failed, { key: "Enter" });

    const [firstChild] = outlinePageOf(FIRST_PACKAGE).items;
    await waitFor(() =>
      expect(document.activeElement).toBe(rowById(firstChild?.id ?? ""))
    );
  });

  test("Enter na linha de erro dos tipos: falhando de novo, o foco volta à linha de erro", async () => {
    const retried = deferred();
    const request = vi.spyOn(transport, "request");
    failChildrenOf(FIRST_PACKAGE);
    renderOutline({ view: { level: "TYPE", parentId: FIRST_PACKAGE } });
    const [, failed] = await waitForRows(ROOT_IDS.length + 1);
    if (failed === undefined) {
      throw new Error("sem linha de erro");
    }
    act(() => failed.focus());
    request.mockImplementationOnce(() => retried.promise);

    fireEvent.keyDown(failed, { key: "Enter" });
    await screen.findByText(t.outline.loadingTypes);
    expect(document.activeElement).toBe(document.body);
    await act(async () => {
      retried.reject(new Error("os tipos caíram de novo"));
      await retried.promise.catch(() => undefined);
    });

    await waitFor(() =>
      expect(treeItems()[1]?.textContent).toBe(t.outline.failed)
    );
    expect(document.activeElement).toBe(treeItems()[1]);
  });

  test("a atualização da raiz que falha mantém os pacotes e diz que não os atualizou; ativá-la refaz a consulta", async () => {
    const client = createQueryClient();
    const request = mockPagedOutline({
      root: { first: { items: ROOT_ITEMS, nextCursor: null }, next: null },
    });
    renderOutline({ client });
    await waitForRows(ROOT_IDS.length);

    request.mockImplementationOnce(() =>
      Promise.reject(new Error("a atualização caiu"))
    );
    await act(() =>
      client.invalidateQueries({ queryKey: queryKeys.structure.all(AN) })
    );
    const rows = await waitForRows(ROOT_IDS.length + 1);
    const failed = rows.at(-1);
    if (failed === undefined) {
      throw new Error("sem linha de erro");
    }

    expect(rows.slice(0, -1).map((row) => row.dataset.elementId)).toStrictEqual(
      ROOT_IDS
    );
    expect(failed.textContent).toBe(t.outline.failedRefresh);

    fireEvent.click(failed);

    await waitForRows(ROOT_IDS.length);
    expect(cursorsOf(request)).toStrictEqual([undefined, undefined, undefined]);
  });

  test("os filhos a caminho são só visuais: nenhuma linha focável, e a árvore fica ocupada", async () => {
    mockPagedOutline({
      [DRILLED]: { first: new Promise(() => undefined), next: null },
      root: { first: { items: ROOT_ITEMS, nextCursor: null }, next: null },
    });
    renderOutline({ view: drilledTypes });

    await waitForRows(ROOT_IDS.length);
    const loading = screen.getByText(t.outline.loadingTypes);

    expect(loading.closest('[aria-hidden="true"]')).not.toBeNull();
    expect(screen.getByRole("tree").getAttribute("aria-busy")).toBe("true");
  });

  test("um pacote aberto sem tipos mostra o vazio do nível, sem linha focável", async () => {
    overrideOutline([[DRILLED, { items: [], nextCursor: null }]]);
    renderOutline({ view: drilledTypes });

    await waitFor(() =>
      expect(screen.getByText(t.graph.empty.TYPE)).toBeDefined()
    );
    expect(treeItems()).toHaveLength(ROOT_IDS.length);
    expect(screen.getByRole("tree").getAttribute("aria-busy")).toBe("false");
  });

  test("a raiz carregando é um status, e não uma árvore vazia", async () => {
    vi.spyOn(transport, "request").mockReturnValue(
      new Promise(() => undefined)
    );
    renderOutline();

    expect((await screen.findByRole("status")).textContent).toBe(
      t.outline.loading
    );
    expect(screen.queryByRole("tree")).toBeNull();
    expect(
      screen.getByRole("heading", { name: t.outline.title }).tabIndex
    ).toBe(-1);
    expect(screen.queryByText(t.outline.keyboardHint)).toBeNull();
  });

  test("a raiz vazia diz que não há pacotes, sem árvore", async () => {
    overrideOutline([[null, { items: [], nextCursor: null }]]);
    renderOutline();

    await waitFor(() =>
      expect(screen.getByRole("status").textContent).toBe(t.graph.empty.PACKAGE)
    );
    expect(screen.queryByRole("tree")).toBeNull();
  });

  test("a raiz que falha é um alerta com título, dica e nova tentativa", async () => {
    const error = {
      code: "PROC_ANALYZER_CRASHED",
      origin: "shell",
      params: { exitCode: 1 },
    } as const;
    vi.spyOn(transport, "request").mockRejectedValueOnce(
      new FakeBridgeError(error)
    );
    renderOutline();

    const alert = await screen.findByRole("alert");
    const expected = formatProtocolError(error);
    expect(within(alert).getByText(expected.title)).toBeDefined();
    expect(within(alert).getByText(expected.hint ?? "")).toBeDefined();
    expect(screen.queryByRole("tree")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: t.graph.retry }));

    await waitForRows(ROOT_IDS.length);
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("ArchitectureOutline: rótulos do repositório", () => {
  test("todo rótulo passa por ElementLabel: o controle bidi sai, e o pacote padrão ganha o nome do catálogo", async () => {
    overrideOutline([
      [
        null,
        {
          items: [
            packageNode("pkg:", "", 2),
            packageNode("pkg:evil", `${RIGHT_TO_LEFT_OVERRIDE}evil`, 1),
          ],
          nextCursor: null,
        },
      ],
    ]);
    renderOutline();

    const [defaultPackage, evil] = await waitForRows(2);

    expect(defaultPackage?.querySelector("bdi")).toBeNull();
    expect(defaultPackage?.textContent).toContain(t.graph.defaultPackage);
    expect(evil === undefined ? null : labelOf(evil)).toBe("evil");
    expect(evil?.querySelector("bdi")?.getAttribute("translate")).toBe("no");
  });
});

describe("ArchitectureOutline: virtualização", () => {
  const thousand = Array.from({ length: 1000 }, (_, index) =>
    packageNode(`pkg:p.p${index}`, `p${index}`, 0)
  );

  test("uma raiz de 1 000 pacotes renderiza a janela exata: 15 visíveis mais a sobra, e não as 1 000", async () => {
    overrideOutline([[null, { items: thousand, nextCursor: null }]]);
    renderOutline();

    const rows = await waitForRows(23);

    expect(rows.map((row) => row.dataset.index)).toStrictEqual(
      Array.from({ length: 23 }, (_, index) => String(index))
    );
    expect(ariaOf(rows[0] ?? document.body)).toStrictEqual({
      expanded: null,
      id: "pkg:p.p0",
      level: "1",
      posinset: "1",
      selected: "false",
      setsize: "1000",
    });
  });

  test("End foca a última linha mesmo fora da janela: o alvo fica montado antes de a rolagem chegar", async () => {
    overrideOutline([[null, { items: thousand, nextCursor: null }]]);
    renderOutline();
    const [first] = await waitForRows(23);
    act(() => first?.focus());

    fireEvent.keyDown(document.activeElement ?? document.body, {
      key: "End",
    });

    expect(document.activeElement).toBe(rowById("pkg:p.p999"));
    expect(treeItems()).toHaveLength(24);
  });

  test("a linha focada continua montada, e com o foco, quando a rolagem a leva para longe", async () => {
    overrideOutline([[null, { items: thousand, nextCursor: null }]]);
    renderOutline();
    const [first] = await waitForRows(23);
    act(() => first?.focus());
    const tree = screen.getByRole("tree");

    tree.scrollTop = 14_000;
    fireEvent.scroll(tree);

    await waitForRows(32);
    expect(document.activeElement).toBe(first);
    expect(first?.dataset.index).toBe("0");
  });
});

describe("flattenOutline", () => {
  const level = (
    nodes: readonly OutlineNode[] | undefined,
    overrides: Partial<OutlineLevel> = {}
  ): OutlineLevel => ({
    failure: null,
    hasNextPage: false,
    nodes,
    ...overrides,
  });

  const expandedDrilled = (
    nodes: readonly OutlineNode[] | undefined,
    overrides: Partial<OutlineLevel> = {}
  ): ExpandedOutlineLevel => ({
    ...level(nodes, overrides),
    parentId: DRILLED,
  });

  const keysWith = (inserted: readonly string[]): string[] =>
    ROOT_IDS.flatMap((id) => (id === DRILLED ? [id, ...inserted] : [id]));

  test("sem pacote aberto: um nível só, na ordem do Java, sem reordenar nem cortar", () => {
    const rows = flattenOutline(level(ROOT_ITEMS), null);

    expect(rows).toMatchObject(
      ROOT_IDS.map((key, index) => ({
        expanded: false,
        key,
        level: 1,
        posInSet: index + 1,
        setSize: ROOT_IDS.length,
        type: "node",
      }))
    );
  });

  test("um pacote sem filhos é nó-folha: expanded indefinido, e não falso", () => {
    const rows = flattenOutline(level([packageNode("pkg:", "", 0)]), null);

    expect(rows).toMatchObject([{ expanded: undefined, key: "pkg:" }]);
  });

  test("raiz com próxima página: setsize -1 e a linha more:root no fim", () => {
    const rows = flattenOutline(level(PAGE_ONE, { hasNextPage: true }), null);

    expect(rows.map((row) => row.key)).toStrictEqual([
      ...PAGE_ONE.map((item) => item.id),
      "more:root",
    ]);
    expect(rows).toMatchObject([
      { setSize: -1 },
      { setSize: -1 },
      {
        level: 1,
        parentId: null,
        posInSet: 3,
        setSize: -1,
        shown: 2,
        total: null,
        type: "more",
      },
    ]);
  });

  test("os filhos do pacote aberto vêm logo depois dele, no nível 2", () => {
    const rows = flattenOutline(
      level(ROOT_ITEMS),
      expandedDrilled(DRILLED_CHILDREN)
    );
    const at = ROOT_IDS.indexOf(DRILLED);
    const children = DRILLED_TYPE_IDS.length;

    expect(rows.map((row) => row.key)).toStrictEqual(
      keysWith(DRILLED_TYPE_IDS)
    );
    expect(rows[at]).toMatchObject({ expanded: true, level: 1 });
    expect(rows.slice(at + 1, at + 1 + children)).toMatchObject(
      DRILLED_TYPE_IDS.map((_, index) => ({
        expanded: undefined,
        level: 2,
        parentId: DRILLED,
        posInSet: index + 1,
        setSize: children,
      }))
    );
    expect(rows[at + 1 + children]).toMatchObject({
      expanded: false,
      level: 1,
      posInSet: at + 2,
    });
  });

  test("filhos a caminho, falhos e vazios: uma pseudo-linha de chave estável cada", () => {
    const keysOf = (expanded: ExpandedOutlineLevel) =>
      flattenOutline(level(ROOT_ITEMS), expanded).map((row) => [
        row.key,
        row.type,
      ]);

    expect(keysOf(expandedDrilled(undefined))).toContainEqual([
      `loading:${DRILLED}`,
      "loading",
    ]);
    expect(
      keysOf(expandedDrilled(undefined, { failure: "load" }))
    ).toContainEqual([`error:${DRILLED}`, "error"]);
    expect(keysOf(expandedDrilled([]))).toContainEqual([
      `empty:${DRILLED}`,
      "empty",
    ]);
    expect(
      keysOf(expandedDrilled(undefined))[ROOT_IDS.indexOf(DRILLED) + 1]
    ).toStrictEqual([`loading:${DRILLED}`, "loading"]);
  });

  test("filhos com próxima página: setsize é o childCount do pai e more:<pai> diz quantos", () => {
    const rows = flattenOutline(
      level(ROOT_ITEMS),
      expandedDrilled(DRILLED_CHILDREN.slice(0, 2), { hasNextPage: true })
    );
    const at = ROOT_IDS.indexOf(DRILLED);
    const total = DRILLED_TYPE_IDS.length;

    expect(rows.map((row) => row.key)).toStrictEqual(
      keysWith([...DRILLED_TYPE_IDS.slice(0, 2), `more:${DRILLED}`])
    );
    expect(rows[at + 1]).toMatchObject({ setSize: total });
    expect(rows[at + 3]).toMatchObject({
      level: 2,
      parentId: DRILLED,
      posInSet: 3,
      setSize: total,
      shown: 2,
      total,
      type: "more",
    });
  });

  test("a página seguinte que falha: os filhos carregados ficam, e o erro toma o lugar do carregar mais", () => {
    const rows = flattenOutline(
      level(ROOT_ITEMS),
      expandedDrilled(DRILLED_CHILDREN.slice(0, 2), {
        failure: "nextPage",
        hasNextPage: true,
      })
    );

    expect(rows.map((row) => row.key)).toStrictEqual(
      keysWith([...DRILLED_TYPE_IDS.slice(0, 2), `error:${DRILLED}`])
    );
  });

  test("o pacote aberto que ainda não chegou não recebe filhos", () => {
    const rows = flattenOutline(
      level(ROOT_ITEMS.slice(0, 1), { hasNextPage: true }),
      expandedDrilled(DRILLED_CHILDREN)
    );

    expect(rows.map((row) => row.key)).toStrictEqual([
      FIRST_PACKAGE,
      "more:root",
    ]);
  });

  test("a raiz sem dados não tem linha; com dados e a página seguinte falha, error:root", () => {
    expect(flattenOutline(level(undefined), null)).toStrictEqual([]);
    expect(
      flattenOutline(
        level(PAGE_ONE, {
          failure: "nextPage",
          hasNextPage: true,
        }),
        null
      ).map((row) => row.key)
    ).toStrictEqual([...PAGE_ONE.map((item) => item.id), "error:root"]);
  });
});
