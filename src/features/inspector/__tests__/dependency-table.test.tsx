import { act, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { transport } from "@/bridge/transport";
import {
  DependencyTable,
  type InspectedElement,
} from "@/features/inspector/dependency-table";
import { interpolate, plural } from "@/i18n/format";
import {
  dependencyTypeLabels,
  dependencyTypePassiveLabels,
  resolutionQualityLabels,
} from "@/i18n/labels";
import { t } from "@/i18n/messages";
import type { DependencyDirection, DependencyType } from "@/protocol/enums";
import type { DependencyEntry } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { externalTypeRow, pageOf } from "@/test/dependency-rows";
import {
  DESCRIBED_IDS,
  dependencyPageOf,
  detailOf,
} from "@/test/fixture-graph";
import {
  asRendered,
  documentPositionsOf,
  focusTargetOf,
  pressKey,
  renderWithQuery,
  rowWithLabel,
  settle,
  tabStopsIn,
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

const inspected = (elementId: string): InspectedElement => {
  const detail = detailOf(elementId);
  return {
    elementId,
    kind: detail.kind === "PACKAGE" ? "PACKAGE" : "TYPE",
    packageId: detail.packageId,
  };
};

const INFERRED_WORD = /inferid/iu;

const TYPE_EDGE_ORDER: readonly DependencyType[] = [
  "IMPORTS",
  "EXTENDS",
  "IMPLEMENTS",
  "CALLS",
  "FIELD_TYPE",
  "PARAMETER_TYPE",
  "RETURN_TYPE",
  "ANNOTATION_REFERENCE",
];

const expectedVerbs = (
  entry: DependencyEntry,
  direction: DependencyDirection
): string => {
  const labels =
    direction === "OUT" ? dependencyTypePassiveLabels : dependencyTypeLabels;
  return TYPE_EDGE_ORDER.filter((type) => entry.types[type] !== undefined)
    .map((type) => labels[type])
    .join(", ");
};

beforeEach(() => {
  stubTableLayout();
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
});

const renderTable = async (
  elementId: string,
  direction: DependencyDirection,
  firstLabel: string
) => {
  const onReveal = vi.fn();
  const rendered = renderWithQuery(
    <DependencyTable
      analysisId={WORLD_ANALYSIS_ID}
      direction={direction}
      element={inspected(elementId)}
      onReveal={onReveal}
    />
  );
  const table = await screen.findByRole("table");
  await within(table).findByText(firstLabel);
  return { ...rendered, onReveal, table };
};

describe("a ordem e o bloco das externas", () => {
  test("CatalogEntry OUT: Kind, então 'Fora do repositório', então java.lang.String", async () => {
    const page = dependencyPageOf(CATALOG_ENTRY_ID, "OUT");
    const [kind, string] = page.items as [DependencyEntry, DependencyEntry];
    const { table } = await renderTable(CATALOG_ENTRY_ID, "OUT", kind.label);

    expect(kind.isExternal).toBe(false);
    expect(string.isExternal).toBe(true);
    expect(string.resolution).toBe("RESOLVED");

    const heading = within(table).getByText(
      t.inspector.dependencies.externalHeading
    );
    const positions = documentPositionsOf(table, [
      within(table).getByText(kind.label),
      heading,
      within(table).getByText(string.label),
    ]);
    expect(positions).toStrictEqual([...positions].sort((a, b) => a - b));
  });

  test("o cabeçalho das externas conta `outExternal`, e a externa diz 'resolvida'", async () => {
    const page = dependencyPageOf(CATALOG_ENTRY_ID, "OUT");
    const [kind, string] = page.items as [DependencyEntry, DependencyEntry];
    const { table } = await renderTable(CATALOG_ENTRY_ID, "OUT", kind.label);
    const count = page.totals.outExternal ?? 0;

    const headingRow = rowWithLabel(
      table,
      t.inspector.dependencies.externalHeading
    );
    expect(headingRow).toHaveTextContent(
      asRendered(
        interpolate(plural(count, t.inspector.dependencies.externalCount), {
          count,
        })
      )
    );
    const stringRow = rowWithLabel(table, string.label);
    expect(
      within(stringRow).getByText(resolutionQualityLabels.RESOLVED)
    ).toBeDefined();
    expect(stringRow).not.toHaveTextContent(
      resolutionQualityLabels.UNRESOLVED_EXTERNAL
    );
  });

  test("Catalog OUT: as linhas na ordem do fio, com os verbos na voz passiva", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const { table } = await renderTable(
      CATALOG_ID,
      "OUT",
      page.items[0]?.label ?? ""
    );

    const positions = documentPositionsOf(
      table,
      page.items.map((entry) => within(table).getByText(entry.label))
    );
    expect(positions).toStrictEqual([...positions].sort((a, b) => a - b));
    for (const entry of page.items) {
      const row = rowWithLabel(table, entry.label);
      expect(row, entry.label).toHaveTextContent(expectedVerbs(entry, "OUT"));
      expect(
        within(row).getByText(resolutionQualityLabels[entry.resolution])
      ).toBeDefined();
    }
  });

  test("Catalog IN: os verbos na voz ativa e a resolução deduzida, em texto", async () => {
    const page = dependencyPageOf(CATALOG_ID, "IN");
    const { table } = await renderTable(
      CATALOG_ID,
      "IN",
      page.items[0]?.label ?? ""
    );

    expect(page.items.every((entry) => entry.resolution === "INFERRED")).toBe(
      true
    );
    for (const entry of page.items) {
      const row = rowWithLabel(table, entry.label);
      expect(row, entry.label).toHaveTextContent(expectedVerbs(entry, "IN"));
      expect(
        within(row).getByText(resolutionQualityLabels.INFERRED)
      ).toBeDefined();
    }
    expect(table.textContent ?? "").not.toMatch(INFERRED_WORD);
    expect(
      within(table).queryByText(t.inspector.dependencies.externalHeading)
    ).toBeNull();
  });
});

describe("a linha de pacote", () => {
  test("aparece só em Store, o único tipo de outro pacote no OUT de Catalog", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const catalogPackage = detailOf(CATALOG_ID).packageId;
    const { table } = await renderTable(
      CATALOG_ID,
      "OUT",
      page.items[0]?.label ?? ""
    );
    const packageNames = DESCRIBED_IDS.filter(
      (id) => detailOf(id).kind === "PACKAGE"
    ).map((id) => detailOf(id).qualifiedName);

    for (const entry of page.items) {
      const row = rowWithLabel(table, entry.label);
      const shown = packageNames.filter(
        (name) => within(row).queryAllByText(name).length > 0
      );
      const expected =
        entry.packageId === catalogPackage
          ? []
          : [detailOf(entry.packageId ?? "").qualifiedName];

      expect(shown, entry.label).toStrictEqual(expected);
    }
    expect(
      page.items.filter((entry) => entry.packageId !== catalogPackage)
    ).toHaveLength(1);
  });

  test("o rótulo e o pacote vêm do repositório: cada um em um <bdi>", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const store = page.items.find(
      (entry) => entry.packageId !== detailOf(CATALOG_ID).packageId
    ) as DependencyEntry;
    const { table } = await renderTable(CATALOG_ID, "OUT", store.label);
    const row = rowWithLabel(table, store.label);

    expect(within(row).getByText(store.label).closest("bdi")).not.toBeNull();
    expect(
      within(row)
        .getByText(detailOf(store.packageId ?? "").qualifiedName)
        .closest("bdi")
    ).not.toBeNull();
  });
});

describe("a tabela nativa", () => {
  test("aria-rowcount é o alcance mais o cabeçalho, e cada linha tem o seu aria-rowindex", async () => {
    const page = dependencyPageOf(CATALOG_ENTRY_ID, "OUT");
    const [kind, string] = page.items as [DependencyEntry, DependencyEntry];
    const { table } = await renderTable(CATALOG_ENTRY_ID, "OUT", kind.label);
    const reachable = page.totals.out + (page.totals.outExternal ?? 0) + 1;

    expect(table).toHaveAttribute("aria-rowcount", String(reachable + 1));
    expect(rowWithLabel(table, kind.label)).toHaveAttribute(
      "aria-rowindex",
      "2"
    );
    expect(
      rowWithLabel(table, t.inspector.dependencies.externalHeading)
    ).toHaveAttribute("aria-rowindex", String(page.totals.out + 2));
    expect(rowWithLabel(table, string.label)).toHaveAttribute(
      "aria-rowindex",
      "4"
    );
  });

  test("o nome é um cabeçalho de linha, e o alvo de foco é o botão dentro dele", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const { table } = await renderTable(
      CATALOG_ID,
      "OUT",
      page.items[0]?.label ?? ""
    );

    for (const entry of page.items) {
      const row = rowWithLabel(table, entry.label);
      const header = within(row).getByRole("rowheader");
      expect(within(header).getByRole("button")).toBe(focusTargetOf(row));
    }
  });
});

describe("o teclado", () => {
  test("um só ponto de tabulação: a primeira linha", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const first = page.items[0] as DependencyEntry;
    const { table } = await renderTable(CATALOG_ID, "OUT", first.label);

    expect(tabStopsIn(table)).toStrictEqual([
      focusTargetOf(rowWithLabel(table, first.label)),
    ]);
  });

  test("as setas percorrem as linhas e o ponto de tabulação vai junto", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const [store, audited, entry] = page.items as [
      DependencyEntry,
      DependencyEntry,
      DependencyEntry,
    ];
    const { table } = await renderTable(CATALOG_ID, "OUT", store.label);
    const target = (row: DependencyEntry) =>
      focusTargetOf(rowWithLabel(table, row.label));
    act(() => {
      target(store).focus();
    });

    pressKey(target(store), "ArrowDown");
    expect(document.activeElement).toBe(target(audited));
    pressKey(target(audited), "ArrowDown");
    expect(document.activeElement).toBe(target(entry));
    pressKey(target(entry), "ArrowUp");
    expect(document.activeElement).toBe(target(audited));
    expect(tabStopsIn(table)).toStrictEqual([target(audited)]);
  });

  test("Home e End vão à primeira e à última linha carregada", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const first = page.items[0] as DependencyEntry;
    const last = page.items.at(-1) as DependencyEntry;
    const { table } = await renderTable(CATALOG_ID, "OUT", first.label);
    const target = (row: DependencyEntry) =>
      focusTargetOf(rowWithLabel(table, row.label));
    act(() => {
      target(first).focus();
    });

    pressKey(target(first), "End");
    expect(document.activeElement).toBe(target(last));
    pressKey(target(last), "Home");
    expect(document.activeElement).toBe(target(first));
  });

  test("as setas pulam o cabeçalho das externas e chegam à linha externa", async () => {
    const page = dependencyPageOf(CATALOG_ENTRY_ID, "OUT");
    const [kind, string] = page.items as [DependencyEntry, DependencyEntry];
    const { table } = await renderTable(CATALOG_ENTRY_ID, "OUT", kind.label);
    const kindTarget = focusTargetOf(rowWithLabel(table, kind.label));
    const stringTarget = focusTargetOf(rowWithLabel(table, string.label));
    act(() => {
      kindTarget.focus();
    });

    pressKey(kindTarget, "ArrowDown");
    expect(document.activeElement).toBe(stringTarget);
    pressKey(stringTarget, "ArrowUp");
    expect(document.activeElement).toBe(kindTarget);
  });

  test("Enter e clique em uma interna revelam a outra ponta: id, tipo e pacote", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const [store, audited] = page.items as [DependencyEntry, DependencyEntry];
    const { onReveal, table } = await renderTable(
      CATALOG_ID,
      "OUT",
      store.label
    );
    const storeTarget = focusTargetOf(rowWithLabel(table, store.label));
    act(() => {
      storeTarget.focus();
    });

    pressKey(storeTarget, "Enter");
    expect(onReveal).toHaveBeenCalledTimes(1);
    expect(onReveal).toHaveBeenLastCalledWith(
      expect.objectContaining({
        elementId: store.elementId,
        kind: store.kind,
        packageId: store.packageId,
      })
    );

    focusTargetOf(rowWithLabel(table, audited.label)).click();
    expect(onReveal).toHaveBeenCalledTimes(2);
    expect(onReveal).toHaveBeenLastCalledWith(
      expect.objectContaining({
        elementId: audited.elementId,
        kind: audited.kind,
        packageId: audited.packageId,
      })
    );
  });

  test("a linha externa recebe foco, é aria-disabled, e nem Enter nem clique a abrem", async () => {
    const page = dependencyPageOf(CATALOG_ENTRY_ID, "OUT");
    const [kind, string] = page.items as [DependencyEntry, DependencyEntry];
    const { onReveal, table } = await renderTable(
      CATALOG_ENTRY_ID,
      "OUT",
      kind.label
    );
    const stringTarget = focusTargetOf(rowWithLabel(table, string.label));

    act(() => {
      stringTarget.focus();
    });
    expect(document.activeElement).toBe(stringTarget);
    expect(stringTarget).toHaveAttribute("aria-disabled", "true");
    expect(stringTarget).toHaveAccessibleDescription();

    pressKey(stringTarget, "Enter");
    stringTarget.click();
    expect(onReveal).not.toHaveBeenCalled();
  });
});

describe("o destaque cruzado", () => {
  test("o foco em uma linha escreve o id da outra ponta, e o blur o solta", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const store = page.items[0] as DependencyEntry;
    const { table } = await renderTable(CATALOG_ID, "OUT", store.label);
    const target = focusTargetOf(rowWithLabel(table, store.label));

    act(() => {
      target.focus();
    });
    expect(useAppStore.getState().activeElementId).toBe(store.elementId);

    act(() => {
      target.blur();
    });
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("o ponteiro sobre uma linha escreve, e ao sair solta", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const audited = page.items[1] as DependencyEntry;
    const { table } = await renderTable(CATALOG_ID, "OUT", audited.label);
    const target = focusTargetOf(rowWithLabel(table, audited.label));

    fireEvent.mouseOver(target);
    expect(useAppStore.getState().activeElementId).toBe(audited.elementId);

    fireEvent.mouseOut(target);
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("o blur não apaga o destaque que outro escritor pôs depois", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const [store, audited] = page.items as [DependencyEntry, DependencyEntry];
    const { table } = await renderTable(CATALOG_ID, "OUT", store.label);
    const target = focusTargetOf(rowWithLabel(table, store.label));
    act(() => {
      target.focus();
    });

    act(() => {
      useAppStore.getState().setActiveElementId(audited.elementId);
    });
    act(() => {
      target.blur();
    });

    expect(useAppStore.getState().activeElementId).toBe(audited.elementId);
  });

  test("a linha externa não é nó do mapa: chegar a ela solta o destaque da anterior", async () => {
    const page = dependencyPageOf(CATALOG_ENTRY_ID, "OUT");
    const [kind, string] = page.items as [DependencyEntry, DependencyEntry];
    const { table } = await renderTable(CATALOG_ENTRY_ID, "OUT", kind.label);
    const kindTarget = focusTargetOf(rowWithLabel(table, kind.label));
    act(() => {
      kindTarget.focus();
    });
    expect(useAppStore.getState().activeElementId).toBe(kind.elementId);

    pressKey(kindTarget, "ArrowDown");

    expect(document.activeElement).toBe(
      focusTargetOf(rowWithLabel(table, string.label))
    );
    expect(useAppStore.getState().activeElementId).toBeNull();
  });

  test("desmontar a tabela solta o destaque que ela escreveu", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const store = page.items[0] as DependencyEntry;
    const { table, unmount } = await renderTable(
      CATALOG_ID,
      "OUT",
      store.label
    );
    act(() => {
      focusTargetOf(rowWithLabel(table, store.label)).focus();
    });
    expect(useAppStore.getState().activeElementId).toBe(store.elementId);

    unmount();

    expect(useAppStore.getState().activeElementId).toBeNull();
  });
});

describe("uma linha ambígua (escrita à mão: o mundo não tem nenhuma)", () => {
  test("diz 'externa, não resolvida' e, à parte, que é ambígua", async () => {
    const config = externalTypeRow(1, {
      ambiguous: true,
      label: "Config",
      qualifiedName: "Config",
    });
    const totals = { in: 0, out: 0, outExternal: 1 };
    const request = vi
      .spyOn(transport, "request")
      .mockImplementation((method: string) =>
        method === "element.dependencies"
          ? Promise.resolve(pageOf([config], totals))
          : Promise.reject(new Error(`pedido inesperado: ${method}`))
      );
    renderWithQuery(
      <DependencyTable
        analysisId={WORLD_ANALYSIS_ID}
        direction="OUT"
        element={inspected(CATALOG_ID)}
        onReveal={vi.fn()}
      />
    );
    const table = await screen.findByRole("table");
    await within(table).findByText(config.label);

    const row = rowWithLabel(table, config.label);
    const quality = within(row).getByText(
      resolutionQualityLabels.UNRESOLVED_EXTERNAL
    );
    const ambiguous = within(row).getByText(t.inspector.dependencies.ambiguous);
    expect(quality).not.toBe(ambiguous);
    expect(quality.contains(ambiguous)).toBe(false);
    expect(ambiguous.contains(quality)).toBe(false);
    await settle();
    expect(
      request.mock.calls.filter(([method]) => method === "element.dependencies")
    ).toHaveLength(1);
  });
});

describe("o que a tabela nunca mostra", () => {
  test("nenhum peso: o número de uma linha não aparece nela", async () => {
    const page = dependencyPageOf(CATALOG_ID, "OUT");
    const store = page.items[0] as DependencyEntry;
    const { table } = await renderTable(CATALOG_ID, "OUT", store.label);

    expect(store.weight).toBe(7);
    expect(textTokensOf(rowWithLabel(table, store.label))).not.toContain(
      String(store.weight)
    );
  });

  test("a direção ausente do mundo nunca vira pedido: só a direção dada", async () => {
    const request = vi.spyOn(transport, "request");
    const page = dependencyPageOf(CATALOG_ID, "IN");
    await renderTable(CATALOG_ID, "IN", page.items[0]?.label ?? "");
    await settle();

    const directions = request.mock.calls
      .filter(([method]) => method === "element.dependencies")
      .map(([, payload]) => (payload as { direction: string }).direction);
    expect(directions).toStrictEqual(["IN"]);
  });
});
