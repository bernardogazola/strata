import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { transport } from "@/bridge/transport";
import { t } from "@/i18n/messages";
import type { ProtocolMethod } from "@/protocol/envelope";
import type { RepositoryDescription } from "@/protocol/repository";
import { type BackendState, useAppStore } from "@/state/app-store";
import { PrepareScreen } from "../prepare-screen";
import { StartScreen } from "../start-screen";
import { HANDLE, OTHER_HANDLE, renderRoutes } from "./route-harness";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
const READY: BackendState = { phase: "READY", protocolVersion: 1 };
const initialStore = useAppStore.getState();

const DESCRIPTION: RepositoryDescription = {
  buildSystem: "MAVEN",
  cachedAnalysisId: null,
  declaredModules: ["core", "api"],
  defaultBranch: "main",
  displayName: "multi-module",
  headCommitId: "fc5c752e7e9610c39de3b7b7caf2a74b7dfe6249",
  isBare: false,
  isGitRepository: true,
  isShallow: false,
  sourceRoots: [
    {
      id: "sr_sqbycfms",
      javaFileCount: 2,
      kind: "MAIN",
      label: "api/src/main/java",
    },
    {
      id: "sr_3gwc7d6d",
      javaFileCount: 6,
      kind: "MAIN",
      label: "core/src/main/java",
    },
    {
      id: "sr_jc51sw4p",
      javaFileCount: 1,
      kind: "TEST",
      label: "core/src/test/java",
    },
  ],
  totalFileCount: 14,
};

type Answer = (payload: unknown) => Promise<unknown>;

const answer = (answers: Partial<Record<ProtocolMethod, Answer>>) =>
  vi
    .spyOn(transport, "request")
    .mockImplementation((method, payload) =>
      (
        answers[method] ??
        (() => Promise.reject(new Error(`sem resposta para ${method}`)))
      )(payload)
    );

const describeOk: Answer = () => Promise.resolve(DESCRIPTION);
const describeBare: Answer = () =>
  Promise.reject({ code: "REPO_IS_BARE", origin: "analyzer", params: {} });

const renderPrepare = () =>
  renderRoutes(`/repository/${HANDLE}`, {
    index: () => <p>index</p>,
    repository: (handle) => <PrepareScreen handle={handle} />,
  });

const main = () => screen.getByRole("main", { name: t.shell.regions.prepare });

const analyze = () => screen.getByRole("button", { name: t.config.analyze });

beforeEach(() => {
  useAppStore.setState({ ...initialStore, backend: READY }, true);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("PrepareScreen", () => {
  test("o describe que chega vira os fatos e o formulário", async () => {
    const request = answer({ "repository.describe": describeOk });
    renderPrepare();

    expect(
      await screen.findByRole("heading", { level: 1, name: "multi-module" })
    ).toBeDefined();
    expect(main()).toBeDefined();
    expect(screen.getByRole("form", { name: t.config.title })).toBeDefined();
    expect(
      within(screen.getByRole("banner")).getByText("multi-module")
    ).toBeDefined();
    expect(screen.getByText("Maven")).toBeDefined();
    expect(
      screen.getByRole("checkbox", { name: "api/src/main/java" })
    ).toBeChecked();
    expect(request).toHaveBeenCalledWith("repository.describe", {}, HANDLE);
  });

  test("enquanto o describe não chega, esqueleto e o anúncio da leitura", async () => {
    answer({ "repository.describe": () => new Promise(() => undefined) });
    renderPrepare();

    expect(await screen.findByRole("status")).toHaveTextContent(
      t.describe.loading
    );
    expect(screen.queryByRole("form")).toBeNull();
  });

  test("o describe espera o analisador READY", async () => {
    useAppStore.setState({ backend: { phase: "STARTING" } });
    const request = answer({ "repository.describe": describeOk });
    renderPrepare();

    expect(await screen.findByRole("status")).toHaveTextContent(
      t.describe.loading
    );
    expect(request).not.toHaveBeenCalled();

    useAppStore.setState({ backend: READY });

    expect(
      await screen.findByRole("heading", { level: 1, name: "multi-module" })
    ).toBeDefined();
    expect(request).toHaveBeenCalledTimes(1);
  });

  test("REPO_IS_BARE: o título em pt-BR e as duas saídas", async () => {
    answer({ "repository.describe": describeBare });
    renderPrepare();

    expect(
      await screen.findByRole("heading", {
        name: "Repositório sem árvore de trabalho",
      })
    ).toBeDefined();
    expect(
      within(main()).getByRole("button", { name: t.repository.chooseAnother })
    ).toBeDefined();
    expect(
      within(main()).getByRole("link", { name: t.repository.backToStart })
    ).toBeDefined();
  });

  test("Voltar ao início sai sem fechar o handle", async () => {
    answer({ "repository.describe": describeBare });
    const close = vi.spyOn(transport, "closeRepository");
    const { router } = renderPrepare();

    fireEvent.click(
      await screen.findByRole("link", { name: t.repository.backToStart })
    );

    expect(await screen.findByText("index")).toBeDefined();
    expect(router.state.location.pathname).toBe("/");
    expect(close).not.toHaveBeenCalled();
  });

  test("Escolher outra pasta fecha este handle e abre o outro", async () => {
    answer({ "repository.describe": describeBare });
    const close = vi
      .spyOn(transport, "closeRepository")
      .mockResolvedValue(undefined);
    const open = vi.spyOn(transport, "openRepository").mockResolvedValue({
      displayName: "multi-module",
      handle: OTHER_HANDLE,
    });
    const { router } = renderPrepare();

    fireEvent.click(
      await screen.findByRole("button", { name: t.repository.chooseAnother })
    );

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(`/repository/${OTHER_HANDLE}`)
    );
    expect(close).toHaveBeenCalledWith(HANDLE);
    expect(open).toHaveBeenCalledTimes(1);
    expect(close.mock.invocationCallOrder[0]).toBeLessThan(
      open.mock.invocationCallOrder[0] ?? 0
    );
  });

  test("Escolher outra pasta e fechar o seletor volta ao início", async () => {
    answer({ "repository.describe": describeBare });
    vi.spyOn(transport, "closeRepository").mockResolvedValue(undefined);
    vi.spyOn(transport, "openRepository").mockResolvedValue(null);
    const { router } = renderPrepare();

    fireEvent.click(
      await screen.findByRole("button", { name: t.repository.chooseAnother })
    );

    expect(await screen.findByText("index")).toBeDefined();
    expect(router.state.location.pathname).toBe("/");
  });

  test("Analisar envia a configuração padrão e empilha ?analysis=<id>", async () => {
    const request = answer({
      "analysis.start": () =>
        Promise.resolve({ analysisId: AN, reusedCache: false }),
      "repository.describe": describeOk,
    });
    const { router } = renderPrepare();
    await screen.findByRole("heading", { level: 1, name: "multi-module" });

    fireEvent.click(analyze());

    await waitFor(() =>
      expect(router.state.location.search).toMatchObject({ analysis: AN })
    );
    expect(
      Object.fromEntries(new URLSearchParams(router.state.location.searchStr))
    ).toEqual({ analysis: AN, level: "PACKAGE" });
    expect(router.history.length).toBe(2);
    expect(request).toHaveBeenCalledWith(
      "analysis.start",
      {
        historyScope: "ALL",
        historyScopeCommits: null,
        includeTests: false,
        rebuildCache: false,
        sourceRootIds: null,
      },
      HANDLE
    );
  });

  test("ANALYSIS_ALREADY_RUNNING vira o aviso acima do rodapé, sem navegar", async () => {
    answer({
      "analysis.start": () =>
        Promise.reject({
          code: "ANALYSIS_ALREADY_RUNNING",
          origin: "analyzer",
          params: {},
        }),
      "repository.describe": describeOk,
    });
    const { router } = renderPrepare();
    await screen.findByRole("heading", { level: 1, name: "multi-module" });

    fireEvent.click(analyze());

    expect(
      await screen.findByText(
        "Já existe uma análise em andamento, e o Strata faz uma de cada vez."
      )
    ).toBeDefined();
    expect(router.state.location.search).not.toHaveProperty("analysis");
    expect(analyze()).not.toHaveAttribute("aria-disabled", "true");
  });

  test("uma análise em voo na store desabilita Analisar", async () => {
    useAppStore.setState({ runningAnalysisId: AN });
    answer({ "repository.describe": describeOk });
    renderPrepare();
    await screen.findByRole("heading", { level: 1, name: "multi-module" });

    expect(analyze()).toHaveAttribute("aria-disabled", "true");
    expect(analyze()).toHaveAccessibleDescription(
      t.config.disabled.runInFlight
    );
  });

  test("Fechar repositório fecha o handle e volta ao início", async () => {
    answer({ "repository.describe": describeOk });
    const close = vi
      .spyOn(transport, "closeRepository")
      .mockResolvedValue(undefined);
    const { router } = renderPrepare();
    await screen.findByRole("heading", { level: 1, name: "multi-module" });

    fireEvent.click(screen.getByRole("button", { name: t.header.close }));

    expect(await screen.findByText("index")).toBeDefined();
    expect(close).toHaveBeenCalledWith(HANDLE);
    expect(router.state.location.pathname).toBe("/");
  });
});

describe("PrepareScreen: o foco de quem chega por um gesto", () => {
  const describeByHandle = (refused: string) =>
    vi
      .spyOn(transport, "request")
      .mockImplementation((method, payload, handle) =>
        method === "repository.describe"
          ? (handle === refused ? describeBare : describeOk)(payload)
          : Promise.reject(new Error(`sem resposta para ${method}`))
      );

  const activate = (button: HTMLElement): void => {
    button.focus();
    fireEvent.click(button);
  };

  test("abrir pelo início leva o foco ao título, só quando o describe chega", async () => {
    let deliver: (description: RepositoryDescription) => void = () => undefined;
    answer({
      "repository.describe": () =>
        new Promise((resolve) => {
          deliver = resolve;
        }),
    });
    vi.spyOn(transport, "openRepository").mockResolvedValue({
      displayName: "multi-module",
      handle: HANDLE,
    });
    renderRoutes("/", {
      index: StartScreen,
      repository: (handle) => <PrepareScreen handle={handle} />,
    });

    activate(await screen.findByRole("button", { name: t.repository.open }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      t.describe.loading
    );
    expect(document.activeElement).toBe(document.body);
    deliver(DESCRIPTION);
    const title = await screen.findByRole("heading", {
      level: 1,
      name: "multi-module",
    });
    await waitFor(() => expect(document.activeElement).toBe(title));
  });

  test("na carga da página, o describe que chega não move o foco", async () => {
    answer({ "repository.describe": describeOk });
    renderPrepare();

    await screen.findByRole("heading", { level: 1, name: "multi-module" });

    expect(document.activeElement).toBe(document.body);
  });

  test("Escolher outra pasta leva o foco ao título da outra", async () => {
    describeByHandle(HANDLE);
    vi.spyOn(transport, "closeRepository").mockResolvedValue(undefined);
    vi.spyOn(transport, "openRepository").mockResolvedValue({
      displayName: "multi-module",
      handle: OTHER_HANDLE,
    });
    renderPrepare();

    activate(
      await screen.findByRole("button", { name: t.repository.chooseAnother })
    );

    const title = await screen.findByRole("heading", {
      level: 1,
      name: "multi-module",
    });
    await waitFor(() => expect(document.activeElement).toBe(title));
  });

  test("Escolher outra pasta e fechar o seletor chega ao início com o foco no título", async () => {
    describeByHandle(HANDLE);
    vi.spyOn(transport, "closeRepository").mockResolvedValue(undefined);
    vi.spyOn(transport, "openRepository").mockResolvedValue(null);
    renderRoutes(`/repository/${HANDLE}`, {
      index: StartScreen,
      repository: (handle) => <PrepareScreen handle={handle} />,
    });

    activate(
      await screen.findByRole("button", { name: t.repository.chooseAnother })
    );

    const title = await screen.findByRole("heading", {
      level: 1,
      name: t.start.title,
    });
    await waitFor(() => expect(document.activeElement).toBe(title));
  });

  test("Fechar repositório chega ao início com o foco no título", async () => {
    answer({ "repository.describe": describeOk });
    vi.spyOn(transport, "closeRepository").mockResolvedValue(undefined);
    renderRoutes(`/repository/${HANDLE}`, {
      index: StartScreen,
      repository: (handle) => <PrepareScreen handle={handle} />,
    });
    await screen.findByRole("heading", { level: 1, name: "multi-module" });

    activate(screen.getByRole("button", { name: t.header.close }));

    const title = await screen.findByRole("heading", {
      level: 1,
      name: t.start.title,
    });
    await waitFor(() => expect(document.activeElement).toBe(title));
  });
});
