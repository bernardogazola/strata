import { QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import type { MouseEvent } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { FakeBridgeError, fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import { toGraphElements } from "@/features/graph/adapter";
import type { ArchitectureGraphProps } from "@/features/graph/architecture-graph";
import { GraphLegend } from "@/features/graph/graph-legend";
import { GraphPanel } from "@/features/graph/graph-panel";
import { NodeSummary } from "@/features/graph/node-summary";
import { EDGE_LINES, GRAPH_STYLE } from "@/features/graph/stylesheet";
import { formatProtocolError } from "@/i18n/errors";
import { interpolate, plural } from "@/i18n/format";
import {
  elementKindLabels,
  englishTermAccessibleNames,
  resolutionQualityLabels,
} from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { graphLevelSchema, resolutionQualitySchema } from "@/protocol/enums";
import type { GraphNode, GraphSlice } from "@/protocol/graph";
import { createQueryClient } from "@/queries/client";
import type { GraphViewParams } from "@/queries/options";

vi.mock("@/features/graph/architecture-graph", async (importOriginal) => {
  const actual =
    await importOriginal<
      typeof import("@/features/graph/architecture-graph")
    >();
  let current: ArchitectureGraphProps | null = null;
  const onClick = (event: MouseEvent<HTMLButtonElement>): void => {
    const { action, id } = event.currentTarget.dataset;
    if (action === "select") {
      current?.onSelect(id ?? null);
    } else if (action === "expand") {
      current?.onExpand(id ?? "");
    } else {
      current?.onRenderError(new Error("o cy.add descartou elementos"));
    }
  };
  const FakeArchitectureGraph = (props: ArchitectureGraphProps) => {
    current = props;
    const { elements, label, selectedElementId, slice } = props;
    const ids = elements?.nodes.map((node) => node.data.id ?? "") ?? [];
    return (
      <>
        <div
          aria-label={label}
          data-node-count={elements?.nodes.length ?? "none"}
          data-selected={selectedElementId ?? ""}
          data-slice-level={slice?.level ?? ""}
          role="img"
        />
        {ids.map((id) => (
          <button
            data-action="select"
            data-id={id}
            key={`select:${id}`}
            onClick={onClick}
            type="button"
          >
            {`select:${id}`}
          </button>
        ))}
        {ids.map((id) => (
          <button
            data-action="expand"
            data-id={id}
            key={`expand:${id}`}
            onClick={onClick}
            type="button"
          >
            {`expand:${id}`}
          </button>
        ))}
        <button data-action="select" onClick={onClick} type="button">
          select:none
        </button>
        <button data-action="render-error" onClick={onClick} type="button">
          render-error
        </button>
      </>
    );
  };
  return { ...actual, ArchitectureGraph: FakeArchitectureGraph };
});

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const ORDERS = "pkg:br.pucpr.orders";
const BIDI_OVERRIDE = String.fromCodePoint(0x20_2e);
const ANY_TEXT = /./u;

afterEach(() => {
  vi.restoreAllMocks();
});

const fixtureSlice = async (view: GraphViewParams): Promise<GraphSlice> =>
  (await fakeRequest("graph.slice", { analysisId: AN, ...view })) as GraphSlice;

const overrideSlice = (view: GraphViewParams, result: unknown): void => {
  vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
    { method: "graph.slice", request: { ...view }, result },
  ] satisfies readonly FakeTranscriptOverride[]);
};

const renderPanel = ({
  focus = null,
  view = { level: "PACKAGE" },
}: {
  readonly focus?: string | null;
  readonly view?: GraphViewParams;
} = {}) => {
  const onExpand = vi.fn<(elementId: string) => void>();
  const onSelect = vi.fn<(elementId: string | null) => void>();
  render(
    <QueryClientProvider client={createQueryClient()}>
      <GraphPanel
        analysisId={AN}
        focus={focus}
        onExpand={onExpand}
        onSelect={onSelect}
        view={view}
      />
    </QueryClientProvider>
  );
  return { onExpand, onSelect };
};

const canvas = () => screen.getByRole("img");

const liveRegion = () =>
  document.querySelector<HTMLElement>('[aria-live="polite"]');

const waitForNodes = async (count: number) => {
  await waitFor(() =>
    expect(canvas().getAttribute("data-node-count")).toBe(String(count))
  );
};

describe("GraphPanel: carregando e pronto", () => {
  test("anuncia o carregamento e então entrega a fatia da fixture ao canvas", async () => {
    renderPanel();

    expect(screen.getByRole("status").textContent).toBe(t.graph.loading);
    expect(canvas().getAttribute("data-node-count")).toBe("none");
    expect(canvas().closest("[aria-busy]")?.getAttribute("aria-busy")).toBe(
      "true"
    );

    await waitForNodes(3);

    expect(screen.queryByText(t.graph.loading)).toBeNull();
    expect(canvas().closest("[aria-busy]")?.getAttribute("aria-busy")).toBe(
      "false"
    );
    expect(canvas().getAttribute("data-slice-level")).toBe("PACKAGE");
  });

  test("o canvas continua o mesmo elemento do carregamento ao pronto", async () => {
    renderPanel();
    const whileLoading = canvas();

    await waitForNodes(3);

    expect(canvas()).toBe(whileLoading);
  });

  test("o nome acessível do canvas diz o nível e as contagens, com plural", async () => {
    renderPanel();
    await waitForNodes(3);

    expect(canvas().getAttribute("aria-label")).toBe(
      "Mapa da arquitetura, pacotes: 3 pacotes e 3 dependências."
    );
  });

  test("o singular sai do catálogo quando a contagem é um", async () => {
    const fixture = await fixtureSlice({ level: "PACKAGE" });
    const [first] = fixture.nodes;
    overrideSlice(
      { level: "PACKAGE" },
      {
        ...fixture,
        edges: [],
        nodes: first === undefined ? [] : [first],
        totals: { edges: 0, nodes: 1 },
      }
    );
    renderPanel();
    await waitForNodes(1);

    expect(canvas().getAttribute("aria-label")).toBe(
      "Mapa da arquitetura, pacotes: 1 pacote e nenhuma dependência."
    );
  });

  test("o zero tem redação própria, e não o singular do CLDR", async () => {
    const fixture = await fixtureSlice({ level: "PACKAGE" });
    overrideSlice(
      { level: "PACKAGE" },
      { ...fixture, edges: [], nodes: [], totals: { edges: 0, nodes: 0 } }
    );
    renderPanel();
    await screen.findByText(t.graph.empty.PACKAGE);

    expect(canvas().getAttribute("aria-label")).toBe(
      "Mapa da arquitetura, pacotes: nenhum pacote e nenhuma dependência."
    );
  });

  test("todo nível tem a forma zero, nos nós e nas arestas", () => {
    const zeroOf = (forms: Parameters<typeof plural>[1]): string =>
      interpolate(plural(0, forms), { count: 0 });

    expect(zeroOf(t.graph.canvas.edges)).toBe("nenhuma dependência");
    expect(
      graphLevelSchema.options.map((level) =>
        zeroOf(t.graph.canvas.nodes[level])
      )
    ).toStrictEqual(["nenhum pacote", "nenhum tipo", "nenhum tipo"]);
  });

  test("o nível TYPE pede os tipos do pacote", async () => {
    renderPanel({ view: { level: "TYPE", parentId: ORDERS } });
    await waitForNodes(3);

    expect(canvas().getAttribute("aria-label")).toBe(
      "Mapa da arquitetura, tipos: 3 tipos e 2 dependências."
    );
  });

  test("uma região viva, vazia enquanto carrega, anuncia a vista com o nome do canvas", async () => {
    renderPanel();

    expect(liveRegion()?.textContent).toBe("");
    expect(liveRegion()?.className).toContain("sr-only");

    await waitForNodes(3);

    expect(liveRegion()?.textContent).toBe(canvas().getAttribute("aria-label"));
    expect(liveRegion()?.textContent).toBe(
      "Mapa da arquitetura, pacotes: 3 pacotes e 3 dependências."
    );
  });

  test("o foco da URL chega ao canvas como a seleção", async () => {
    renderPanel({ focus: ORDERS });
    await waitForNodes(3);

    expect(canvas().getAttribute("data-selected")).toBe(ORDERS);
  });

  test("seleção e expansão do canvas sobem pelos callbacks", async () => {
    const { onExpand, onSelect } = renderPanel();
    await waitForNodes(3);

    fireEvent.click(screen.getByRole("button", { name: `select:${ORDERS}` }));
    fireEvent.click(screen.getByRole("button", { name: "select:none" }));
    fireEvent.click(screen.getByRole("button", { name: `expand:${ORDERS}` }));

    expect(onSelect.mock.calls).toStrictEqual([[ORDERS], [null]]);
    expect(onExpand).toHaveBeenCalledWith(ORDERS);
  });

  test("uma fatia completa não mostra aviso nenhum", async () => {
    renderPanel();
    await waitForNodes(3);

    expect(screen.queryByRole("status")).toBeNull();
  });
});

describe("GraphPanel: erro", () => {
  test("uma violação do adaptador mostra o erro inesperado, sem nova tentativa", async () => {
    const fixture = await fixtureSlice({ level: "PACKAGE" });
    overrideSlice(
      { level: "PACKAGE" },
      { ...fixture, budget: { edges: 1200, nodes: 2 } }
    );
    renderPanel();

    const alert = await screen.findByRole("alert");
    const expected = formatProtocolError({
      code: "INTERNAL_UNEXPECTED",
      origin: "frontend",
    });
    expect(within(alert).getByText(expected.title)).toBeDefined();
    expect(expected.hint).toBeDefined();
    expect(within(alert).getByText(expected.hint ?? "")).toBeDefined();
    expect(expected.retryable).toBe(true);
    expect(screen.queryByRole("button", { name: t.graph.retry })).toBeNull();
    expect(canvas().getAttribute("data-node-count")).toBe("none");
    expect(canvas().getAttribute("aria-label")).toBe(t.shell.regions.canvas);
    expect(liveRegion()?.textContent).toBe("");
  });

  test("um BridgeError que admite nova tentativa oferece o botão, e tentar pede de novo", async () => {
    const request = vi.spyOn(transport, "request").mockRejectedValueOnce(
      new FakeBridgeError({
        code: "PROC_ANALYZER_CRASHED",
        origin: "shell",
        params: { exitCode: 1 },
      })
    );
    renderPanel();

    const alert = await screen.findByRole("alert");
    const expected = formatProtocolError({
      code: "PROC_ANALYZER_CRASHED",
      origin: "shell",
      params: { exitCode: 1 },
    });
    expect(within(alert).getByText(expected.title)).toBeDefined();
    expect(expected.retryable).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: t.graph.retry }));

    await waitForNodes(3);
    expect(request).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  test("um BridgeError mostra o título do código, e sem nova tentativa quando não cabe", async () => {
    overrideSlice({ level: "PACKAGE" }, { notASlice: true });
    renderPanel();

    const alert = await screen.findByRole("alert");
    const expected = formatProtocolError({
      code: "PROTO_INVALID_PAYLOAD",
      origin: "frontend",
      params: { method: "graph.slice" },
    });
    expect(within(alert).getByText(expected.title)).toBeDefined();
    expect(expected.retryable).toBe(false);
    expect(screen.queryByRole("button", { name: t.graph.retry })).toBeNull();
  });

  test("uma falha de render do canvas vira o mesmo estado de erro, e a nova tentativa desenha", async () => {
    renderPanel();
    await waitForNodes(3);

    fireEvent.click(screen.getByRole("button", { name: "render-error" }));

    const alert = await screen.findByRole("alert");
    expect(
      within(alert).getByText(
        formatProtocolError({ code: "INTERNAL_UNEXPECTED", origin: "frontend" })
          .title
      )
    ).toBeDefined();
    expect(canvas().getAttribute("data-node-count")).toBe("none");

    fireEvent.click(screen.getByRole("button", { name: t.graph.retry }));
    await waitForNodes(3);
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("GraphPanel: falha de render sem fatia", () => {
  test("uma falha relatada antes de a fatia chegar não vira erro", async () => {
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "render-error" }));

    await waitForNodes(3);
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("GraphPanel: vazio e avisos", () => {
  test("uma fatia sem nós mostra o vazio do nível e deixa o canvas limpo", async () => {
    const fixture = await fixtureSlice({ level: "PACKAGE" });
    overrideSlice(
      { level: "PACKAGE" },
      { ...fixture, edges: [], nodes: [], totals: { edges: 0, nodes: 0 } }
    );
    renderPanel();

    expect(await screen.findByText(t.graph.empty.PACKAGE)).toBeDefined();
    expect(canvas().getAttribute("data-node-count")).toBe("none");
  });

  test("uma fatia truncada diz quanto o mapa mostra, de nós e de arestas", async () => {
    const fixture = await fixtureSlice({ level: "TYPE", parentId: ORDERS });
    overrideSlice(
      { level: "TYPE", parentId: ORDERS },
      { ...fixture, totals: { edges: 9120, nodes: 1842 }, truncated: true }
    );
    renderPanel({ view: { level: "TYPE", parentId: ORDERS } });
    await waitForNodes(3);

    expect(screen.getByRole("status").textContent).toBe(
      "Este nível tem mais elementos do que o mapa mostra: 3 de 1.842 tipos e 2 de 9.120 dependências. O limite de exibição do nível deixou o restante de fora."
    );
  });

  test('o aviso compara números: um total zero de arestas não vira "de nenhuma"', async () => {
    const fixture = await fixtureSlice({ level: "TYPE", parentId: ORDERS });
    overrideSlice(
      { level: "TYPE", parentId: ORDERS },
      {
        ...fixture,
        edges: [],
        totals: { edges: 0, nodes: 1842 },
        truncated: true,
      }
    );
    renderPanel({ view: { level: "TYPE", parentId: ORDERS } });
    await waitForNodes(3);

    const notice = screen.getByRole("status").textContent ?? "";
    expect(notice).toContain("3 de 1.842 tipos e 0 de 0 dependência.");
    expect(notice).not.toContain("nenhuma");
  });

  test("uma fatia agregada diz quantos pacotes existem e que foram agrupados", async () => {
    const fixture = await fixtureSlice({ level: "PACKAGE" });
    overrideSlice(
      { level: "PACKAGE" },
      { ...fixture, aggregated: true, totals: { edges: 7000, nodes: 1842 } }
    );
    renderPanel();
    await waitForNodes(3);

    expect(screen.getByRole("status").textContent).toBe(
      "Este repositório tem 1.842 pacotes, mais do que o primeiro nível comporta. Eles aparecem agrupados pelos prefixos de pacote de topo."
    );
  });

  test("agregada e truncada mostra os dois avisos", async () => {
    const fixture = await fixtureSlice({ level: "PACKAGE" });
    overrideSlice(
      { level: "PACKAGE" },
      {
        ...fixture,
        aggregated: true,
        totals: { edges: 7000, nodes: 1842 },
        truncated: true,
      }
    );
    renderPanel();
    await waitForNodes(3);

    expect(
      within(screen.getByRole("status")).getAllByText(ANY_TEXT)
    ).toHaveLength(2);
  });
});

describe("NodeSummary", () => {
  const packageNode = async (): Promise<GraphNode> => {
    const elements = toGraphElements(await fixtureSlice({ level: "PACKAGE" }));
    const orders = elements.nodes.find((node) => node.data.id === ORDERS);
    return orders?.data as GraphNode;
  };

  test("sem seleção, o vazio do inspetor", () => {
    render(<NodeSummary node={null} onExpand={vi.fn()} />);

    expect(screen.getByText(t.inspector.empty)).toBeDefined();
    expect(screen.queryByText(t.inspector.notOnMap.truncated)).toBeNull();
    expect(screen.queryByText(t.inspector.notOnMap.aggregated)).toBeNull();
  });

  test("uma seleção que a fatia truncada deixou fora do mapa diz isso, e não que nada foi selecionado", () => {
    render(<NodeSummary node={null} notOnMap="truncated" onExpand={vi.fn()} />);

    expect(screen.getByText(t.inspector.notOnMap.truncated)).toBeDefined();
    expect(screen.queryByText(t.inspector.empty)).toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
  });

  test("um pacote dentro de um nível 1 agrupado diz que está em um grupo, e não que foi cortado", () => {
    render(
      <NodeSummary node={null} notOnMap="aggregated" onExpand={vi.fn()} />
    );

    expect(screen.getByText(t.inspector.notOnMap.aggregated)).toBeDefined();
    expect(screen.queryByText(t.inspector.notOnMap.truncated)).toBeNull();
    expect(screen.queryByText(t.inspector.empty)).toBeNull();
  });

  test("um pacote mostra o rótulo isolado, o tipo, as métricas e o botão de expandir", async () => {
    const onExpand = vi.fn<(elementId: string) => void>();
    render(<NodeSummary node={await packageNode()} onExpand={onExpand} />);

    const heading = screen.getByRole("heading", { level: 2 });
    const label = heading.querySelector("bdi");
    expect(label?.textContent).toBe("orders");
    expect(label?.getAttribute("lang")).toBe("");
    expect(label?.getAttribute("translate")).toBe("no");

    expect(screen.getByText(elementKindLabels.PACKAGE)).toBeDefined();
    expect(screen.getByText("4.210")).toBeDefined();
    expect(screen.getByText(t.inspector.typeCount)).toBeDefined();
    expect(screen.getByText("3")).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: t.inspector.expand }));
    expect(onExpand).toHaveBeenCalledWith(ORDERS);
  });

  test("os termos ingleses têm nome acessível em português", async () => {
    render(<NodeSummary node={await packageNode()} onExpand={vi.fn()} />);

    for (const term of ["LOC", "fan-in", "fan-out"] as const) {
      const name = screen.getByText(englishTermAccessibleNames[term]);
      expect(name.className).toContain("sr-only");
      const visible = screen.getByText(term);
      expect(visible.getAttribute("aria-hidden")).toBe("true");
      expect(visible.getAttribute("title")).toBe(
        englishTermAccessibleNames[term]
      );
    }
  });

  test("o título do inspetor é o nome em Inter, e não em mono", async () => {
    render(<NodeSummary node={await packageNode()} onExpand={vi.fn()} />);

    const heading = screen.getByRole("heading", { level: 2 });
    expect(heading.className).not.toContain("font-mono");
    expect(heading.querySelector(".font-mono")).toBeNull();
  });

  test("a ressalva assenta no pé do inspetor, com ou sem seleção, depois do resumo", async () => {
    const caveat = <p>ressalva parcial</p>;
    const { rerender } = render(
      <NodeSummary caveat={caveat} node={null} onExpand={vi.fn()} />
    );
    expect(screen.getByText("ressalva parcial")).toBeDefined();

    rerender(
      <NodeSummary
        caveat={caveat}
        node={await packageNode()}
        onExpand={vi.fn()}
      />
    );
    const button = screen.getByRole("button", { name: t.inspector.expand });
    const note = screen.getByText("ressalva parcial");
    expect(button.compareDocumentPosition(note)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING
    );
    expect(note.parentElement?.classList.contains("mt-auto")).toBe(true);
  });

  test("sem ressalva, o pé fica vazio: nenhum invólucro a mais", () => {
    const { container } = render(
      <NodeSummary node={null} onExpand={vi.fn()} />
    );

    expect(container.firstElementChild?.childElementCount).toBe(1);
  });

  test("um tipo não oferece expandir nem conta tipos", async () => {
    const node = { ...(await packageNode()), id: "type:x.Y", kind: "TYPE" };
    render(<NodeSummary node={node as GraphNode} onExpand={vi.fn()} />);

    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByText(t.inspector.typeCount)).toBeNull();
    expect(screen.getByText(elementKindLabels.TYPE)).toBeDefined();
  });

  test("um rótulo hostil chega saneado e cortado", async () => {
    const hostile = `${BIDI_OVERRIDE}${"x".repeat(500)}`;
    render(
      <NodeSummary
        node={{ ...(await packageNode()), label: hostile }}
        onExpand={vi.fn()}
      />
    );

    const label = screen.getByRole("heading", { level: 2 }).textContent ?? "";
    expect(label).not.toContain(BIDI_OVERRIDE);
    expect(label.length).toBeLessThan(hostile.length);
    expect(label.endsWith("…")).toBe(true);
  });

  test("o pacote padrão do Java tem título, e não um cabeçalho vazio", async () => {
    render(
      <NodeSummary
        node={{ ...(await packageNode()), id: "pkg:", label: "" }}
        onExpand={vi.fn()}
      />
    );

    const heading = screen.getByRole("heading", { level: 2 });
    expect(heading.textContent).toBe(t.graph.defaultPackage);
    expect(heading.querySelector("bdi")).toBeNull();
  });
});

describe("GraphLegend", () => {
  test("as três qualidades de resolução, na ordem do protocolo, cada uma com o traço do canvas", () => {
    const { container } = render(<GraphLegend />);

    const items = screen
      .getByText(t.graph.legend.resolution)
      .parentElement?.querySelectorAll("li");
    expect([...(items ?? [])].map((item) => item.textContent)).toStrictEqual(
      resolutionQualitySchema.options.map(
        (quality) => resolutionQualityLabels[quality]
      )
    );

    const lines = [...container.querySelectorAll("line")];
    expect(
      lines.map((line) => [
        line.getAttribute("stroke-dasharray"),
        line.getAttribute("stroke-linecap"),
      ])
    ).toStrictEqual(
      resolutionQualitySchema.options.map((quality) => [
        EDGE_LINES[quality].dashPattern?.join(" ") ?? null,
        EDGE_LINES[quality].cap,
      ])
    );
  });

  test("forma, seleção e dica de expansão são texto; os desenhos ficam fora da árvore acessível", () => {
    const { container } = render(<GraphLegend />);

    expect(
      screen.getByRole("heading", { level: 2, name: t.graph.legend.title })
    ).toBeDefined();
    expect(screen.getByText(t.graph.legend.kindShapes.PACKAGE)).toBeDefined();
    expect(screen.getByText(t.graph.legend.kindShapes.TYPE)).toBeDefined();
    expect(screen.getByText(t.graph.legend.selection)).toBeDefined();
    expect(screen.getByText(t.graph.legend.expandHint)).toBeDefined();
    expect(screen.getByText(t.graph.legend.keyboardHint)).toBeDefined();

    const svgs = [...container.querySelectorAll("svg")];
    expect(svgs).toHaveLength(6);
    for (const svg of svgs) {
      expect(svg.getAttribute("aria-hidden")).toBe("true");
    }
  });

  test("na linha única, os nomes dos grupos ficam para o leitor de tela, sem sumir da árvore", () => {
    render(<GraphLegend />);

    const groups = screen.getAllByRole("heading", { level: 3 });
    expect(groups.map((group) => group.textContent)).toStrictEqual([
      t.graph.legend.kind,
      t.graph.legend.resolution,
    ]);
    for (const group of groups) {
      expect(group.className).toContain("sr-only");
      expect(group.nextElementSibling?.tagName).toBe("UL");
    }
  });

  test("o pacote é um retângulo, o tipo um círculo, e a seleção engrossa a borda", () => {
    render(<GraphLegend />);

    const sampleOf = (text: string) =>
      screen.getByText(text).querySelector("svg");
    expect(
      sampleOf(t.graph.legend.kindShapes.PACKAGE)?.querySelector("rect")
    ).not.toBeNull();
    expect(
      sampleOf(t.graph.legend.kindShapes.TYPE)?.querySelector("circle")
    ).not.toBeNull();
    expect(
      sampleOf(t.graph.legend.kindShapes.TYPE)?.getAttribute("stroke-width")
    ).toBe(String(GRAPH_STYLE.node.borderWidth));
    expect(
      sampleOf(t.graph.legend.selection)?.getAttribute("stroke-width")
    ).toBe(String(GRAPH_STYLE.node.selectedBorderWidth));
  });
});

describe("GraphPanel: a legenda", () => {
  const legend = () =>
    screen.getByRole("region", { name: t.graph.legend.title });

  test("é um rodapé do painel: irmã do invólucro do canvas, logo depois dele, e nunca dentro", async () => {
    renderPanel();
    await waitForNodes(3);

    const wrapper = canvas().parentElement;
    expect(wrapper?.nextElementSibling).toBe(legend());
    expect(wrapper?.contains(legend())).toBe(false);
    expect(canvas().contains(legend())).toBe(false);
  });

  test("está lá em todo estado do painel, inclusive carregando", () => {
    renderPanel();

    expect(screen.getByRole("status").textContent).toBe(t.graph.loading);
    expect(legend()).toBeDefined();
  });

  test("não tem parada de Tab: ela explica o canvas, não é controle", () => {
    render(<GraphLegend />);

    expect(
      legend().querySelectorAll(
        "a[href], button, input, select, textarea, [tabindex]"
      )
    ).toHaveLength(0);
  });
});
