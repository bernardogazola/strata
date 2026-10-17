import { QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
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
import { NodeSearch, SEARCH_DEBOUNCE_MS } from "@/features/outline/node-search";
import { formatProtocolError } from "@/i18n/errors";
import { interpolate, plural } from "@/i18n/format";
import { elementKindLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import type { SearchHit, SearchResult } from "@/protocol/graph";
import { SEARCH_QUERY_MAX_CHARS } from "@/protocol/primitives";
import { createQueryClient } from "@/queries/client";
import { graphSearchQueryOptions } from "@/queries/options";
import { useAppStore } from "@/state/app-store";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const ORDERS = "pkg:br.pucpr.orders";
const ORDER = "type:br.pucpr.orders.Order";
const ORDER_SERVICE = "type:br.pucpr.orders.OrderService";
const FIXTURE_IDS = [ORDER, ORDER_SERVICE, ORDERS];
const RESULTS_CAP = 50;
const BIDI_OVERRIDE = String.fromCodePoint(0x20_2e);

const ORDER_SERVICE_HIT: SearchHit = {
  elementId: ORDER_SERVICE,
  kind: "TYPE",
  label: "OrderService",
  packageId: ORDERS,
  qualifiedName: "br.pucpr.orders.OrderService",
};

const ORDERS_HIT: SearchHit = {
  elementId: ORDERS,
  kind: "PACKAGE",
  label: "orders",
  packageId: null,
  qualifiedName: "br.pucpr.orders",
};

beforeEach(() => {
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
});

const typeHit = (name: string, packageId: string | null): SearchHit => ({
  elementId: `type:br.pucpr.orders.${name}`,
  kind: "TYPE",
  label: name,
  packageId,
  qualifiedName: `br.pucpr.orders.${name}`,
});

const overrideResults = (results: readonly SearchHit[]): void => {
  vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
    {
      method: "graph.search",
      request: {},
      result: { results: [...results] } satisfies SearchResult,
    },
  ] satisfies readonly FakeTranscriptOverride[]);
};

const renderSearch = ({
  focus = null,
}: {
  readonly focus?: string | null;
} = {}) => {
  const queryClient = createQueryClient();
  const onReveal = vi.fn<(hit: SearchHit) => void>();
  const view = (selected: string | null) => (
    <QueryClientProvider client={queryClient}>
      <NodeSearch analysisId={AN} focus={selected} onReveal={onReveal} />
    </QueryClientProvider>
  );
  const { container, rerender } = render(view(focus));
  return {
    container,
    onReveal,
    queryClient,
    rerenderWith: (selected: string | null) => rerender(view(selected)),
  };
};

const searchbox = (): HTMLInputElement =>
  screen.getByRole<HTMLInputElement>("searchbox", { name: t.search.label });

const typeQuery = (value: string): void => {
  fireEvent.change(searchbox(), { target: { value } });
};

const listbox = (): Promise<HTMLElement> =>
  screen.findByRole("listbox", { name: t.search.results });

const options = (): HTMLElement[] => screen.getAllByRole("option");

const optionFor = (elementId: string): HTMLElement => {
  const option = options().find(
    (candidate) => candidate.dataset.elementId === elementId
  );
  if (option === undefined) {
    throw new Error(`nenhuma opção para ${elementId}`);
  }
  return option;
};

const elementIdsOf = (rows: readonly HTMLElement[]): (string | undefined)[] =>
  rows.map((row) => row.dataset.elementId);

const searchCalls = (request: MockInstance<typeof transport.request>) =>
  request.mock.calls.filter(([method]) => method === "graph.search");

const outlastDebounce = (): Promise<void> =>
  act(
    () =>
      new Promise<void>((resolve) => {
        setTimeout(resolve, SEARCH_DEBOUNCE_MS * 2);
      })
  );

const countPhrase = (count: number): string =>
  interpolate(plural(count, t.search.count), { count });

const renderWithHits = async (props?: { readonly focus?: string | null }) => {
  const rendered = renderSearch(props);
  typeQuery("Ord");
  const list = await listbox();
  await waitFor(() => {
    expect(within(list).getAllByRole("option")).toHaveLength(3);
  });
  return { ...rendered, list };
};

describe("a caixa de busca", () => {
  test("tem rótulo, placeholder, teto de 128 e nenhum autocompletar", () => {
    const { container } = renderSearch();

    const input = searchbox();
    expect(input.type).toBe("search");
    expect(input.placeholder).toBe(t.search.placeholder);
    expect(input.maxLength).toBe(SEARCH_QUERY_MAX_CHARS);
    expect(input.maxLength).toBe(128);
    expect(input.getAttribute("autocomplete")).toBe("off");
    expect(container.firstElementChild?.tagName).toBe("SEARCH");
  });

  test("sem consulta: nenhum pedido, nenhuma lista, nada anunciado", async () => {
    const request = vi.spyOn(transport, "request");
    renderSearch();

    await outlastDebounce();

    expect(searchCalls(request)).toHaveLength(0);
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(screen.getByRole("status").textContent).toBe("");
  });
});

describe("o pedido", () => {
  test("digitar O, Or, Ord depressa faz um só pedido, com Ord, pacotes e tipos", async () => {
    const request = vi.spyOn(transport, "request");
    renderSearch();

    typeQuery("O");
    typeQuery("Or");
    typeQuery("Ord");
    await listbox();

    expect(searchCalls(request)).toHaveLength(1);
    expect(request).toHaveBeenCalledWith("graph.search", {
      analysisId: AN,
      kinds: ["PACKAGE", "TYPE"],
      query: "Ord",
    });
  });

  test('"Ord   " é a mesma chave aparada e não pede de novo', async () => {
    const request = vi.spyOn(transport, "request");
    await renderWithHits();
    expect(searchCalls(request)).toHaveLength(1);

    typeQuery("Ord   ");
    await outlastDebounce();

    expect(searchCalls(request)).toHaveLength(1);
    expect(options()).toHaveLength(3);
  });

  test("só espaços não pede nada", async () => {
    const request = vi.spyOn(transport, "request");
    renderSearch();

    typeQuery("   ");
    await outlastDebounce();

    expect(searchCalls(request)).toHaveLength(0);
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});

describe("a lista", () => {
  test("mostra os resultados na ordem do Java, com os ids exatos", async () => {
    await renderWithHits();

    expect(elementIdsOf(options())).toStrictEqual(FIXTURE_IDS);
  });

  test("cada opção traz o rótulo, o tipo de elemento e o nome qualificado", async () => {
    await renderWithHits();

    const service = optionFor(ORDER_SERVICE);
    expect(service.textContent).toContain("OrderService");
    expect(service.textContent).toContain(elementKindLabels.TYPE);
    expect(service.textContent).toContain("br.pucpr.orders.OrderService");
    expect(optionFor(ORDERS).textContent).toContain(elementKindLabels.PACKAGE);
    for (const bdi of service.querySelectorAll("bdi")) {
      expect(bdi.getAttribute("translate")).toBe("no");
      expect(bdi.getAttribute("lang")).toBe("");
    }
    expect(service.querySelectorAll("bdi")).toHaveLength(2);
  });

  test("apagar a caixa esconde a lista, mesmo com os dados ainda no cache", async () => {
    const { queryClient } = await renderWithHits();

    typeQuery("");

    await waitFor(() => {
      expect(screen.queryByRole("listbox")).toBeNull();
    });
    expect(
      queryClient.getQueryData(graphSearchQueryOptions(AN, "Ord").queryKey)
    ).toBeDefined();
    expect(screen.getByRole("status").textContent).toBe("");
  });

  test("uma tabulação só: a primeira opção é a parada, as outras ficam fora", async () => {
    await renderWithHits();

    expect(options().map((option) => option.tabIndex)).toStrictEqual([
      0, -1, -1,
    ]);
  });

  test("aria-selected segue o focus da URL", async () => {
    const { rerenderWith } = await renderWithHits({ focus: ORDERS });

    expect(
      options().map((option) => option.getAttribute("aria-selected"))
    ).toStrictEqual(["false", "false", "true"]);

    rerenderWith(ORDER);

    expect(
      options().map((option) => option.getAttribute("aria-selected"))
    ).toStrictEqual(["true", "false", "false"]);
  });

  test("um caractere de controle bidi no rótulo e no nome qualificado é removido", async () => {
    overrideResults([
      {
        ...typeHit("Order", ORDERS),
        label: `Order${BIDI_OVERRIDE}Evil`,
        qualifiedName: `br.pucpr.orders.Order${BIDI_OVERRIDE}Evil`,
      },
    ]);
    renderSearch();
    typeQuery("Ord");
    await listbox();

    const [option] = options();
    expect(option?.textContent).not.toContain(BIDI_OVERRIDE);
    expect(option?.textContent).toContain("OrderEvil");
    expect(option?.textContent).toContain("br.pucpr.orders.OrderEvil");
  });

  test("enquanto a próxima consulta chega, a lista anterior fica, ocupada e calada", async () => {
    await renderWithHits();
    let resolve: (value: SearchResult) => void = () => undefined;
    vi.spyOn(transport, "request").mockImplementationOnce(
      () =>
        new Promise((settle) => {
          resolve = settle;
        })
    );

    typeQuery("Orde");

    await waitFor(() => {
      expect(screen.getByRole("listbox").getAttribute("aria-busy")).toBe(
        "true"
      );
    });
    expect(options()).toHaveLength(3);
    expect(screen.getByRole("status").textContent).toBe("");

    await act(async () => {
      resolve({ results: [ORDERS_HIT] });
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(elementIdsOf(options())).toStrictEqual([ORDERS]);
    });
    expect(screen.getByRole("listbox").getAttribute("aria-busy")).toBe("false");
    expect(screen.getByRole("status").textContent).toBe(countPhrase(1));
  });
});

describe("o teclado", () => {
  test("↓ na caixa leva o foco à primeira opção e escreve o destaque", async () => {
    await renderWithHits();

    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });

    expect(document.activeElement).toBe(optionFor(ORDER));
    expect(useAppStore.getState().activeElementId).toBe(ORDER);
    expect(optionFor(ORDER).tabIndex).toBe(0);
  });

  test("↓, ↑, Home e End movem o foco e a parada de tabulação", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });

    fireEvent.keyDown(optionFor(ORDER), { key: "ArrowDown" });
    expect(document.activeElement).toBe(optionFor(ORDER_SERVICE));
    expect(useAppStore.getState().activeElementId).toBe(ORDER_SERVICE);
    expect(options().map((option) => option.tabIndex)).toStrictEqual([
      -1, 0, -1,
    ]);

    fireEvent.keyDown(optionFor(ORDER_SERVICE), { key: "End" });
    expect(document.activeElement).toBe(optionFor(ORDERS));

    fireEvent.keyDown(optionFor(ORDERS), { key: "ArrowDown" });
    expect(document.activeElement).toBe(optionFor(ORDERS));

    fireEvent.keyDown(optionFor(ORDERS), { key: "ArrowUp" });
    expect(document.activeElement).toBe(optionFor(ORDER_SERVICE));

    fireEvent.keyDown(optionFor(ORDER_SERVICE), { key: "Home" });
    expect(document.activeElement).toBe(optionFor(ORDER));
    expect(useAppStore.getState().activeElementId).toBe(ORDER);
  });

  test("↑ na primeira opção volta para a caixa", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });

    fireEvent.keyDown(optionFor(ORDER), { key: "ArrowUp" });

    expect(document.activeElement).toBe(searchbox());
  });

  test("Escape na lista volta para a caixa", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.keyDown(optionFor(ORDER), { key: "ArrowDown" });

    fireEvent.keyDown(optionFor(ORDER_SERVICE), { key: "Escape" });

    expect(document.activeElement).toBe(searchbox());
  });

  test("Enter revela o resultado e o foco fica nele", async () => {
    const { onReveal } = await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.keyDown(optionFor(ORDER), { key: "ArrowDown" });

    fireEvent.keyDown(optionFor(ORDER_SERVICE), { key: "Enter" });

    expect(onReveal).toHaveBeenCalledTimes(1);
    expect(onReveal).toHaveBeenCalledWith(ORDER_SERVICE_HIT);
    expect(document.activeElement).toBe(optionFor(ORDER_SERVICE));
  });

  test("Espaço revela o resultado", async () => {
    const { onReveal } = await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.keyDown(optionFor(ORDER), { key: "End" });

    fireEvent.keyDown(optionFor(ORDERS), { key: " " });

    expect(onReveal).toHaveBeenCalledWith(ORDERS_HIT);
  });

  test("o clique revela o resultado e leva o foco a ele", async () => {
    const { onReveal } = await renderWithHits();

    fireEvent.click(optionFor(ORDERS));

    expect(onReveal).toHaveBeenCalledTimes(1);
    expect(onReveal).toHaveBeenCalledWith(ORDERS_HIT);
    expect(document.activeElement).toBe(optionFor(ORDERS));
  });

  test("um tipo sem pacote fica desabilitado: focável, mas não revela", async () => {
    const orphan = typeHit("Orphan", null);
    overrideResults([orphan, typeHit("Order", ORDERS)]);
    const { onReveal } = renderSearch();
    typeQuery("Or");
    await listbox();
    await waitFor(() => {
      expect(options()).toHaveLength(2);
    });
    const [disabled, enabled] = options();

    expect(disabled?.getAttribute("aria-disabled")).toBe("true");
    expect(enabled?.hasAttribute("aria-disabled")).toBe(false);

    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    expect(document.activeElement).toBe(disabled);
    fireEvent.keyDown(document.activeElement ?? document.body, {
      key: "Enter",
    });
    fireEvent.click(disabled ?? document.body);

    expect(onReveal).not.toHaveBeenCalled();
  });
});

describe("o destaque cruzado", () => {
  test("o ponteiro escreve ao entrar e solta ao sair", async () => {
    await renderWithHits();

    fireEvent.mouseOver(optionFor(ORDER_SERVICE));
    expect(useAppStore.getState().activeElementId).toBe(ORDER_SERVICE);

    fireEvent.mouseOut(optionFor(ORDER_SERVICE));
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("o ponteiro andando dentro da mesma opção não solta o destaque", async () => {
    await renderWithHits();
    const option = optionFor(ORDER_SERVICE);
    const [label, qualifiedName] = option.querySelectorAll("bdi");
    fireEvent.mouseOver(option);

    fireEvent.mouseOut(label ?? option, { relatedTarget: qualifiedName });

    expect(useAppStore.getState().activeElementId).toBe(ORDER_SERVICE);
  });

  test("o ponteiro saindo não apaga o destaque de outro escritor", async () => {
    await renderWithHits();
    fireEvent.mouseOver(optionFor(ORDER_SERVICE));

    act(() => {
      useAppStore.getState().setActiveElementId(ORDERS);
    });
    fireEvent.mouseOut(optionFor(ORDER_SERVICE));

    expect(useAppStore.getState().activeElementId).toBe(ORDERS);
  });

  test("o foco saindo da lista solta o destaque que ele escreveu", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    expect(useAppStore.getState().activeElementId).toBe(ORDER);

    fireEvent.keyDown(optionFor(ORDER), { key: "ArrowUp" });

    expect(document.activeElement).toBe(searchbox());
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("um resultado que sai da tela leva o destaque que escreveu junto", async () => {
    await renderWithHits();
    fireEvent.mouseOver(optionFor(ORDER_SERVICE));

    typeQuery("");

    await waitFor(() => {
      expect(screen.queryByRole("listbox")).toBeNull();
    });
    expect(useAppStore.getState().activeElementId).toBeNull();
  });
});

describe("o anúncio", () => {
  test("a contagem assentada, sem repetir a consulta", async () => {
    await renderWithHits();

    const status = screen.getByRole("status");
    expect(status.textContent).toBe(countPhrase(3));
    expect(status.textContent).not.toContain("Ord");
  });

  test("zero resultados: a forma zero, no lugar da lista", async () => {
    overrideResults([]);
    renderSearch();

    typeQuery("Zzz");

    await waitFor(() => {
      expect(screen.getByRole("status").textContent).toBe(t.search.count.zero);
    });
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  test("50 resultados: o teto é dito, porque o fio não traz o total", async () => {
    const capped = Array.from({ length: RESULTS_CAP }, (_, index) =>
      typeHit(`Type${index}`, ORDERS)
    );
    overrideResults(capped);
    renderSearch();

    typeQuery("Type");
    await listbox();

    await waitFor(() => {
      expect(options()).toHaveLength(RESULTS_CAP);
    });
    expect(screen.getByRole("status").textContent).toBe(
      interpolate(t.search.capped, {
        count: countPhrase(RESULTS_CAP),
        limit: RESULTS_CAP,
      })
    );
    expect(elementIdsOf(options())).toStrictEqual(
      capped.map((hit) => hit.elementId)
    );
  });

  test("49 resultados não falam em teto", async () => {
    overrideResults(
      Array.from({ length: RESULTS_CAP - 1 }, (_, index) =>
        typeHit(`Type${index}`, ORDERS)
      )
    );
    renderSearch();

    typeQuery("Type");

    await waitFor(() => {
      expect(screen.getByRole("status").textContent).toBe(
        countPhrase(RESULTS_CAP - 1)
      );
    });
  });
});

describe("a falha", () => {
  test("mostra o alerta do código, e tentar de novo pede outra vez", async () => {
    const request = vi.spyOn(transport, "request").mockRejectedValueOnce(
      new FakeBridgeError({
        code: "PROC_ANALYZER_CRASHED",
        origin: "shell",
        params: { exitCode: 1 },
      })
    );
    renderSearch();
    typeQuery("Ord");

    const alert = await screen.findByRole("alert");
    const expected = formatProtocolError({
      code: "PROC_ANALYZER_CRASHED",
      origin: "shell",
      params: { exitCode: 1 },
    });
    expect(within(alert).getByText(expected.title)).toBeDefined();
    expect(expected.retryable).toBe(true);
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(screen.getByRole("status").textContent).toBe("");

    fireEvent.click(screen.getByRole("button", { name: t.graph.retry }));

    await listbox();
    await waitFor(() => {
      expect(options()).toHaveLength(3);
    });
    expect(searchCalls(request)).toHaveLength(2);
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
