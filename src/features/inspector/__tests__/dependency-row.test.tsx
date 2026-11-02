import { act, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { transport } from "@/bridge/transport";
import { DependencyTable } from "@/features/inspector/dependency-table";
import { interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import { clampText, DEFAULT_UNTRUSTED_MAX_CHARS } from "@/lib/untrusted";
import type { DependencyEntry } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { internalTypeRow, pageOf } from "@/test/dependency-rows";
import {
  DESCRIBED_IDS,
  dependencyPageOf,
  detailOf,
} from "@/test/fixture-graph";
import {
  focusTargetOf,
  renderWithQuery,
  rowWithLabel,
  WORLD_ANALYSIS_ID,
} from "@/test/inspector";
import { stubTableLayout } from "@/test/virtual";
import { CATALOG_ELEMENT } from "./paging-transport";

const RTL_OVERRIDE = String.fromCodePoint(0x20_2e);

const HOSTILE_SEGMENT = "a".repeat(70);
const HOSTILE_PACKAGE = `br.${HOSTILE_SEGMENT}${RTL_OVERRIDE}.${HOSTILE_SEGMENT}.x`;

const SHOWN_PACKAGE = clampText(
  HOSTILE_PACKAGE.replace(RTL_OVERRIDE, ""),
  DEFAULT_UNTRUSTED_MAX_CHARS
);

const hostileRow = (): DependencyEntry =>
  internalTypeRow(0, {
    elementId: `type:${HOSTILE_PACKAGE}.Probe`,
    label: "Probe",
    packageId: `pkg:${HOSTILE_PACKAGE}`,
    qualifiedName: `${HOSTILE_PACKAGE}.Probe`,
  });

const typeIdOf = (label: string): string =>
  DESCRIBED_IDS.find(
    (id) => id.startsWith("type:") && detailOf(id).label === label
  ) ?? "";

beforeEach(() => {
  stubTableLayout();
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("o pacote de uma linha, para o leitor de tela", () => {
  test("é a frase do molde em volta do mesmo nome saneado e cortado da linha visível", async () => {
    vi.spyOn(transport, "request").mockResolvedValue(
      pageOf([hostileRow()], { in: 0, out: 1, outExternal: 0 })
    );
    renderWithQuery(
      <DependencyTable
        analysisId={WORLD_ANALYSIS_ID}
        direction="OUT"
        element={CATALOG_ELEMENT}
        onReveal={vi.fn()}
      />
    );
    const table = await screen.findByRole("table");
    await within(table).findByText("Probe");
    const row = rowWithLabel(table, "Probe");
    const control = focusTargetOf(row);

    const shown = within(row).getByText(SHOWN_PACKAGE);
    const bdi = shown.closest("bdi");
    expect(bdi).not.toBeNull();
    expect(bdi).toHaveAttribute("lang", "");
    expect(bdi).toHaveAttribute("translate", "no");
    expect(bdi?.closest("[aria-hidden='true']")).toBeNull();

    const phrase = interpolate(t.inspector.dependencies.packageOf, {
      name: SHOWN_PACKAGE,
    });
    expect(control.textContent).toContain(phrase);
    expect(control.textContent).not.toContain(RTL_OVERRIDE);
    expect(within(row).getAllByText(SHOWN_PACKAGE)).toHaveLength(1);
  });
});

describe("as frases que só descrevem", () => {
  test("ficam fora da ordem de leitura e ainda descrevem a tabela e a linha externa", async () => {
    const catalogEntry = typeIdOf("CatalogEntry");
    const page = dependencyPageOf(catalogEntry, "OUT");
    const [first, external] = page.items as [DependencyEntry, DependencyEntry];
    renderWithQuery(
      <DependencyTable
        analysisId={WORLD_ANALYSIS_ID}
        direction="OUT"
        element={{
          elementId: catalogEntry,
          kind: "TYPE",
          packageId: detailOf(catalogEntry).packageId,
        }}
        onReveal={vi.fn()}
      />
    );
    const table = await screen.findByRole("table");
    await within(table).findByText(first.label);

    const hint = screen.getByText(t.inspector.dependencies.keyboardHint);
    const externalDescription = screen.getByText(
      t.inspector.dependencies.externalDescription
    );
    expect(hint).toHaveAttribute("hidden");
    expect(externalDescription).toHaveAttribute("hidden");

    expect(table).toHaveAccessibleDescription(
      t.inspector.dependencies.keyboardHint
    );
    const externalControl = focusTargetOf(rowWithLabel(table, external.label));
    act(() => {
      externalControl.focus();
    });
    expect(externalControl).toHaveAccessibleDescription(
      t.inspector.dependencies.externalDescription
    );
  });
});
