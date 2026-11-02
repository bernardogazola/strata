import { type QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, within } from "@testing-library/react";
import type { ReactElement } from "react";
import { vi } from "vitest";
import {
  ElementInspector,
  type ElementInspectorProps,
} from "@/features/inspector/element-inspector";
import { createQueryClient } from "@/queries/client";

export const WORLD_ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

export const renderWithQuery = (
  ui: ReactElement,
  queryClient: QueryClient = createQueryClient()
) => ({
  ...render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
  ),
  queryClient,
});

export const inspectorCallbacks = () => ({
  onExpand: vi.fn<(packageId: string) => void>(),
  onReveal: vi.fn<ElementInspectorProps["onReveal"]>(),
  onSelectDirection: vi.fn<ElementInspectorProps["onSelectDirection"]>(),
  onSelectTab: vi.fn<ElementInspectorProps["onSelectTab"]>(),
});

export type InspectorCallbacks = ReturnType<typeof inspectorCallbacks>;

export const renderInspector = (
  props: Partial<ElementInspectorProps> &
    Pick<ElementInspectorProps, "focus" | "view">
) => {
  const callbacks = inspectorCallbacks();
  const rendered = renderWithQuery(
    <ElementInspector
      analysisId={WORLD_ANALYSIS_ID}
      direction="OUT"
      tab="overview"
      {...callbacks}
      {...props}
    />
  );
  return { ...rendered, callbacks };
};

export const pressKey = (target: Element, key: string): void => {
  const proceeded = fireEvent.keyDown(target, { key });
  fireEvent.keyUp(target, { key });
  if (proceeded && key === "Enter" && target instanceof HTMLButtonElement) {
    fireEvent.click(target);
  }
};

const WHITESPACE_RUN = /\s+/gu;

export const asRendered = (text: string): string =>
  text.replace(WHITESPACE_RUN, " ").trim();

const SETTLE_MS = 50;
export const settle = () =>
  act(
    () =>
      new Promise<void>((resolve) => {
        setTimeout(resolve, SETTLE_MS);
      })
  );

export const rowWithLabel = (
  table: HTMLElement,
  label: string
): HTMLElement => {
  const matches = within(table)
    .getAllByRole("row")
    .filter((row) => within(row).queryAllByText(label).length > 0);
  if (matches.length !== 1) {
    throw new Error(
      `${matches.length} linhas mostram ${JSON.stringify(label)}`
    );
  }
  return matches[0] as HTMLElement;
};

export const focusTargetOf = (row: HTMLElement): HTMLElement =>
  within(row).getByRole("button");

const FOCUSABLE =
  "a[href], button, input, select, textarea, [tabindex]:not([tabindex='-1'])";

export const tabStopsIn = (container: HTMLElement): HTMLElement[] =>
  [...container.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(
    (element) =>
      element.tabIndex >= 0 && !(element as HTMLButtonElement).disabled
  );

export const documentPositionsOf = (
  container: HTMLElement,
  elements: readonly Element[]
): number[] => {
  const all = [...container.querySelectorAll("*")];
  return elements.map((element) => all.indexOf(element));
};

export const textTokensOf = (
  root: HTMLElement,
  skip: readonly string[] = []
): string[] => {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const tokens: string[] = [];
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    const text = node.textContent ?? "";
    if (!skip.includes(text)) {
      tokens.push(...asRendered(text).split(" "));
    }
  }
  return tokens.filter((token) => token !== "");
};
