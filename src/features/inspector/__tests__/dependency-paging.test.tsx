import { screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { FakeBridgeError } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import {
  DependencyTable,
  type InspectedElement,
} from "@/features/inspector/dependency-table";
import { t } from "@/i18n/messages";
import type { DependencyEntry, DependencyPage } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { internalTypeRow, NEXT_CURSOR, pageOf } from "@/test/dependency-rows";
import {
  DESCRIBED_IDS,
  detailOf,
  ELEMENT_REFUSALS,
} from "@/test/fixture-graph";
import {
  pressKey,
  renderWithQuery,
  settle,
  WORLD_ANALYSIS_ID,
} from "@/test/inspector";
import { stubTableLayout } from "@/test/virtual";

const CATALOG_ID =
  DESCRIBED_IDS.find((id) => detailOf(id).label === "Catalog") ?? "";
const ELEMENT: InspectedElement = {
  elementId: CATALOG_ID,
  kind: "TYPE",
  packageId: detailOf(CATALOG_ID).packageId,
};

const ROW_HEIGHT = 40;
const TALL_VIEWPORT = { height: 1000, width: 280 };
const SHORT_VIEWPORT = { height: 200, width: 280 };

const PAGE_ONE_ROWS = 10;
const PAGE_TWO_ROWS = 5;

const rows = (from: number, count: number): DependencyEntry[] =>
  Array.from({ length: count }, (_, offset) =>
    internalTypeRow(from + offset, { weight: 1000 - from - offset })
  );

const totalsOf = (total: number): DependencyPage["totals"] => ({
  in: total,
  out: 0,
  outExternal: 0,
});

const pageTwoRefusal = () => {
  const refusal = ELEMENT_REFUSALS.find(
    ({ method, request }) =>
      method === "element.dependencies" && request.cursor !== undefined
  );
  if (refusal === undefined) {
    throw new Error("o mundo não tem uma página de dependências recusada");
  }
  return new FakeBridgeError(refusal.error);
};

type Answer = () => Promise<DependencyPage>;

const mockPaging = (first: DependencyPage, next: readonly Answer[]) => {
  const queue = [...next];
  return vi
    .spyOn(transport, "request")
    .mockImplementation((method: string, payload: unknown) => {
      if (method !== "element.dependencies") {
        return Promise.reject(new Error(`pedido inesperado: ${method}`));
      }
      const { cursor } = payload as { readonly cursor?: string };
      if (cursor === undefined) {
        return Promise.resolve(first);
      }
      const answer = queue.shift();
      return answer === undefined
        ? Promise.reject(new Error("não sobrou página"))
        : answer();
    });
};

const cursorsOf = (request: ReturnType<typeof mockPaging>) =>
  request.mock.calls
    .filter(([method]) => method === "element.dependencies")
    .map(([, payload]) => (payload as { cursor?: string }).cursor);

const SYNTHETIC_LABEL = /^T\d+$/u;

const shownLabels = (table: HTMLElement): string[] =>
  within(table)
    .queryAllByText(SYNTHETIC_LABEL)
    .map((element) => element.textContent ?? "");

const renderTable = () =>
  renderWithQuery(
    <DependencyTable
      analysisId={WORLD_ANALYSIS_ID}
      direction="IN"
      element={ELEMENT}
      onReveal={vi.fn()}
    />
  );

beforeEach(() => {
  useAppStore.setState({ activeElementId: null });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("a próxima página pelo intervalo do virtualizador", () => {
  test("10 linhas e um cursor, depois 5 e nenhum: 15 linhas, 2 pedidos", async () => {
    stubTableLayout({ rowHeight: ROW_HEIGHT, viewport: TALL_VIEWPORT });
    const total = PAGE_ONE_ROWS + PAGE_TWO_ROWS;
    const request = mockPaging(
      pageOf(rows(0, PAGE_ONE_ROWS), totalsOf(total), NEXT_CURSOR),
      [
        () =>
          Promise.resolve(
            pageOf(rows(PAGE_ONE_ROWS, PAGE_TWO_ROWS), totalsOf(total))
          ),
      ]
    );
    renderTable();
    const table = await screen.findByRole("table");

    await waitFor(() => {
      expect(shownLabels(table)).toHaveLength(total);
    });
    await settle();

    expect(shownLabels(table)).toStrictEqual(
      rows(0, total).map((entry) => entry.label)
    );
    expect(cursorsOf(request)).toStrictEqual([undefined, NEXT_CURSOR]);
  });

  test("o aria-rowcount é o total desde a página 1, e a cauda diz que carrega", async () => {
    stubTableLayout({ rowHeight: ROW_HEIGHT, viewport: TALL_VIEWPORT });
    const total = PAGE_ONE_ROWS + PAGE_TWO_ROWS;
    let release: (page: DependencyPage) => void = () => undefined;
    const pageTwo = new Promise<DependencyPage>((resolve) => {
      release = resolve;
    });
    mockPaging(pageOf(rows(0, PAGE_ONE_ROWS), totalsOf(total), NEXT_CURSOR), [
      () => pageTwo,
    ]);
    renderTable();
    const table = await screen.findByRole("table");

    await within(table).findByText(t.inspector.dependencies.loadingMore);
    expect(table).toHaveAttribute("aria-rowcount", String(total + 1));
    expect(shownLabels(table)).toHaveLength(PAGE_ONE_ROWS);

    release(pageOf(rows(PAGE_ONE_ROWS, PAGE_TWO_ROWS), totalsOf(total)));
    await waitFor(() => {
      expect(shownLabels(table)).toHaveLength(total);
    });
    expect(
      within(table).queryByText(t.inspector.dependencies.loadingMore)
    ).toBeNull();
  });

  test("longe do fim carregado, nenhuma página a mais: nunca pelo tamanho da lista", async () => {
    stubTableLayout({ rowHeight: ROW_HEIGHT, viewport: SHORT_VIEWPORT });
    const loaded = 30;
    const request = mockPaging(
      pageOf(rows(0, loaded), totalsOf(loaded * 2), NEXT_CURSOR),
      [
        () =>
          Promise.resolve(pageOf(rows(loaded, loaded), totalsOf(loaded * 2))),
      ]
    );
    renderTable();
    const table = await screen.findByRole("table");
    await within(table).findByText("T0");

    await settle();

    expect(cursorsOf(request)).toStrictEqual([undefined]);
  });
});

describe("as páginas que dão errado", () => {
  test("uma página vazia com cursor: o erro de contrato aparece, e só 2 pedidos", async () => {
    stubTableLayout({ rowHeight: ROW_HEIGHT, viewport: TALL_VIEWPORT });
    const total = PAGE_ONE_ROWS + PAGE_TWO_ROWS;
    const request = mockPaging(
      pageOf(rows(0, PAGE_ONE_ROWS), totalsOf(total), NEXT_CURSOR),
      [
        () => Promise.resolve(pageOf([], totalsOf(total), NEXT_CURSOR)),
        () => Promise.resolve(pageOf([], totalsOf(total), NEXT_CURSOR)),
      ]
    );
    renderTable();

    const failures = await screen.findAllByText(
      (_, element) =>
        element?.textContent === t.inspector.dependencies.failed ||
        element?.textContent === t.inspector.dependencies.failedMore
    );
    expect(failures.length).toBeGreaterThan(0);
    await settle();

    expect(cursorsOf(request)).toStrictEqual([undefined, NEXT_CURSOR]);
  });

  test("a página 2 recusada: uma linha final focável, que pede a página 2 de novo", async () => {
    stubTableLayout({ rowHeight: ROW_HEIGHT, viewport: TALL_VIEWPORT });
    const total = PAGE_ONE_ROWS + PAGE_TWO_ROWS;
    const request = mockPaging(
      pageOf(rows(0, PAGE_ONE_ROWS), totalsOf(total), NEXT_CURSOR),
      [
        () => Promise.reject(pageTwoRefusal()),
        () =>
          Promise.resolve(
            pageOf(rows(PAGE_ONE_ROWS, PAGE_TWO_ROWS), totalsOf(total))
          ),
      ]
    );
    renderTable();
    const table = await screen.findByRole("table");

    const message = await within(table).findByText(
      t.inspector.dependencies.failedMore
    );
    await settle();
    expect(cursorsOf(request)).toStrictEqual([undefined, NEXT_CURSOR]);
    expect(shownLabels(table)).toHaveLength(PAGE_ONE_ROWS);

    const tail = message.closest("tr");
    expect(tail).not.toBeNull();
    const retry = within(tail as HTMLElement).getByRole("button");
    retry.focus();
    expect(document.activeElement).toBe(retry);
    pressKey(retry, "Enter");

    await waitFor(() => {
      expect(shownLabels(table)).toHaveLength(total);
    });
    await settle();
    expect(cursorsOf(request)).toStrictEqual([
      undefined,
      NEXT_CURSOR,
      NEXT_CURSOR,
    ]);
    expect(
      within(table).queryByText(t.inspector.dependencies.failedMore)
    ).toBeNull();
  });
});
