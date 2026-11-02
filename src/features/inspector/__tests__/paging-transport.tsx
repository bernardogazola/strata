import { screen, within } from "@testing-library/react";
import { vi } from "vitest";
import { FakeBridgeError } from "@/bridge/fake/error";
import { transport } from "@/bridge/transport";
import {
  DependencyTable,
  type InspectedElement,
} from "@/features/inspector/dependency-table";
import type { DependencyDirection } from "@/protocol/enums";
import type { DependencyEntry, DependencyPage } from "@/protocol/graph";
import { internalTypeRow } from "@/test/dependency-rows";
import {
  DESCRIBED_IDS,
  detailOf,
  ELEMENT_REFUSALS,
} from "@/test/fixture-graph";
import { renderWithQuery, WORLD_ANALYSIS_ID } from "@/test/inspector";

export type Answer = () => Promise<DependencyPage>;

const later = (work: () => Promise<DependencyPage>): Promise<DependencyPage> =>
  new Promise((resolve, reject) => {
    setTimeout(() => {
      work().then(resolve, reject);
    }, 0);
  });

export const answerPage =
  (page: DependencyPage): Answer =>
  () =>
    later(() => Promise.resolve(page));

export const answerError =
  (error: Error): Answer =>
  () =>
    later(() => Promise.reject(error));

export const heldAnswer = () => {
  let release: (page: DependencyPage) => void = () => undefined;
  const pending = new Promise<DependencyPage>((resolve) => {
    release = resolve;
  });
  return { answer: (() => later(() => pending)) satisfies Answer, release };
};

export const mockPaging = (first: Answer, next: readonly Answer[]) => {
  const queue = [...next];
  return vi
    .spyOn(transport, "request")
    .mockImplementation((method: string, payload: unknown) => {
      if (method !== "element.dependencies") {
        return Promise.reject(new Error(`pedido inesperado: ${method}`));
      }
      const { cursor } = payload as { readonly cursor?: string };
      if (cursor === undefined) {
        return first();
      }
      const answer = queue.shift();
      return answer === undefined
        ? Promise.reject(new Error("não sobrou página"))
        : answer();
    });
};

export const cursorsOf = (request: ReturnType<typeof mockPaging>) =>
  request.mock.calls
    .filter(([method]) => method === "element.dependencies")
    .map(([, payload]) => (payload as { cursor?: string }).cursor);

export const syntheticRows = (from: number, count: number): DependencyEntry[] =>
  Array.from({ length: count }, (_, offset) =>
    internalTypeRow(from + offset, { weight: 10_000 - from - offset })
  );

export const inTotals = (total: number): DependencyPage["totals"] => ({
  in: total,
  out: 0,
  outExternal: 0,
});

export const timeoutError = (): FakeBridgeError =>
  new FakeBridgeError({
    code: "PROC_REQUEST_TIMEOUT",
    origin: "shell",
    params: { method: "element.dependencies", timeoutMs: 30_000 },
  });

export const foreignCursorRefusal = (): FakeBridgeError => {
  const refusal = ELEMENT_REFUSALS.find(
    ({ method, request }) =>
      method === "element.dependencies" && request.cursor !== undefined
  );
  if (refusal === undefined) {
    throw new Error("o mundo não tem uma página de dependências recusada");
  }
  return new FakeBridgeError(refusal.error);
};

const CATALOG_ID =
  DESCRIBED_IDS.find((id) => detailOf(id).label === "Catalog") ?? "";
export const CATALOG_ELEMENT: InspectedElement = {
  elementId: CATALOG_ID,
  kind: "TYPE",
  packageId: detailOf(CATALOG_ID).packageId,
};

export const renderPagedTable = (direction: DependencyDirection = "IN") =>
  renderWithQuery(
    <DependencyTable
      analysisId={WORLD_ANALYSIS_ID}
      direction={direction}
      element={CATALOG_ELEMENT}
      onReveal={vi.fn()}
    />
  );

const SYNTHETIC_LABEL = /^T\d+$/u;

export const shownLabels = (table: HTMLElement): string[] =>
  within(table)
    .queryAllByText(SYNTHETIC_LABEL)
    .map((element) => element.textContent ?? "");

export const findTable = () => screen.findByRole("table");
