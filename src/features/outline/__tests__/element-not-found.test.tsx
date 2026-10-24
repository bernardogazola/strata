import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { FakeBridgeError, fakeRequest } from "@/bridge/fake";
import { transport } from "@/bridge/transport";
import { ArchitectureOutline } from "@/features/outline/architecture-outline";
import { flattenOutline } from "@/features/outline/use-outline";
import { formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";
import { createQueryClient } from "@/queries/client";
import { ROOT_ITEMS, UNKNOWN_PARENT } from "@/test/fixture-graph";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const ELEMENT_NOT_FOUND_BODY = "Este elemento não faz parte desta análise.";

const VIEWPORT = { height: 400, width: 320 } as const;

const ROOT_IDS = ROOT_ITEMS.map((item) => item.id);

const LISTED_PACKAGE = ROOT_IDS[1] ?? "";

let refusedParent = UNKNOWN_PARENT.parentId;

beforeEach(() => {
  refusedParent = UNKNOWN_PARENT.parentId;
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(
    VIEWPORT.height
  );
  vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(
    VIEWPORT.width
  );
  vi.spyOn(transport, "request").mockImplementation((method, payload) => {
    const { parentId } = payload as { readonly parentId?: unknown };
    return method === "outline.children" && parentId === refusedParent
      ? Promise.reject(new FakeBridgeError(UNKNOWN_PARENT.error))
      : fakeRequest(method, payload);
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

const renderOpened = (parentId: string) =>
  render(
    <QueryClientProvider client={createQueryClient()}>
      <ArchitectureOutline
        analysisId={AN}
        focus={null}
        onCollapse={vi.fn()}
        onExpand={vi.fn()}
        onSelect={vi.fn()}
        view={{ level: "TYPE", parentId }}
      />
    </QueryClientProvider>
  );

const treeItems = (): HTMLElement[] => screen.queryAllByRole("treeitem");

const rowIds = (): (string | null)[] =>
  treeItems().map((row) => row.dataset.elementId ?? null);

const waitForOrphanRow = async (): Promise<HTMLElement> => {
  await waitFor(() => expect(rowIds()).toStrictEqual([null, ...ROOT_IDS]));
  const [refused] = treeItems();
  if (refused === undefined) {
    throw new Error("sem linha de recusa");
  }
  return refused;
};

describe("ELEMENT_NOT_FOUND: o registro", () => {
  test("é um erro, não admite nova tentativa, e o corpo é o de error-codes.md", () => {
    const message = formatProtocolError(UNKNOWN_PARENT.error);

    expect(UNKNOWN_PARENT.error.code).toBe("ELEMENT_NOT_FOUND");
    expect(message.severity).toBe("error");
    expect(message.retryable).toBe(false);
    expect(message.body).toStrictEqual([
      { kind: "chrome", text: ELEMENT_NOT_FOUND_BODY },
    ]);
  });
});

describe("ArchitectureOutline: o parent velho de uma URL colada", () => {
  test("a fixture pede um pacote que a raiz do mundo não tem: é o caso real", () => {
    expect(ROOT_IDS).toHaveLength(4);
    expect(ROOT_IDS).not.toContain(UNKNOWN_PARENT.parentId);
  });

  test("a recusa é a primeira linha da raiz, no nível 1, e os pacotes ficam todos", async () => {
    renderOpened(UNKNOWN_PARENT.parentId);

    const refused = await waitForOrphanRow();

    expect(refused.getAttribute("aria-level")).toBe("1");
    expect(refused.getAttribute("aria-expanded")).toBeNull();
    expect(refused.getAttribute("aria-selected")).toBeNull();
  });

  test("a linha diz o corpo do registro, e não o genérico nem Tentar de novo", async () => {
    renderOpened(UNKNOWN_PARENT.parentId);

    const refused = await waitForOrphanRow();

    expect(refused.textContent).toBe(ELEMENT_NOT_FOUND_BODY);
    expect(refused.textContent).not.toContain(t.outline.failed);
    expect(refused.textContent).not.toContain(t.graph.retry);
  });

  test("é a parada de Tab da árvore: quem entra por teclado ouve primeiro por que nada abriu", async () => {
    renderOpened(UNKNOWN_PARENT.parentId);

    const refused = await waitForOrphanRow();

    const tabbable = treeItems().filter((row) => row.tabIndex === 0);
    expect(tabbable).toStrictEqual([refused]);
  });

  test("o texto quebra em vez de cortar: sem truncate, e a linha tem altura mínima, não fixa", async () => {
    renderOpened(UNKNOWN_PARENT.parentId);

    const refused = await waitForOrphanRow();
    const text = [...refused.querySelectorAll("span")].find(
      (span) => span.textContent === ELEMENT_NOT_FOUND_BODY
    );

    expect(text?.className.split(" ")).not.toContain("truncate");
    expect(text?.className.split(" ")).toContain("break-words");
    expect(refused.style.height).toBe("");
    expect(refused.style.minHeight).toBe("28px");
  });
});

describe("ArchitectureOutline: uma recusa sob um pacote que a raiz tem (defensivo)", () => {
  test("a recusa fica sob o pacote, no nível 2, com o mesmo corpo", async () => {
    refusedParent = LISTED_PACKAGE;
    renderOpened(LISTED_PACKAGE);

    await waitFor(() =>
      expect(rowIds()).toStrictEqual([
        ROOT_IDS[0],
        LISTED_PACKAGE,
        null,
        ...ROOT_IDS.slice(2),
      ])
    );
    const [, , refused] = treeItems();

    expect(refused?.getAttribute("aria-level")).toBe("2");
    expect(refused?.textContent).toBe(ELEMENT_NOT_FOUND_BODY);
  });
});

describe("flattenOutline: onde a falha de um pai que a raiz não tem entra", () => {
  const ORPHAN = UNKNOWN_PARENT.parentId;
  const complete = { failure: null, hasNextPage: false, nodes: ROOT_ITEMS };

  test("com a raiz completa, a falha é a primeira linha, no nível 1", () => {
    const rows = flattenOutline(complete, {
      failure: "load",
      hasNextPage: false,
      nodes: undefined,
      parentId: ORPHAN,
    });

    expect(rows.map((row) => row.key)).toStrictEqual([
      `error:${ORPHAN}`,
      ...ROOT_IDS,
    ]);
    expect(rows[0]).toMatchObject({ level: 1, parentId: ORPHAN });
  });

  test("com páginas da raiz por vir, nada ainda: o pacote pode estar em uma delas", () => {
    const rows = flattenOutline(
      { ...complete, hasNextPage: true },
      {
        failure: "load",
        hasNextPage: false,
        nodes: undefined,
        parentId: ORPHAN,
      }
    );

    expect(rows.map((row) => row.type)).toStrictEqual([
      ...ROOT_IDS.map(() => "node"),
      "more",
    ]);
  });

  test("com os tipos ainda a caminho, nenhuma linha órfã", () => {
    const rows = flattenOutline(complete, {
      failure: null,
      hasNextPage: false,
      nodes: undefined,
      parentId: ORPHAN,
    });

    expect(rows.map((row) => row.key)).toStrictEqual(ROOT_IDS);
  });
});
