import { QueryClientProvider } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { ElementInspector } from "@/features/inspector/element-inspector";
import { t } from "@/i18n/messages";
import type { DependencyEntry } from "@/protocol/graph";
import type { GraphViewParams } from "@/queries/options";
import { useAppStore } from "@/state/app-store";
import {
  DESCRIBED_IDS,
  dependencyPageOf,
  detailOf,
} from "@/test/fixture-graph";
import {
  focusTargetOf,
  pressKey,
  renderInspector,
  rowWithLabel,
  settle,
  WORLD_ANALYSIS_ID,
} from "@/test/inspector";
import { stubTableLayout } from "@/test/virtual";

const CATALOG_ID = (() => {
  const id = DESCRIBED_IDS.find(
    (candidate) =>
      candidate.startsWith("type:") && detailOf(candidate).label === "Catalog"
  );
  if (id === undefined) {
    throw new Error("nenhum tipo descrito tem o rótulo Catalog");
  }
  return id;
})();
const CATALOG_PACKAGE = detailOf(CATALOG_ID).packageId;

const targetOf = (samePackage: boolean): DependencyEntry => {
  const entry = dependencyPageOf(CATALOG_ID, "OUT").items.find(
    (candidate) =>
      candidate.kind === "TYPE" &&
      candidate.packageId !== null &&
      DESCRIBED_IDS.includes(candidate.elementId) &&
      (candidate.packageId === CATALOG_PACKAGE) === samePackage
  );
  if (entry === undefined) {
    throw new Error(
      `Catalog não tem dependência descrita, same=${samePackage}`
    );
  }
  return entry;
};

const viewOfPackage = (packageId: string | null): GraphViewParams => ({
  level: "TYPE",
  parentId: packageId ?? "",
});

const outside = () => {
  const nav = document.createElement("nav");
  nav.setAttribute("aria-label", t.shell.breadcrumb.label);
  const crumb = document.createElement("span");
  crumb.tabIndex = -1;
  crumb.textContent = "core";
  nav.append(crumb);
  const search = document.createElement("input");
  search.setAttribute("aria-label", "busca");
  document.body.append(nav, search);
  return { crumb, search };
};

beforeEach(() => {
  stubTableLayout();
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
  for (const element of document.body.querySelectorAll(
    ":scope > nav, :scope > input"
  )) {
    element.remove();
  }
});

const revealWhileFocusMoves = async (
  target: DependencyEntry,
  moveFocus: () => void
) => {
  const { callbacks, queryClient, rerender } = renderInspector({
    direction: "OUT",
    focus: CATALOG_ID,
    tab: "dependencies",
    view: viewOfPackage(CATALOG_PACKAGE),
  });
  const table = await screen.findByRole("table");
  await within(table).findByText(target.label);
  const row = focusTargetOf(rowWithLabel(table, target.label));
  act(() => {
    row.focus();
  });
  pressKey(row, "Enter");
  if (callbacks.onReveal.mock.calls.length !== 1) {
    throw new Error("Enter na linha não pediu para revelar");
  }

  act(moveFocus);

  const navigate = () =>
    rerender(
      <QueryClientProvider client={queryClient}>
        <ElementInspector
          analysisId={WORLD_ANALYSIS_ID}
          direction="OUT"
          focus={target.elementId}
          tab="dependencies"
          view={viewOfPackage(target.packageId)}
          {...callbacks}
        />
      </QueryClientProvider>
    );
  navigate();
  const heading = await screen.findByRole("heading", { name: target.label });
  return { heading, navigate };
};

describe("revelar noutro pacote: o título novo só leva o foco que não tem dono", () => {
  test("do <body>, o foco chega ao título novo", async () => {
    const { heading } = await revealWhileFocusMoves(targetOf(false), () => {
      (document.activeElement as HTMLElement).blur();
    });

    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
  });

  test("da trilha, para onde a troca de vista o leva, o foco chega ao título novo", async () => {
    const { crumb } = outside();
    const { heading } = await revealWhileFocusMoves(targetOf(false), () => {
      crumb.focus();
    });

    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
  });

  test("da busca, onde a pessoa foi no meio do caminho, o foco fica lá, e a passagem desarma", async () => {
    const { search } = outside();
    const { heading, navigate } = await revealWhileFocusMoves(
      targetOf(false),
      () => {
        search.focus();
      }
    );

    await settle();
    expect(document.activeElement).toBe(search);

    act(() => {
      search.blur();
    });
    navigate();
    await settle();
    expect(document.activeElement).toBe(document.body);
    expect(document.activeElement).not.toBe(heading);
  });
});

describe("revelar no mesmo pacote: o foco dentro do inspetor ainda é da revelação", () => {
  test("da aba 'Dependências', que continua montada, o foco chega ao título novo", async () => {
    const { heading } = await revealWhileFocusMoves(targetOf(true), () => {
      screen.getByRole("tab", { name: t.inspector.tabs.dependencies }).focus();
    });

    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
  });

  test("da busca, o foco fica lá", async () => {
    const { search } = outside();
    await revealWhileFocusMoves(targetOf(true), () => {
      search.focus();
    });

    await settle();
    expect(document.activeElement).toBe(search);
  });
});
