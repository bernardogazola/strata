import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { FakeBridgeError, fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import { ArchitectureOutline } from "@/features/outline/architecture-outline";
import { formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";
import type { ProtocolError } from "@/protocol/errors";
import { createQueryClient } from "@/queries/client";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const API = "pkg:br.pucpr.legacy.api";
const CORE = "pkg:br.pucpr.legacy.core";
const CORE_GEN = "pkg:br.pucpr.legacy.core.gen";
const CORE_SPI = "pkg:br.pucpr.legacy.core.spi";

const ELEMENT_NOT_FOUND_BODY = "Este elemento não faz parte desta análise.";

const ELEMENT_NOT_FOUND: ProtocolError = {
  code: "ELEMENT_NOT_FOUND",
  detail: "o elemento não é um elemento interno desta análise",
  origin: "analyzer",
  params: {},
};

const VIEWPORT = { height: 400, width: 320 } as const;

beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(
    VIEWPORT.height
  );
  vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(
    VIEWPORT.width
  );
  vi.spyOn(transport, "request").mockImplementation((method, payload) => {
    const { parentId } = payload as { readonly parentId?: unknown };
    return method === "outline.children" && parentId === CORE
      ? Promise.reject(new FakeBridgeError(ELEMENT_NOT_FOUND))
      : fakeRequest(method, payload);
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

const renderOpenedCore = () =>
  render(
    <QueryClientProvider client={createQueryClient()}>
      <ArchitectureOutline
        analysisId={AN}
        focus={null}
        onCollapse={vi.fn()}
        onExpand={vi.fn()}
        onSelect={vi.fn()}
        view={{ level: "TYPE", parentId: CORE }}
      />
    </QueryClientProvider>
  );

const treeItems = (): HTMLElement[] => screen.queryAllByRole("treeitem");

const waitForErrorRow = async (): Promise<HTMLElement> => {
  await waitFor(() =>
    expect(
      treeItems().map((row) => row.dataset.elementId ?? null)
    ).toStrictEqual([API, CORE, null, CORE_GEN, CORE_SPI])
  );
  const [, , failed] = treeItems();
  if (failed === undefined) {
    throw new Error("sem linha de erro");
  }
  return failed;
};

describe("ELEMENT_NOT_FOUND: o registro", () => {
  test("é um erro, não admite nova tentativa, e o corpo é o de error-codes.md", () => {
    const message = formatProtocolError(ELEMENT_NOT_FOUND);

    expect(message.severity).toBe("error");
    expect(message.retryable).toBe(false);
    expect(message.body).toStrictEqual([
      { kind: "chrome", text: ELEMENT_NOT_FOUND_BODY },
    ]);
  });
});

describe("ArchitectureOutline: os filhos de um pacote que a análise não tem", () => {
  test("a recusa vira a linha de erro do nível 2, sob o pacote", async () => {
    renderOpenedCore();

    const failed = await waitForErrorRow();

    expect(failed.getAttribute("aria-level")).toBe("2");
    expect(failed.getAttribute("aria-expanded")).toBeNull();
    expect(failed.getAttribute("aria-selected")).toBeNull();
  });

  test("a linha diz o que o registro diz, e não o genérico", async () => {
    renderOpenedCore();

    const failed = await waitForErrorRow();

    expect(failed.textContent).toContain(ELEMENT_NOT_FOUND_BODY);
    expect(failed.textContent).not.toBe(t.outline.failed);
  });

  test('o código não admite nova tentativa: a linha não oferece "Tentar de novo"', async () => {
    renderOpenedCore();

    const failed = await waitForErrorRow();

    expect(failed.textContent).not.toContain(t.graph.retry);
  });
});
