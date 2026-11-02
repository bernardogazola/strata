import { QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  configure,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import {
  afterAll,
  afterEach,
  beforeAll,
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
import {
  DRILLED_PACKAGE_ID,
  packageIdByLabel,
  SEARCH_HITS,
  SEARCH_QUERY,
} from "@/test/fixture-graph";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const RESULTS_CAP = 50;
const BIDI_OVERRIDE = String.fromCodePoint(0x20_2e);

const requireHit = (hit: SearchHit | undefined): SearchHit => {
  if (hit === undefined) {
    throw new Error("graph.search.hit tem menos resultados do que o teste lê");
  }
  return hit;
};

const FIXTURE_IDS = SEARCH_HITS.map((hit) => hit.elementId);
const FIRST_HIT = requireHit(SEARCH_HITS[0]);
const SECOND_HIT = requireHit(SEARCH_HITS[1]);
const LAST_HIT = requireHit(SEARCH_HITS.at(-1));
const FIRST = FIRST_HIT.elementId;
const SECOND = SECOND_HIT.elementId;
const LAST = LAST_HIT.elementId;

const qualifiedOf = (packageId: string): string =>
  packageId.slice("pkg:".length);

const DEBOUNCED_WAIT_MS = 4000;
const TESTING_LIBRARY_DEFAULT_WAIT_MS = 1000;

beforeAll(() => {
  configure({ asyncUtilTimeout: DEBOUNCED_WAIT_MS });
});

afterAll(() => {
  configure({ asyncUtilTimeout: TESTING_LIBRARY_DEFAULT_WAIT_MS });
});

beforeEach(() => {
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const typeHit = (name: string, packageId: string | null): SearchHit => ({
  elementId: `type:${qualifiedOf(DRILLED_PACKAGE_ID)}.${name}`,
  kind: "TYPE",
  label: name,
  packageId,
  qualifiedName: `${qualifiedOf(DRILLED_PACKAGE_ID)}.${name}`,
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

const SPEC_DEBOUNCE_MS = 250;

const advance = async (ms: number): Promise<void> => {
  await act(() => vi.advanceTimersByTimeAsync(ms));
  await act(() => vi.advanceTimersByTimeAsync(0));
};

const renderWithHits = async (props?: { readonly focus?: string | null }) => {
  const rendered = renderSearch(props);
  typeQuery(SEARCH_QUERY);
  const list = await listbox();
  await waitFor(() => {
    expect(within(list).getAllByRole("option")).toHaveLength(
      SEARCH_HITS.length
    );
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
    expect(input.className).toContain("placeholder:text-muted-foreground");
    expect(input.className).not.toContain("placeholder:text-subtle-foreground");
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
  test("digitar C, Ca, Cat depressa faz um só pedido, com Cat, pacotes e tipos", async () => {
    const request = vi.spyOn(transport, "request");
    renderSearch();

    typeQuery("C");
    typeQuery("Ca");
    typeQuery("Cat");
    await listbox();

    expect(searchCalls(request)).toHaveLength(1);
    expect(request).toHaveBeenCalledWith("graph.search", {
      analysisId: AN,
      kinds: ["PACKAGE", "TYPE"],
      query: "Cat",
    });
  });

  test("a consulta com espaços no fim é a mesma chave aparada e não pede de novo", async () => {
    const request = vi.spyOn(transport, "request");
    await renderWithHits();
    expect(searchCalls(request)).toHaveLength(1);

    typeQuery(`${SEARCH_QUERY}   `);
    await outlastDebounce();

    expect(searchCalls(request)).toHaveLength(1);
    expect(options()).toHaveLength(SEARCH_HITS.length);
  });

  test("só espaços não pede nada", async () => {
    const request = vi.spyOn(transport, "request");
    renderSearch();

    typeQuery("   ");
    await outlastDebounce();

    expect(searchCalls(request)).toHaveLength(0);
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  test("nada sai antes de a pausa vencer, e um pedido sai quando ela vence", async () => {
    vi.useFakeTimers();
    const request = vi.spyOn(transport, "request");
    renderSearch();

    typeQuery("Cat");
    await advance(SPEC_DEBOUNCE_MS - 1);
    expect(searchCalls(request)).toHaveLength(0);

    await advance(1);
    expect(searchCalls(request)).toHaveLength(1);
    expect(SEARCH_DEBOUNCE_MS).toBe(SPEC_DEBOUNCE_MS);
  });

  test("cada tecla recomeça a pausa: o pedido é da última, não da primeira", async () => {
    vi.useFakeTimers();
    const request = vi.spyOn(transport, "request");
    renderSearch();

    typeQuery("C");
    await advance(SPEC_DEBOUNCE_MS - 50);
    typeQuery("Ca");
    await advance(SPEC_DEBOUNCE_MS - 50);
    expect(searchCalls(request)).toHaveLength(0);

    await advance(50);
    expect(searchCalls(request)).toHaveLength(1);
    expect(request).toHaveBeenCalledWith(
      "graph.search",
      expect.objectContaining({ query: "Ca" })
    );
  });
});

describe("apagar a caixa e digitar outra busca", () => {
  const SPI = packageIdByLabel("core.spi");
  const SPI_HIT: SearchHit = {
    elementId: SPI,
    kind: "PACKAGE",
    label: "core.spi",
    packageId: null,
    qualifiedName: qualifiedOf(SPI),
  };
  const answerByQuery = () =>
    vi
      .spyOn(transport, "request")
      .mockImplementation((_method: string, payload: unknown) =>
        Promise.resolve({
          results: [
            (payload as { readonly query: string }).query === "Cat"
              ? FIRST_HIT
              : SPI_HIT,
          ],
        } satisfies SearchResult)
      );

  const queriesOf = (request: ReturnType<typeof answerByQuery>) =>
    searchCalls(request).map(
      ([, payload]) => (payload as { query: string }).query
    );

  test("dentro da pausa, a busca apagada não volta: nem mostrada, nem anunciada, nem ativável", async () => {
    vi.useFakeTimers();
    const request = answerByQuery();
    const { onReveal } = renderSearch();
    typeQuery("Cat");
    await advance(SEARCH_DEBOUNCE_MS);
    expect(elementIdsOf(options())).toStrictEqual([FIRST]);

    typeQuery("");
    await advance(100);
    typeQuery("spi");
    await advance(10);

    expect(searchbox().value).toBe("spi");
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(screen.getByRole("status").textContent).toBe("");
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.keyDown(document.activeElement ?? document.body, {
      key: "Enter",
    });
    expect(onReveal).not.toHaveBeenCalled();

    await advance(SEARCH_DEBOUNCE_MS);
    expect(elementIdsOf(options())).toStrictEqual([SPI]);
    expect(screen.getByRole("status").textContent).toBe(countPhrase(1));
    expect(queriesOf(request)).toStrictEqual(["Cat", "spi"]);
  });

  test("enquanto a nova busca chega, a lista apagada não volta como substituta", async () => {
    vi.useFakeTimers();
    const request = answerByQuery();
    const { onReveal } = renderSearch();
    typeQuery("Cat");
    await advance(SEARCH_DEBOUNCE_MS);
    expect(elementIdsOf(options())).toStrictEqual([FIRST]);
    let answer: (value: SearchResult) => void = () => undefined;
    request.mockImplementationOnce(
      () =>
        new Promise((settle) => {
          answer = settle;
        })
    );

    typeQuery("");
    typeQuery("spi");
    await advance(SEARCH_DEBOUNCE_MS);

    expect(queriesOf(request)).toStrictEqual(["Cat", "spi"]);
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(screen.getByRole("status").textContent).toBe("");
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.keyDown(document.activeElement ?? document.body, {
      key: "Enter",
    });
    expect(onReveal).not.toHaveBeenCalled();

    await act(async () => {
      answer({ results: [SPI_HIT] });
      await Promise.resolve();
    });
    await advance(0);
    expect(elementIdsOf(options())).toStrictEqual([SPI]);
    expect(screen.getByRole("status").textContent).toBe(countPhrase(1));
  });
});

describe("a lista", () => {
  test("mostra os resultados na ordem do Java, com os ids exatos", async () => {
    await renderWithHits();

    expect(elementIdsOf(options())).toStrictEqual(FIXTURE_IDS);
  });

  test("cada opção traz o rótulo, o tipo de elemento e o nome qualificado", async () => {
    await renderWithHits();

    const service = optionFor(SECOND);
    expect(service.textContent).toContain(SECOND_HIT.label);
    expect(service.textContent).toContain(elementKindLabels.TYPE);
    expect(service.textContent).toContain(SECOND_HIT.qualifiedName);
    expect(optionFor(LAST).textContent).toContain(elementKindLabels.PACKAGE);
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
      queryClient.getQueryData(
        graphSearchQueryOptions(AN, SEARCH_QUERY).queryKey
      )
    ).toBeDefined();
    expect(screen.getByRole("status").textContent).toBe("");
  });

  test("uma tabulação só: a primeira opção é a parada, as outras ficam fora", async () => {
    await renderWithHits();

    expect(options().map((option) => option.tabIndex)).toStrictEqual(
      FIXTURE_IDS.map((id) => (id === FIRST ? 0 : -1))
    );
  });

  test("aria-selected segue o focus da URL", async () => {
    const { rerenderWith } = await renderWithHits({ focus: LAST });

    expect(
      options().map((option) => option.getAttribute("aria-selected"))
    ).toStrictEqual(FIXTURE_IDS.map((id) => String(id === LAST)));

    rerenderWith(FIRST);

    expect(
      options().map((option) => option.getAttribute("aria-selected"))
    ).toStrictEqual(FIXTURE_IDS.map((id) => String(id === FIRST)));
  });

  test("um caractere de controle bidi no rótulo e no nome qualificado é removido", async () => {
    const evil = typeHit("Catalog", DRILLED_PACKAGE_ID);
    overrideResults([
      {
        ...evil,
        label: `Catalog${BIDI_OVERRIDE}Evil`,
        qualifiedName: `${evil.qualifiedName}${BIDI_OVERRIDE}Evil`,
      },
    ]);
    renderSearch();
    typeQuery("Cat");
    await listbox();

    const [option] = options();
    expect(option?.textContent).not.toContain(BIDI_OVERRIDE);
    expect(option?.textContent).toContain("CatalogEvil");
    expect(option?.textContent).toContain(`${evil.qualifiedName}Evil`);
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

    typeQuery("core");

    await waitFor(() => {
      expect(screen.getByRole("listbox").getAttribute("aria-busy")).toBe(
        "true"
      );
    });
    expect(options()).toHaveLength(SEARCH_HITS.length);
    expect(screen.getByRole("status").textContent).toBe("");

    await act(async () => {
      resolve({ results: [LAST_HIT] });
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(elementIdsOf(options())).toStrictEqual([LAST]);
    });
    expect(screen.getByRole("listbox").getAttribute("aria-busy")).toBe("false");
    expect(screen.getByRole("status").textContent).toBe(countPhrase(1));
  });
});

describe("o teclado", () => {
  test("↓ na caixa leva o foco à primeira opção e escreve o destaque", async () => {
    await renderWithHits();

    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });

    expect(document.activeElement).toBe(optionFor(FIRST));
    expect(useAppStore.getState().activeElementId).toBe(FIRST);
    expect(optionFor(FIRST).tabIndex).toBe(0);
  });

  test("↓, ↑, Home e End movem o foco e a parada de tabulação", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });

    fireEvent.keyDown(optionFor(FIRST), { key: "ArrowDown" });
    expect(document.activeElement).toBe(optionFor(SECOND));
    expect(useAppStore.getState().activeElementId).toBe(SECOND);
    expect(options().map((option) => option.tabIndex)).toStrictEqual(
      FIXTURE_IDS.map((id) => (id === SECOND ? 0 : -1))
    );

    fireEvent.keyDown(optionFor(SECOND), { key: "End" });
    expect(document.activeElement).toBe(optionFor(LAST));

    fireEvent.keyDown(optionFor(LAST), { key: "ArrowDown" });
    expect(document.activeElement).toBe(optionFor(LAST));

    const penultimate = FIXTURE_IDS.at(-2) ?? "";
    fireEvent.keyDown(optionFor(LAST), { key: "ArrowUp" });
    expect(document.activeElement).toBe(optionFor(penultimate));

    fireEvent.keyDown(optionFor(penultimate), { key: "Home" });
    expect(document.activeElement).toBe(optionFor(FIRST));
    expect(useAppStore.getState().activeElementId).toBe(FIRST);
  });

  test("↑ na primeira opção volta para a caixa", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });

    fireEvent.keyDown(optionFor(FIRST), { key: "ArrowUp" });

    expect(document.activeElement).toBe(searchbox());
  });

  test("Escape na lista volta para a caixa", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.keyDown(optionFor(FIRST), { key: "ArrowDown" });

    fireEvent.keyDown(optionFor(SECOND), { key: "Escape" });

    expect(document.activeElement).toBe(searchbox());
  });

  test("Enter revela o resultado e o foco fica nele", async () => {
    const { onReveal } = await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.keyDown(optionFor(FIRST), { key: "ArrowDown" });

    fireEvent.keyDown(optionFor(SECOND), { key: "Enter" });

    expect(onReveal).toHaveBeenCalledTimes(1);
    expect(onReveal).toHaveBeenCalledWith(SECOND_HIT);
    expect(document.activeElement).toBe(optionFor(SECOND));
  });

  test("Espaço revela o resultado", async () => {
    const { onReveal } = await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.keyDown(optionFor(FIRST), { key: "End" });

    fireEvent.keyDown(optionFor(LAST), { key: " " });

    expect(onReveal).toHaveBeenCalledWith(LAST_HIT);
  });

  test("o clique revela o resultado e leva o foco a ele", async () => {
    const { onReveal } = await renderWithHits();

    fireEvent.click(optionFor(LAST));

    expect(onReveal).toHaveBeenCalledTimes(1);
    expect(onReveal).toHaveBeenCalledWith(LAST_HIT);
    expect(document.activeElement).toBe(optionFor(LAST));
  });

  test("um tipo sem pacote fica desabilitado: focável, mas não revela", async () => {
    const orphan = typeHit("Orphan", null);
    overrideResults([orphan, typeHit("Catalog", DRILLED_PACKAGE_ID)]);
    const { onReveal } = renderSearch();
    typeQuery("Ca");
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

describe("o foco em uma opção que a nova consulta tira", () => {
  test("a lista que desmonta com o foco dentro o devolve à caixa", async () => {
    await renderWithHits();
    vi.spyOn(transport, "request").mockResolvedValueOnce({ results: [] });

    typeQuery(`${SEARCH_QUERY}zz`);
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    expect(document.activeElement).toBe(optionFor(FIRST));

    await waitFor(() => {
      expect(screen.queryByRole("listbox")).toBeNull();
    });
    expect(document.activeElement).toBe(searchbox());
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("a opção focada que some da nova lista devolve o foco à caixa", async () => {
    await renderWithHits();
    vi.spyOn(transport, "request").mockResolvedValueOnce({
      results: [LAST_HIT],
    });

    typeQuery("core");
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    expect(document.activeElement).toBe(optionFor(FIRST));

    await waitFor(() => {
      expect(elementIdsOf(options())).toStrictEqual([LAST]);
    });
    expect(document.activeElement).toBe(searchbox());
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("a opção focada que continua na nova lista fica com o foco", async () => {
    await renderWithHits();
    vi.spyOn(transport, "request").mockResolvedValueOnce({
      results: [LAST_HIT],
    });

    typeQuery("core");
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.keyDown(optionFor(FIRST), { key: "End" });
    expect(document.activeElement).toBe(optionFor(LAST));

    await waitFor(() => {
      expect(elementIdsOf(options())).toStrictEqual([LAST]);
    });
    expect(document.activeElement).toBe(optionFor(LAST));
    expect(useAppStore.getState().activeElementId).toBe(LAST);
  });
});

describe("o destaque cruzado", () => {
  test("o ponteiro escreve ao entrar e solta ao sair", async () => {
    await renderWithHits();

    fireEvent.mouseOver(optionFor(SECOND));
    expect(useAppStore.getState().activeElementId).toBe(SECOND);

    fireEvent.mouseOut(optionFor(SECOND));
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("o ponteiro andando dentro da mesma opção não solta o destaque", async () => {
    await renderWithHits();
    const option = optionFor(SECOND);
    const [label, qualifiedName] = option.querySelectorAll("bdi");
    fireEvent.mouseOver(option);

    fireEvent.mouseOut(label ?? option, { relatedTarget: qualifiedName });

    expect(useAppStore.getState().activeElementId).toBe(SECOND);
  });

  test("o ponteiro saindo não apaga o destaque de outro escritor", async () => {
    await renderWithHits();
    fireEvent.mouseOver(optionFor(SECOND));

    act(() => {
      useAppStore.getState().setActiveElementId(LAST);
    });
    fireEvent.mouseOut(optionFor(SECOND));

    expect(useAppStore.getState().activeElementId).toBe(LAST);
  });

  test("o ponteiro cruzando um nó do canvas não apaga o destaque da opção com foco", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    expect(useAppStore.getState().activeElementId).toBe(FIRST);

    act(() => {
      useAppStore.getState().setActiveElementId(LAST);
    });
    expect(useAppStore.getState().activeElementId).toBe(LAST);
    act(() => {
      useAppStore.getState().releaseActiveElementId(LAST);
    });

    expect(document.activeElement).toBe(optionFor(FIRST));
    expect(useAppStore.getState().activeElementId).toBe(FIRST);
  });

  test("o ponteiro saindo de outra opção devolve o destaque à opção com foco", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    fireEvent.mouseOver(optionFor(SECOND));
    expect(useAppStore.getState().activeElementId).toBe(SECOND);

    fireEvent.mouseOut(optionFor(SECOND));

    expect(useAppStore.getState().activeElementId).toBe(FIRST);
  });

  test("o foco saindo da lista solta o destaque que ele escreveu", async () => {
    await renderWithHits();
    fireEvent.keyDown(searchbox(), { key: "ArrowDown" });
    expect(useAppStore.getState().activeElementId).toBe(FIRST);

    fireEvent.keyDown(optionFor(FIRST), { key: "ArrowUp" });

    expect(document.activeElement).toBe(searchbox());
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("um resultado que sai da tela leva o destaque que escreveu junto", async () => {
    await renderWithHits();
    fireEvent.mouseOver(optionFor(SECOND));

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
    expect(status.textContent).toBe(countPhrase(SEARCH_HITS.length));
    expect(status.textContent).not.toContain(SEARCH_QUERY);
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
      typeHit(`Type${index}`, DRILLED_PACKAGE_ID)
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
        typeHit(`Type${index}`, DRILLED_PACKAGE_ID)
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
    typeQuery(SEARCH_QUERY);

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
      expect(options()).toHaveLength(SEARCH_HITS.length);
    });
    expect(searchCalls(request)).toHaveLength(2);
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
