import { act, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { transport } from "@/bridge/transport";
import { t } from "@/i18n/messages";
import type { ElementDetail } from "@/protocol/graph";
import { DESCRIBED_IDS, detailOf } from "@/test/fixture-graph";
import { renderInspector, settle } from "@/test/inspector";
import { stubTableLayout } from "@/test/virtual";

const CATALOG_ID =
  DESCRIBED_IDS.find((id) => detailOf(id).label === "Catalog") ?? "";
const CATALOG = detailOf(CATALOG_ID);

beforeEach(() => {
  stubTableLayout();
});

afterEach(() => {
  vi.restoreAllMocks();
});

test("a região de status do describe já está montada, e só o texto dela muda", async () => {
  let release: (detail: ElementDetail) => void = () => undefined;
  const pending = new Promise<ElementDetail>((resolve) => {
    release = resolve;
  });
  const request = transport.request.bind(transport);
  vi.spyOn(transport, "request").mockImplementation((method, payload) =>
    method === "element.describe" ? pending : request(method, payload)
  );
  if (CATALOG.packageId === null) {
    throw new Error("Catalog sem pacote: o mundo de fixtures mudou");
  }
  renderInspector({
    focus: CATALOG_ID,
    view: { level: "TYPE", parentId: CATALOG.packageId },
  });

  const status = await screen.findByRole("status");
  await waitFor(() => {
    expect(status).toHaveTextContent(t.inspector.loading);
  });
  expect(screen.getAllByRole("status")).toHaveLength(1);

  act(() => {
    release(CATALOG);
  });
  await screen.findByRole("heading", { name: CATALOG.label });
  await settle();

  expect(screen.getByRole("status")).toBe(status);
  expect(status.textContent).toBe("");
});
