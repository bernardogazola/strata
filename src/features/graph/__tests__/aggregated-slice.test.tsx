import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { MouseEvent } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import {
  GraphSliceViolationError,
  toGraphElements,
} from "@/features/graph/adapter";
import type { ArchitectureGraphProps } from "@/features/graph/architecture-graph";
import { GraphPanel } from "@/features/graph/graph-panel";
import { NodeSummary } from "@/features/graph/node-summary";
import { sentenceCase } from "@/i18n/format";
import { graphNodeKindLabels, resolutionQualityLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import type { GraphNode, GraphSlice } from "@/protocol/graph";
import { createQueryClient } from "@/queries/client";
import {
  AGGREGATED_SLICE,
  ALPHA,
  ALPHA_NODE,
  BETA_NODE,
  GAMMA_NODE,
} from "./aggregated-slice-data";

vi.mock("@/features/graph/architecture-graph", async (importOriginal) => {
  const actual =
    await importOriginal<
      typeof import("@/features/graph/architecture-graph")
    >();
  let current: ArchitectureGraphProps | null = null;
  const onClick = (event: MouseEvent<HTMLButtonElement>): void => {
    current?.onSelect(event.currentTarget.dataset.id ?? null);
  };
  const FakeArchitectureGraph = (props: ArchitectureGraphProps) => {
    current = props;
    const { elements, label } = props;
    const ids = elements?.nodes.map((node) => node.data.id ?? "") ?? [];
    return (
      <>
        <div
          aria-label={label}
          data-node-count={elements?.nodes.length ?? "none"}
          role="img"
        />
        {ids.map((id) => (
          <button data-id={id} key={id} onClick={onClick} type="button">
            {`select:${id}`}
          </button>
        ))}
      </>
    );
  };
  return { ...actual, ArchitectureGraph: FakeArchitectureGraph };
});

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const EDGE_WORD = /dependência/;
const GROUP_NOUN = /grupo de pacotes/i;
const PREFIX_WORD = /prefixo/i;
const LOC_JARGON = /\bLOC\b/;
const PLAIN: GraphSlice = {
  ...AGGREGATED_SLICE,
  aggregated: false,
  edges: [],
  nodes: [
    { ...ALPHA_NODE, id: "pkg:org.acme.alpha", kind: "PACKAGE" },
    { ...BETA_NODE, id: "pkg:org.acme.beta", kind: "PACKAGE" },
    GAMMA_NODE,
  ],
  totals: { edges: 0, nodes: 3 },
  truncated: false,
};

const violationOf = (slice: GraphSlice): string | undefined => {
  try {
    toGraphElements(slice);
  } catch (error) {
    if (error instanceof GraphSliceViolationError) {
      return error.violation;
    }
    throw error;
  }
};

const withNode = (index: number, node: GraphNode): GraphSlice => {
  const previous = AGGREGATED_SLICE.nodes[index]?.id;
  const follow = (id: string): string => (id === previous ? node.id : id);
  return {
    ...AGGREGATED_SLICE,
    edges: AGGREGATED_SLICE.edges.map((edge) => ({
      ...edge,
      source: follow(edge.source),
      target: follow(edge.target),
    })),
    nodes: AGGREGATED_SLICE.nodes.map((current, at) =>
      at === index ? node : current
    ),
  };
};

afterEach(() => {
  vi.restoreAllMocks();
});

const serveSlice = (result: GraphSlice): void => {
  vi.stubGlobal("__STRATA_FAKE_TRANSCRIPTS__", [
    { method: "graph.slice", request: { level: "PACKAGE" }, result },
  ] satisfies readonly FakeTranscriptOverride[]);
};

const renderPanel = () => {
  const onSelect = vi.fn<(elementId: string | null) => void>();
  render(
    <QueryClientProvider client={createQueryClient()}>
      <GraphPanel
        analysisId={AN}
        focus={null}
        onExpand={vi.fn()}
        onSelect={onSelect}
        view={{ level: "PACKAGE" }}
      />
    </QueryClientProvider>
  );
  return { onSelect };
};

const canvas = () => screen.getByRole("img");

const waitForNodes = async (count: number) => {
  await waitFor(() =>
    expect(canvas().getAttribute("data-node-count")).toBe(String(count))
  );
};

const notices = (): string[] =>
  [...screen.getByRole("status").querySelectorAll("p")].map(
    (paragraph) => paragraph.textContent ?? ""
  );

const legend = () => screen.getByRole("region", { name: t.graph.legend.title });

const legendItems = (heading: string): HTMLLIElement[] => {
  const list = screen.getByText(heading).nextElementSibling;
  return [...(list?.querySelectorAll("li") ?? [])];
};

const shownName = (item: HTMLElement): string =>
  item.querySelector('[aria-hidden="true"]:not(svg)')?.textContent ??
  item.textContent ??
  "";

describe("toGraphElements: o nível 1 agrupado", () => {
  test("aceita grupos e pacotes lado a lado, agregada e truncada, um elemento por nó", () => {
    const elements = toGraphElements(AGGREGATED_SLICE);

    expect(elements.nodes.map((node) => node.data.kind)).toStrictEqual([
      "PACKAGE_PREFIX",
      "PACKAGE_PREFIX",
      "PACKAGE",
    ]);
    expect(elements.edges).toHaveLength(3);
  });

  test("um grupo com id de pacote é contrato quebrado: KIND_ID_MISMATCH", () => {
    expect(
      violationOf(withNode(0, { ...ALPHA_NODE, id: "pkg:org.acme.alpha" }))
    ).toBe("KIND_ID_MISMATCH");
  });

  test("um pacote com id de grupo é contrato quebrado: KIND_ID_MISMATCH", () => {
    expect(
      violationOf(withNode(2, { ...GAMMA_NODE, id: "pfx:org.acme.gamma" }))
    ).toBe("KIND_ID_MISMATCH");
  });

  test("um tipo com id de grupo é contrato quebrado: KIND_ID_MISMATCH", () => {
    expect(
      violationOf(
        withNode(2, { ...GAMMA_NODE, id: "pfx:org.acme.gamma", kind: "TYPE" })
      )
    ).toBe("KIND_ID_MISMATCH");
  });
});

describe("GraphPanel: os avisos de um nível 1 agrupado", () => {
  test("o aviso de agrupamento é a frase completa, com o total de pacotes em algarismos pt-BR", async () => {
    serveSlice({ ...AGGREGATED_SLICE, truncated: false });
    renderPanel();
    await waitForNodes(3);

    expect(notices()).toStrictEqual([
      "Este repositório tem 1.842 pacotes, mais do que o primeiro nível comporta. Eles aparecem agrupados por prefixo de pacote. Use a estrutura da arquitetura ou a busca para abrir um pacote.",
    ]);
  });

  test('agrupada e truncada: uma segunda frase, sem "de" e o total, com as dependências à vista', async () => {
    serveSlice(AGGREGATED_SLICE);
    renderPanel();
    await waitForNodes(3);

    const [, truncated, ...rest] = notices();
    expect(rest).toStrictEqual([]);
    expect(truncated).toBe(
      "Este nível tem mais do que o mapa mostra: 3 dependências estão à vista, e o limite de exibição do nível deixou o restante de fora."
    );
    expect(truncated).toMatch(EDGE_WORD);
    expect(truncated).not.toContain("de 9.120");
    expect(truncated).not.toContain("1.842");
  });

  test("o estouro que também corta grupos: a mesma frase continua verdadeira, sem dizer que só faltam dependências", async () => {
    const [kept] = AGGREGATED_SLICE.edges;
    serveSlice({
      ...AGGREGATED_SLICE,
      budget: { edges: 1200, nodes: 2 },
      edges: kept === undefined ? [] : [kept],
      nodes: [ALPHA_NODE, BETA_NODE],
    });
    renderPanel();
    await waitForNodes(2);

    const [, truncated] = notices();
    expect(truncated).toBe(
      "Este nível tem mais do que o mapa mostra: 1 dependência está à vista, e o limite de exibição do nível deixou o restante de fora."
    );
    expect(truncated).not.toContain("mais dependências");
    expect(truncated).not.toContain("as outras");
  });

  test("nenhuma dependência à vista: a forma zero, e não 0 dependência", async () => {
    serveSlice({ ...AGGREGATED_SLICE, edges: [] });
    renderPanel();
    await waitForNodes(3);

    const [, truncated] = notices();
    expect(truncated).toBe(
      "Este nível tem mais do que o mapa mostra: nenhuma dependência está à vista, e o limite de exibição do nível deixou o restante de fora."
    );
  });
});

describe("GraphPanel: o nome acessível de um nível 1 agrupado", () => {
  test("conta grupos e pacotes separadamente, cada um com o próprio plural", async () => {
    serveSlice(AGGREGATED_SLICE);
    renderPanel();
    await waitForNodes(3);

    const label = canvas().getAttribute("aria-label") ?? "";
    expect(label).toContain("2 grupos de pacotes");
    expect(label).toContain("1 pacote");
    expect(label).toContain("3 dependências");
    expect(label).not.toContain("3 pacotes");
  });

  test("um grupo só, no singular, e nenhum pacote pela forma zero", async () => {
    serveSlice({
      ...AGGREGATED_SLICE,
      edges: [],
      nodes: [{ ...ALPHA_NODE, pagerank: 1 }],
    });
    renderPanel();
    await waitForNodes(1);

    const label = canvas().getAttribute("aria-label") ?? "";
    expect(label).toContain("1 grupo de pacotes");
    expect(label).toContain("nenhum pacote");
    expect(label).toContain("nenhuma dependência");
  });
});

describe("GraphPanel: a legenda de um nível 1 agrupado", () => {
  test("a fatia agrupada ganha o item do grupo: nome à vista, frase para o leitor de tela e um desenho próprio", async () => {
    serveSlice(AGGREGATED_SLICE);
    renderPanel();
    await waitForNodes(3);

    const items = legendItems(t.graph.legend.kind);
    const known = new Set<string>([
      t.graph.legend.kindNames.PACKAGE,
      t.graph.legend.kindNames.TYPE,
      t.graph.legend.selectionName,
    ]);
    const groups = items.filter((item) => !known.has(shownName(item)));
    expect(groups).toHaveLength(1);
    const [group] = groups;
    const spoken = group?.querySelector(".sr-only")?.textContent ?? "";
    expect(spoken).toMatch(GROUP_NOUN);
    expect(spoken).not.toMatch(PREFIX_WORD);
    expect(shownName(group as HTMLElement)).not.toBe("");

    const glyphOf = (item: HTMLElement | undefined): string =>
      item?.querySelector(":scope > svg")?.innerHTML ?? "";
    const packageItem = items.find(
      (item) => shownName(item) === t.graph.legend.kindNames.PACKAGE
    );
    const typeItem = items.find(
      (item) => shownName(item) === t.graph.legend.kindNames.TYPE
    );
    expect(glyphOf(group)).not.toBe("");
    expect(glyphOf(group)).not.toBe(glyphOf(packageItem));
    expect(glyphOf(group)).not.toBe(glyphOf(typeItem));
  });

  test("a amostra do grupo é um hexágono de topo e base retos, maior que o círculo do tipo e com pontas em quina", async () => {
    serveSlice(AGGREGATED_SLICE);
    renderPanel();
    await waitForNodes(3);

    const items = legendItems(t.graph.legend.kind);
    const sampleOf = (name: string): Element | null | undefined =>
      items
        .find((item) => shownName(item) === name)
        ?.querySelector(":scope > svg > *");
    const hexagon = sampleOf(t.graph.legend.kindNames.PACKAGE_PREFIX);
    const circle = sampleOf(t.graph.legend.kindNames.TYPE);
    const vertices = (hexagon?.getAttribute("points") ?? "")
      .split(" ")
      .map((pair) => pair.split(",").map(Number));
    const xs = vertices.map(([x]) => x ?? Number.NaN);
    const ys = vertices.map(([, y]) => y ?? Number.NaN);
    const diameter = 2 * Number(circle?.getAttribute("r"));

    expect(hexagon?.tagName.toLowerCase()).toBe("polygon");
    expect(vertices).toHaveLength(6);
    expect(ys.filter((y) => y === Math.min(...ys))).toHaveLength(2);
    expect(ys.filter((y) => y === Math.max(...ys))).toHaveLength(2);
    expect(Math.max(...xs) - Math.min(...xs)).toBeGreaterThan(diameter);
    expect(Math.max(...ys) - Math.min(...ys)).toBeGreaterThan(diameter);
    expect(hexagon?.getAttribute("stroke-linejoin")).toBe("miter");
  });

  test("uma fatia sem grupos mantém só pacote, tipo e seleção", async () => {
    serveSlice(PLAIN);
    renderPanel();
    await waitForNodes(3);

    expect(legendItems(t.graph.legend.kind).map(shownName)).toStrictEqual([
      t.graph.legend.kindNames.PACKAGE,
      t.graph.legend.kindNames.TYPE,
      t.graph.legend.selectionName,
    ]);
  });

  test("a legenda das arestas tem só as duas qualidades que uma aresta do fio tem", async () => {
    serveSlice(AGGREGATED_SLICE);
    renderPanel();
    await waitForNodes(3);

    const items = legendItems(t.graph.legend.resolution);
    expect(items.map((item) => item.textContent)).toStrictEqual([
      sentenceCase(resolutionQualityLabels.RESOLVED),
      sentenceCase(resolutionQualityLabels.INFERRED),
    ]);
    expect(legend().querySelectorAll("line")).toHaveLength(2);
  });
});

describe("NodeSummary: o ramo do grupo de pacotes", () => {
  const metricRows = () =>
    [...document.querySelectorAll("dl > div")].map((row) => [
      row.querySelector("dt")?.textContent ?? "",
      row.querySelector("dd")?.textContent ?? "",
    ]);

  test("tipos, linhas de código e os vizinhos em pacotes, a unidade das bordas do grupo", () => {
    render(<NodeSummary node={ALPHA_NODE} onExpand={vi.fn()} />);

    const rows = metricRows();
    expect(rows.map(([, value]) => value)).toStrictEqual([
      "120",
      "5.400",
      "7 pacotes",
      "3 pacotes",
    ]);
    expect(rows.slice(2).map(([term]) => term)).toStrictEqual([
      t.inspector.dependsOn,
      t.inspector.usedBy,
    ]);
    const [types, loc] = rows.map(([term]) => term);
    expect(types).not.toBe("");
    expect(types).not.toBe(t.inspector.typeCount);
    expect(loc).not.toBe("");
    expect(loc).not.toMatch(LOC_JARGON);
  });

  test("um vizinho no singular e nenhum pela forma zero, em pacotes", () => {
    render(
      <NodeSummary
        node={{ ...ALPHA_NODE, fanIn: 1, fanOut: 0 }}
        onExpand={vi.fn()}
      />
    );

    expect(metricRows().slice(2)).toStrictEqual([
      [t.inspector.dependsOn, "nenhum pacote"],
      [t.inspector.usedBy, "1 pacote"],
    ]);
  });

  test("o grupo não se expande: nenhum botão, o nome dele e o rótulo do tipo de nó", () => {
    render(<NodeSummary node={ALPHA_NODE} onExpand={vi.fn()} />);

    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByRole("heading", { level: 2 }).textContent).toBe("alpha");
    expect(screen.getByText(graphNodeKindLabels.PACKAGE_PREFIX)).toBeDefined();
  });

  test("o ícone do grupo é o hexágono de 14 × 12 com pontas em quina, e não cabe no encaixe do círculo", () => {
    const { container } = render(
      <NodeSummary node={ALPHA_NODE} onExpand={vi.fn()} />
    );

    const icon = container.querySelector("dl svg");
    expect(icon?.getAttribute("width")).toBe("14");
    expect(icon?.getAttribute("height")).toBe("12");
    expect(icon?.getAttribute("stroke-linejoin")).toBe("miter");
    expect(
      icon?.querySelector("polygon")?.getAttribute("points")?.split(" ")
    ).toHaveLength(6);
    expect(icon?.parentElement?.className.split(" ")).toContain("w-3.5");
  });
});

describe("GraphPanel: selecionar um grupo", () => {
  test("o id pfx: do grupo sobe pela seleção do canvas", async () => {
    serveSlice(AGGREGATED_SLICE);
    const { onSelect } = renderPanel();
    await waitForNodes(3);

    fireEvent.click(screen.getByRole("button", { name: `select:${ALPHA}` }));

    expect(onSelect).toHaveBeenCalledWith(ALPHA);
  });
});
