import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { ElementOverview } from "@/features/inspector/element-overview";
import { MethodList } from "@/features/inspector/method-list";
import { formatInteger } from "@/i18n/format";
import { t } from "@/i18n/messages";
import { type MethodSummary, methodSummarySchema } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { DESCRIBED_IDS, detailOf } from "@/test/fixture-graph";
import { asRendered, renderInspector } from "@/test/inspector";
import { stubTableLayout } from "@/test/virtual";
import { lineRangeText } from "../inspector-labels";

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

const spokenPartsOf = (root: Element): string[] =>
  [root, ...root.querySelectorAll("*")]
    .filter(
      (element) => element.closest("[aria-hidden='true'], [hidden]") === null
    )
    .map((element) =>
      asRendered(
        [...element.childNodes]
          .filter((node) => node.nodeType === Node.TEXT_NODE)
          .map((node) => node.textContent ?? "")
          .join("")
      )
    )
    .filter((text) => text !== "");

const methodRowsOf = (container: HTMLElement): HTMLElement[] => {
  const list = container.querySelector("ol");
  if (list === null) {
    throw new Error("a lista de métodos não foi renderizada");
  }
  return within(list).getAllByRole("listitem");
};

beforeEach(() => {
  stubTableLayout();
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("cada valor de complexidade é lido com o nome da coluna (achado 2)", () => {
  test.each([
    ["Catalog, cc 1", CATALOG_ID],
    ["CatalogEntry, cc nulo", CATALOG_ENTRY_ID],
  ])(
    "%s: a linha lê a assinatura, as linhas e 'complexidade ciclomática' antes do valor",
    (_, elementId) => {
      const { methodTotal, methods } = detailOf(elementId);
      const { container } = render(
        <MethodList methods={methods} methodTotal={methodTotal} />
      );

      const rows = methodRowsOf(container);
      expect(rows).toHaveLength(methods.length);
      for (const [index, method] of methods.entries()) {
        const shown =
          method.cc === null
            ? t.inspector.methods.ccNotApplicable
            : formatInteger(method.cc);
        expect(spokenPartsOf(rows[index] as HTMLElement)).toStrictEqual([
          method.signature,
          asRendered(lineRangeText(method.lines)),
          asRendered(`${t.inspector.methods.ccHeader} ${shown}`),
        ]);
      }
    }
  );

  test("o valor à vista não muda: o número sozinho, fora da árvore de acessibilidade", () => {
    const { methodTotal, methods } = detailOf(CATALOG_ID);
    const { container } = render(
      <MethodList methods={methods} methodTotal={methodTotal} />
    );

    for (const [index, method] of methods.entries()) {
      const row = methodRowsOf(container)[index] as HTMLElement;
      const visible = within(row).getByText(formatInteger(method.cc ?? -1));
      expect(visible).toHaveAttribute("aria-hidden", "true");
    }
    expect(screen.getByText(t.inspector.methods.ccHeader)).toBeDefined();
  });
});

describe("a descrição dos botões de direção sai da ordem de leitura (achado 3)", () => {
  test("o parágrafo é `hidden`, e os dois botões ainda o têm como descrição", () => {
    const detail = detailOf(CATALOG_ID);
    render(
      <ElementOverview
        detail={detail}
        kind="TYPE"
        onOpenDependencies={vi.fn()}
        typeCount={null}
      />
    );

    const paragraph = screen.getByText(t.inspector.graphFacts.openDependencies);
    expect(paragraph.tagName).toBe("P");
    expect(paragraph).toHaveAttribute("hidden");
    const described = screen.getAllByRole("button", {
      description: t.inspector.graphFacts.openDependencies,
    });
    expect(described).toHaveLength(2);
    for (const button of described) {
      expect(button.getAttribute("aria-describedby")).toBe(paragraph.id);
    }
  });
});

const methodWith = (signature: string): MethodSummary =>
  methodSummarySchema.parse({
    cc: 2,
    elementId: `method:br.pucpr.legacy.core.Catalog#${signature}`,
    lines: { end: 40, start: 12 },
    signature,
  });

const breaksOf = (bdi: Element): string[] =>
  [...bdi.childNodes].map((node) =>
    node.nodeName === "WBR" ? "<wbr>" : (node.textContent ?? "")
  );

describe("a assinatura só quebra depois de '(', ',' e '.' (achado 4)", () => {
  test.each([
    [
      "orderMethods(MethodOrdererContext)",
      ["orderMethods(", "<wbr>", "MethodOrdererContext)"],
    ],
    [
      "put(Map.Entry, String[])",
      ["put(", "<wbr>", "Map.", "<wbr>", "Entry,", "<wbr>", " String[])"],
    ],
    ["close", ["close"]],
  ])("%s", (signature, expected) => {
    const { container } = render(
      <MethodList methods={[methodWith(signature)]} methodTotal={1} />
    );

    const bdi = within(methodRowsOf(container)[0] as HTMLElement)
      .getByText(signature)
      .closest("bdi");
    expect(bdi).not.toBeNull();
    expect(breaksOf(bdi as Element)).toStrictEqual(expected);
    expect(bdi).toHaveClass("break-words");
    expect(bdi).toHaveAttribute("translate", "no");
  });

  test("a assinatura do mundo passa pelo mesmo saneamento: um bidi do repositório some", () => {
    const override = String.fromCodePoint(0x20_2e);
    const { container } = render(
      <MethodList
        methods={[methodWith(`save${override}(CatalogEntry)`)]}
        methodTotal={1}
      />
    );

    const row = methodRowsOf(container)[0] as HTMLElement;
    expect(row.textContent).not.toContain(override);
    expect(breaksOf(row.querySelector("bdi") as Element)).toStrictEqual([
      "save(",
      "<wbr>",
      "CatalogEntry)",
    ]);
  });
});

describe("o painel 'Dependências' não é uma parada de tabulação (achado 7)", () => {
  test("Dependências: o painel fica fora do Tab; o primeiro filho é o grupo de direção", async () => {
    renderInspector({
      direction: "OUT",
      focus: CATALOG_ID,
      tab: "dependencies",
      view: { level: "TYPE", parentId: detailOf(CATALOG_ID).packageId ?? "" },
    });

    await screen.findByRole("radiogroup");
    expect(screen.getByRole("tabpanel")).toHaveAttribute("tabindex", "-1");
  });

  test("Visão geral: o painel guarda a parada dele, porque começa por texto", async () => {
    renderInspector({
      focus: CATALOG_ID,
      tab: "overview",
      view: { level: "TYPE", parentId: detailOf(CATALOG_ID).packageId ?? "" },
    });

    await screen.findByText(t.inspector.metrics.title);
    expect(screen.getByRole("tabpanel")).toHaveAttribute("tabindex", "0");
  });
});
