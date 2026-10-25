import { QueryClientProvider } from "@tanstack/react-query";
import type { Matcher } from "@testing-library/react";
import { act, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { transport } from "@/bridge/transport";
import {
  AGGREGATED_SLICE,
  ALPHA,
  ALPHA_NODE,
} from "@/features/graph/__tests__/aggregated-slice-data";
import { ElementInspector } from "@/features/inspector/element-inspector";
import {
  formatDecimal,
  formatInteger,
  interpolate,
  plural,
  sentenceCase,
} from "@/i18n/format";
import {
  elementKindLabels,
  resolutionQualityLabels,
  typeKindLabels,
} from "@/i18n/labels";
import { t } from "@/i18n/messages";
import type { ResolutionQuality } from "@/protocol/enums";
import type { ElementDetail } from "@/protocol/graph";
import type { GraphViewParams } from "@/queries/options";
import { useAppStore } from "@/state/app-store";
import {
  DESCRIBED_IDS,
  dependencyPageOf,
  detailOf,
  packageIdByLabel,
  packageNodeOf,
  typeSliceOf,
} from "@/test/fixture-graph";
import {
  asRendered,
  documentPositionsOf,
  focusTargetOf,
  pressKey,
  renderInspector,
  rowWithLabel,
  settle,
  textTokensOf,
  WORLD_ANALYSIS_ID,
} from "@/test/inspector";
import { stubTableLayout } from "@/test/virtual";

const typeIdOf = (label: string): string => {
  const id = DESCRIBED_IDS.find(
    (candidate) =>
      candidate.startsWith("type:") && detailOf(candidate).label === label
  );
  if (id === undefined) {
    throw new Error(`nenhum tipo descrito tem o rótulo ${label}`);
  }
  return id;
};
const CATALOG_ID = typeIdOf("Catalog");
const CATALOG_ENTRY_ID = typeIdOf("CatalogEntry");
const CORE_ID = packageIdByLabel("core");
const CORE_GEN_ID = packageIdByLabel("core.gen");

const LEVEL_ONE: GraphViewParams = { level: "PACKAGE" };

const viewOf = (detail: ElementDetail): GraphViewParams =>
  detail.packageId === null
    ? LEVEL_ONE
    : { level: "TYPE", parentId: detail.packageId };

const ESCAPED = /[.*+?^${}()|[\]\\]/gu;
const caseless = (text: string): RegExp =>
  new RegExp(`^${text.replace(ESCAPED, "\\$&")}$`, "iu");

const counted = (
  count: number,
  forms: { one: string; other: string; zero?: string }
): string => asRendered(interpolate(plural(count, forms), { count }));

const elementCalls = (request: ReturnType<typeof spyTransport>) =>
  request.mock.calls
    .filter(([method]) => method.startsWith("element."))
    .map(([method, payload]) => ({ method, payload }));

const spyTransport = () => vi.spyOn(transport, "request");

const valueBeside = (scope: HTMLElement, label: Matcher): string => {
  const term = within(scope).getByText(label);
  const dt = term.closest("dt");
  if (dt !== null) {
    const dd = dt.nextElementSibling;
    if (dd?.tagName !== "DD") {
      throw new Error(`o termo ${String(label)} não vem seguido de um <dd>`);
    }
    return asRendered(dd.textContent ?? "");
  }
  const own = asRendered(term.textContent ?? "");
  let box = term.parentElement;
  while (box !== null && asRendered(box.textContent ?? "") === own) {
    box = box.parentElement;
  }
  return asRendered(
    (box?.textContent ?? "").replace(term.textContent ?? "", "")
  );
};

const inspect = async (
  elementId: string,
  props: Partial<Parameters<typeof renderInspector>[0]> = {}
) => {
  const detail = detailOf(elementId);
  const rendered = renderInspector({
    focus: elementId,
    view: viewOf(detail),
    ...props,
  });
  const heading = await screen.findByRole("heading", { name: detail.label });
  return { ...rendered, detail, heading };
};

const DIRECTION_ROLES = ["tab", "radio", "button"] as const;
const directionControl = (name: string): HTMLElement => {
  for (const role of DIRECTION_ROLES) {
    const found = screen.queryAllByRole(role, { name });
    if (found.length === 1) {
      return found[0] as HTMLElement;
    }
  }
  throw new Error(`não há um único controle de direção chamado ${name}`);
};

const isChosen = (control: HTMLElement): boolean =>
  ["aria-selected", "aria-checked", "aria-pressed"].some(
    (attribute) => control.getAttribute(attribute) === "true"
  );

const LINE_RANGE = /linhas? \d/u;

const methodRowOf = (signature: string, cc: string): HTMLElement => {
  let box: HTMLElement | null = screen.getByText(signature);
  while (box !== null) {
    const text = box.textContent ?? "";
    const hasLines =
      text.includes(t.inspector.header.noLines) || LINE_RANGE.test(text);
    if (hasLines && tokensOf(box, signature).includes(cc)) {
      return box;
    }
    box = box.parentElement;
  }
  throw new Error(`nenhuma linha de método tem ${signature} e ${cc}`);
};

const tokensOf = (row: HTMLElement, signature: string): string[] =>
  textTokensOf(row, [signature]);

beforeEach(() => {
  stubTableLayout();
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("o que não é pacote nem tipo não vira pedido", () => {
  test("sem seleção: o vazio de sempre, e nenhum element.*", async () => {
    const request = spyTransport();
    renderInspector({ focus: null, view: LEVEL_ONE });

    expect(await screen.findByText(t.inspector.empty)).toBeDefined();
    await settle();
    expect(elementCalls(request)).toStrictEqual([]);
  });

  test("um grupo pfx: o resumo da fatia, e nenhum element.*", async () => {
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
      {
        method: "graph.slice",
        request: { level: "PACKAGE" },
        result: AGGREGATED_SLICE,
      },
    ] satisfies readonly FakeTranscriptOverride[]);
    const request = spyTransport();
    const { container } = renderInspector({ focus: ALPHA, view: LEVEL_ONE });

    const term = await screen.findByText(caseless(t.inspector.group.typeCount));
    expect(valueBeside(container, caseless(t.inspector.group.typeCount))).toBe(
      formatInteger(ALPHA_NODE.typeCount)
    );
    expect(term).toBeDefined();
    await settle();
    expect(elementCalls(request)).toStrictEqual([]);
  });

  test.each([
    ["método", detailOf(CATALOG_ID).methods[0]?.elementId ?? ""],
    ["arquivo", `file:${detailOf(CATALOG_ID).filePath}`],
  ])(
    "um %s colado na URL: nada selecionado, e nenhum element.*",
    async (_, focus) => {
      const request = spyTransport();
      renderInspector({ focus, view: viewOf(detailOf(CATALOG_ID)) });

      expect(await screen.findByText(t.inspector.empty)).toBeDefined();
      await settle();
      expect(elementCalls(request)).toStrictEqual([]);
    }
  );

  test("a ressalva de análise parcial fica no pé, com e sem seleção", async () => {
    const caveat = <p>ressalva de teste</p>;
    const { unmount } = renderInspector({
      caveat,
      focus: null,
      view: LEVEL_ONE,
    });
    expect(await screen.findByText("ressalva de teste")).toBeDefined();
    unmount();

    await inspect(CATALOG_ID, { caveat });
    expect(screen.getByText("ressalva de teste")).toBeDefined();
  });
});

describe("o cabeçalho do elemento descrito", () => {
  test.each(DESCRIBED_IDS)(
    "%s: o rótulo como título e o nome completo",
    async (id) => {
      const detail = detailOf(id);

      const { heading } = await inspect(id);

      expect(heading).toBeDefined();
      expect(screen.getByText(detail.qualifiedName)).toBeDefined();
    }
  );

  test("Catalog: o tipo, o arquivo e as linhas do describe", async () => {
    const { container, detail } = await inspect(CATALOG_ID);

    expect(detail.typeKind).toBe("CLASS");
    expect(
      screen.getByText(caseless(typeKindLabels[detail.typeKind ?? "CLASS"]))
    ).toBeDefined();
    expect(container).toHaveTextContent(detail.filePath ?? "");
    expect(container).toHaveTextContent(
      interpolate(t.inspector.header.lines, {
        end: detail.lines?.end ?? 0,
        start: detail.lines?.start ?? 0,
      })
    );
  });

  test("core: um pacote diz 'pacote', e não tem arquivo nem linhas", async () => {
    const { container, detail } = await inspect(CORE_ID);

    expect(detail.filePath).toBeNull();
    expect(screen.getByText(caseless(elementKindLabels.PACKAGE))).toBeDefined();
    expect(container).not.toHaveTextContent(t.inspector.header.noLines);
    expect(container.textContent ?? "").not.toMatch(LINE_RANGE);
  });
});

describe("as abas", () => {
  test("só 'Visão geral' e 'Dependências': o histórico não é oferecido", async () => {
    await inspect(CATALOG_ID);

    expect(
      screen.getAllByRole("tab").map((tab) => tab.textContent?.trim())
    ).toStrictEqual([t.inspector.tabs.overview, t.inspector.tabs.dependencies]);
    expect(screen.queryByText(t.inspector.tabs.history)).toBeNull();
  });

  test("um ?tab=history colado mostra a visão geral", async () => {
    await inspect(CATALOG_ID, { tab: "history" });

    expect(
      screen.getByRole("tab", { name: t.inspector.tabs.overview })
    ).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText(t.inspector.metrics.title)).toBeDefined();
  });

  test("escolher 'Dependências' pede a troca de aba", async () => {
    const { callbacks } = await inspect(CATALOG_ID);

    screen.getByRole("tab", { name: t.inspector.tabs.dependencies }).click();

    expect(callbacks.onSelectTab).toHaveBeenCalledWith("dependencies");
  });

  test("na aba de dependências, escolher 'Usado por' pede a troca de direção", async () => {
    const { callbacks } = await inspect(CATALOG_ID, { tab: "dependencies" });
    const chosen = (name: string) => directionControl(name);

    expect(isChosen(chosen(t.inspector.dependsOn))).toBe(true);
    expect(isChosen(chosen(t.inspector.usedBy))).toBe(false);

    chosen(t.inspector.usedBy).click();

    expect(callbacks.onSelectDirection).toHaveBeenCalledWith("IN");
  });
});

describe("a visão geral de um tipo", () => {
  test("as linhas de código no mesmo <dl> de toda complexidade ciclomática", async () => {
    const { container, detail } = await inspect(CATALOG_ID);
    const { metrics } = detail;

    const ccTerms = [
      t.inspector.metrics.ccSum,
      t.inspector.metrics.ccMax,
      t.inspector.metrics.ccMean,
    ];
    const locList = screen.getByText(t.inspector.metrics.loc).closest("dl");
    expect(locList).not.toBeNull();
    for (const term of ccTerms) {
      expect(screen.getByText(term).closest("dl"), term).toBe(locList);
    }
    expect(valueBeside(container, t.inspector.metrics.loc)).toBe(
      formatInteger(metrics.loc)
    );
    expect(valueBeside(container, t.inspector.metrics.ccSum)).toBe(
      formatInteger(metrics.ccSum)
    );
    expect(valueBeside(container, t.inspector.metrics.ccMax)).toBe(
      formatInteger(metrics.ccMax)
    );
    expect(valueBeside(container, t.inspector.metrics.ccMean)).toBe(
      formatDecimal(metrics.ccMean)
    );
  });

  test("a média sai com uma casa, pelo formatDecimal, e nunca arredondada a inteiro", async () => {
    const mean = 2.3;
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
      {
        method: "element.describe",
        request: { elementId: CATALOG_ID },
        result: {
          ...detailOf(CATALOG_ID),
          metrics: { ...detailOf(CATALOG_ID).metrics, ccMean: mean },
        },
      },
    ] satisfies readonly FakeTranscriptOverride[]);
    const { container } = await inspect(CATALOG_ID);

    expect(valueBeside(container, t.inspector.metrics.ccMean)).toBe(
      formatDecimal(mean)
    );
    expect(formatDecimal(mean)).toBe("2,3");
  });

  test("no grafo: 'Depende de' é o fanOut, 'Usado por' o fanIn, e nenhum ciclo", async () => {
    const { detail } = await inspect(CATALOG_ID);
    const { graph } = detail;

    expect(graph.cycle).toBeNull();
    expect(
      screen.getByText(
        counted(graph.fanOut, t.inspector.graphFacts.dependsOnValue.TYPE)
      )
    ).toBeDefined();
    expect(
      screen.getByText(
        counted(graph.fanIn, t.inspector.graphFacts.usedByValue.TYPE)
      )
    ).toBeDefined();
    expect(screen.getByText(t.inspector.graphFacts.cycleNone)).toBeDefined();
  });

  test("a qualidade de resolução: as três categorias do resolutionMix, com rótulo aberto", async () => {
    const { container, detail } = await inspect(CATALOG_ID);
    const mix = detail.graph.resolutionMix;
    const total = mix.RESOLVED + mix.INFERRED + mix.UNRESOLVED_EXTERNAL;

    expect(screen.getByText(t.inspector.resolution.title)).toBeDefined();
    expect(
      screen.getByText(counted(total, t.inspector.resolution.caption.TYPE))
    ).toBeDefined();
    for (const quality of Object.keys(
      resolutionQualityLabels
    ) as ResolutionQuality[]) {
      const value =
        mix[quality] === 0
          ? t.inspector.resolution.none
          : formatInteger(mix[quality]);
      expect(
        valueBeside(container, sentenceCase(resolutionQualityLabels[quality])),
        quality
      ).toBe(value);
    }
  });

  test("Catalog: os métodos na ordem do fio, cada um com as linhas e a complexidade", async () => {
    const { container, detail } = await inspect(CATALOG_ID);

    expect(
      screen.getByText(
        interpolate(t.inspector.methods.title, { count: detail.methodTotal })
      )
    ).toBeDefined();
    expect(screen.getByText(t.inspector.methods.ccHeader)).toBeDefined();
    const positions = documentPositionsOf(
      container,
      detail.methods.map((method) => screen.getByText(method.signature))
    );
    expect(positions).toStrictEqual([...positions].sort((a, b) => a - b));
    for (const method of detail.methods) {
      const row = methodRowOf(method.signature, formatInteger(method.cc ?? -1));
      expect(row).toHaveTextContent(
        interpolate(t.inspector.header.lines, {
          end: method.lines?.end ?? 0,
          start: method.lines?.start ?? 0,
        })
      );
      expect(row).not.toHaveTextContent(t.inspector.methods.ccNotApplicable);
    }
  });

  test("CatalogEntry: cc nulo diz 'não se aplica', sem linhas diz que não há, nunca 0, 1 ou traço", async () => {
    const { detail } = await inspect(CATALOG_ENTRY_ID);

    expect(detail.methods).toHaveLength(3);
    for (const method of detail.methods) {
      expect(method.cc).toBeNull();
      expect(method.lines).toBeNull();
      const lastWord =
        t.inspector.methods.ccNotApplicable.split(" ").at(-1) ?? "";
      const row = methodRowOf(method.signature, lastWord);
      expect(row, method.signature).toHaveTextContent(
        t.inspector.methods.ccNotApplicable
      );
      expect(row, method.signature).toHaveTextContent(
        t.inspector.header.noLines
      );
      const tokens = tokensOf(row, method.signature);
      for (const placeholder of ["0", "1", "-", "–", "—"]) {
        expect(tokens, method.signature).not.toContain(placeholder);
      }
    }
    expect(screen.getAllByText(t.inspector.methods.ccNote)).toHaveLength(1);
  });
});

describe("a visão geral de um pacote", () => {
  test("core: os tipos do pacote, o ciclo 'incluindo este', e nenhum método", async () => {
    const { container, detail } = await inspect(CORE_ID);
    const { graph } = detail;
    const { typeCount } = packageNodeOf(CORE_ID);

    expect(valueBeside(container, caseless(t.inspector.typeCount))).toBe(
      formatInteger(typeCount)
    );
    expect(
      screen.getByText(
        counted(
          graph.cycle?.size ?? 0,
          t.inspector.graphFacts.cycleSize.PACKAGE
        )
      )
    ).toBeDefined();
    expect(
      screen.getByText(
        counted(graph.fanOut, t.inspector.graphFacts.dependsOnValue.PACKAGE)
      )
    ).toBeDefined();
    expect(
      screen.getByText(
        counted(graph.fanIn, t.inspector.graphFacts.usedByValue.PACKAGE)
      )
    ).toBeDefined();
    const methodsTitle = t.inspector.methods.title.split("{")[0] ?? "";
    expect(container.textContent ?? "").not.toContain(methodsTitle);
    expect(screen.queryByText(t.inspector.methods.none)).toBeNull();
  });

  test("'Ver os tipos deste pacote' abre o nível TYPE do pacote", async () => {
    const { callbacks } = await inspect(CORE_ID);

    screen.getByRole("button", { name: t.inspector.expand }).click();

    expect(callbacks.onExpand).toHaveBeenCalledWith(CORE_ID);
  });
});

describe("um tipo que a fatia truncada deixou fora do mapa", () => {
  test("a ressalva de sempre, e também os detalhes do describe", async () => {
    const slice = typeSliceOf(CORE_ID);
    vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
      {
        method: "graph.slice",
        request: { level: "TYPE", parentId: CORE_ID },
        result: {
          ...slice,
          edges: slice.edges.filter(
            (edge) => edge.source !== CATALOG_ID && edge.target !== CATALOG_ID
          ),
          nodes: slice.nodes.filter((node) => node.id !== CATALOG_ID),
          totals: { edges: 9120, nodes: 1842 },
          truncated: true,
        },
      },
    ] satisfies readonly FakeTranscriptOverride[]);

    await inspect(CATALOG_ID);

    expect(
      await screen.findByText(t.inspector.notOnMap.truncated)
    ).toBeDefined();
    expect(screen.getByText(detailOf(CATALOG_ID).qualifiedName)).toBeDefined();
  });
});

describe("os pedidos de dependências", () => {
  test("na visão geral, só o describe: nenhuma página de dependências", async () => {
    const request = spyTransport();
    await inspect(CATALOG_ID);
    await settle();

    expect(elementCalls(request)).toStrictEqual([
      {
        method: "element.describe",
        payload: { analysisId: WORLD_ANALYSIS_ID, elementId: CATALOG_ID },
      },
    ]);
  });

  test("na aba de dependências, só a direção escolhida", async () => {
    const request = spyTransport();
    await inspect(CATALOG_ID, { direction: "IN", tab: "dependencies" });
    const [first] = dependencyPageOf(CATALOG_ID, "IN").items;
    await screen.findByText(first?.label ?? "");
    await settle();

    const directions = elementCalls(request)
      .filter(({ method }) => method === "element.dependencies")
      .map(({ payload }) => (payload as { direction: string }).direction);
    expect(directions).toStrictEqual(["IN"]);
  });
});

describe("o que a aba de dependências diz sobre a lista", () => {
  test("só externas: 'Nenhum tipo do repositório e 1 dependência externa'", async () => {
    const metadataId = typeIdOf("CatalogMetadata");
    const { totals } = dependencyPageOf(metadataId, "OUT");
    expect(totals.out).toBe(0);
    const external = totals.outExternal ?? 0;
    const summary = interpolate(t.inspector.dependencies.summary.withExternal, {
      external: interpolate(
        plural(external, t.inspector.dependencies.externalCount),
        { count: external }
      ),
      internal: interpolate(
        plural(totals.out, t.inspector.dependencies.summary.internal.TYPE),
        { count: totals.out }
      ),
    });

    await inspect(metadataId, { tab: "dependencies" });

    const shown = (
      await screen.findAllByText(
        (_, element) =>
          asRendered(element?.textContent ?? "") === asRendered(summary)
      )
    ).at(-1);
    const NO_BREAK = String.fromCodePoint(0xa0);
    expect(shown?.textContent).toContain(`${external}${NO_BREAK}`);
  });

  test("ninguém usa Main: a frase do vazio de 'Usado por' de um tipo", async () => {
    const mainId = typeIdOf("Main");
    expect(dependencyPageOf(mainId, "IN").items).toStrictEqual([]);

    await inspect(mainId, { direction: "IN", tab: "dependencies" });

    expect(
      await screen.findByText(t.inspector.dependencies.empty.IN.TYPE)
    ).toBeDefined();
  });

  test("Audited não depende de nada: a frase do vazio de 'Depende de' de um tipo", async () => {
    const auditedId = typeIdOf("Audited");
    expect(dependencyPageOf(auditedId, "OUT").items).toStrictEqual([]);

    await inspect(auditedId, { direction: "OUT", tab: "dependencies" });

    expect(
      await screen.findByText(t.inspector.dependencies.empty.OUT.TYPE)
    ).toBeDefined();
  });

  test("core.gen não tem vizinhos: as frases do vazio de um pacote", async () => {
    expect(dependencyPageOf(CORE_GEN_ID, "IN").items).toStrictEqual([]);
    const { unmount } = await inspect(CORE_GEN_ID, {
      direction: "IN",
      tab: "dependencies",
    });
    expect(
      await screen.findByText(t.inspector.dependencies.empty.IN.PACKAGE)
    ).toBeDefined();
    unmount();

    await inspect(CORE_GEN_ID, { direction: "OUT", tab: "dependencies" });
    expect(
      await screen.findByText(t.inspector.dependencies.empty.OUT.PACKAGE)
    ).toBeDefined();
  });
});

describe("revelar uma linha passa o foco ao título do elemento novo", () => {
  test("Enter em Audited, no mesmo pacote, e o foco chega ao título de Audited", async () => {
    const audited = dependencyPageOf(CATALOG_ID, "OUT").items.find(
      (entry) => entry.packageId === detailOf(CATALOG_ID).packageId
    );
    expect(audited).toBeDefined();
    const { callbacks, queryClient, rerender } = await inspect(CATALOG_ID, {
      tab: "dependencies",
    });
    const table = await screen.findByRole("table");
    await within(table).findByText(audited?.label ?? "");
    const target = focusTargetOf(rowWithLabel(table, audited?.label ?? ""));
    act(() => {
      target.focus();
    });

    pressKey(target, "Enter");
    expect(callbacks.onReveal).toHaveBeenCalledTimes(1);
    rerender(
      <QueryClientProvider client={queryClient}>
        <ElementInspector
          analysisId={WORLD_ANALYSIS_ID}
          direction="OUT"
          focus={audited?.elementId ?? null}
          tab="dependencies"
          view={viewOf(detailOf(CATALOG_ID))}
          {...callbacks}
        />
      </QueryClientProvider>
    );

    const heading = await screen.findByRole("heading", {
      name: audited?.label ?? "",
    });
    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
  });
});
