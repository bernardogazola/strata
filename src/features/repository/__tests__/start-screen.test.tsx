import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { transport } from "@/bridge/transport";
import { GESTURE_STATE } from "@/features/shell/focus-title";
import { useCloseRepository } from "@/features/shell/use-close-repository";
import { t } from "@/i18n/messages";
import { type BackendState, useAppStore } from "@/state/app-store";
import { StartScreen } from "../start-screen";
import { OTHER_HANDLE, renderRoutes } from "./route-harness";

const READY: BackendState = { phase: "READY", protocolVersion: 1 };
const initialStore = useAppStore.getState();

const deferred = <T,>() => {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>((settle) => {
    resolve = settle;
  });
  return { promise, resolve };
};

const renderStart = (backend: BackendState) => {
  useAppStore.setState({ backend });
  return renderRoutes("/", {
    index: StartScreen,
    repository: (handle) => <p>{`repository:${handle}`}</p>,
  });
};

const main = () => screen.getByRole("main", { name: t.shell.regions.start });

function CloseButton({ handle }: { readonly handle: string }) {
  const onClose = useCloseRepository(handle);
  return (
    <button onClick={onClose} type="button">
      {t.header.close}
    </button>
  );
}

const openButton = () =>
  within(main()).getByRole("button", { name: t.repository.open });

beforeEach(() => {
  useAppStore.setState(initialStore, true);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("StartScreen", () => {
  test("título, corpo, botão e as três garantias, em um <main> com nome", async () => {
    renderStart(READY);

    await screen.findByRole("main", { name: t.shell.regions.start });
    expect(screen.getByRole("banner")).toBeDefined();
    expect(
      screen.getByRole("heading", { level: 1, name: t.start.title })
    ).toBeDefined();
    expect(screen.getByText(t.start.body)).toBeDefined();
    const guarantees = within(main()).getAllByRole("listitem");
    expect(guarantees.map((item) => item.textContent)).toEqual([
      ...t.start.guarantees,
    ]);
    for (const item of guarantees) {
      expect(item.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
    }
  });

  test("o título recebe foco de programa, mas a montagem não o move", async () => {
    renderStart(READY);

    const title = await screen.findByRole("heading", {
      level: 1,
      name: t.start.title,
    });
    expect(title.getAttribute("tabindex")).toBe("-1");
    expect(document.activeElement).toBe(document.body);
  });

  test("Fechar repositório chega ao início com o foco no título", async () => {
    vi.spyOn(transport, "closeRepository").mockResolvedValue(undefined);
    const { router } = renderRoutes(`/repository/${OTHER_HANDLE}`, {
      index: StartScreen,
      repository: (handle) => <CloseButton handle={handle} />,
    });
    const close = await screen.findByRole("button", { name: t.header.close });
    close.focus();

    fireEvent.click(close);

    const title = await screen.findByRole("heading", {
      level: 1,
      name: t.start.title,
    });
    await waitFor(() => expect(document.activeElement).toBe(title));
    expect(router.state.location.pathname).toBe("/");
  });
});

describe("OpenRepository", () => {
  test("READY: o botão funciona e descreve a janela do sistema", async () => {
    renderStart(READY);

    await screen.findByRole("main", { name: t.shell.regions.start });
    expect(openButton()).not.toHaveAttribute("aria-disabled", "true");
    expect(openButton()).toHaveAccessibleDescription(t.start.pickerHint);
  });

  test("STARTING: desabilitado, focável e com o motivo", async () => {
    const open = vi.spyOn(transport, "openRepository");
    renderStart({ phase: "STARTING" });

    await screen.findByRole("main", { name: t.shell.regions.start });
    expect(openButton()).toHaveAttribute("aria-disabled", "true");
    expect(openButton()).not.toHaveAttribute("disabled");
    expect(openButton()).toHaveAccessibleDescription(
      t.start.unavailable.STARTING
    );
    fireEvent.click(openButton());
    expect(open).not.toHaveBeenCalled();
  });

  test("abrir com sucesso empilha a rota do repositório", async () => {
    vi.spyOn(transport, "openRepository").mockResolvedValue({
      displayName: "multi-module",
      handle: OTHER_HANDLE,
    });
    const { router } = renderStart(READY);
    await screen.findByRole("main", { name: t.shell.regions.start });

    fireEvent.click(openButton());

    expect(await screen.findByText(`repository:${OTHER_HANDLE}`)).toBeDefined();
    expect(router.state.location.pathname).toBe(`/repository/${OTHER_HANDLE}`);
    expect(router.history.length).toBe(2);
    expect(router.state.location.state.focusTitle).toBe(
      GESTURE_STATE.focusTitle
    );
  });

  test("fechar o seletor fica na tela, sem erro", async () => {
    const open = vi.spyOn(transport, "openRepository").mockResolvedValue(null);
    const { router } = renderStart(READY);
    await screen.findByRole("main", { name: t.shell.regions.start });

    fireEvent.click(openButton());

    await waitFor(() => expect(open).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(openButton()).not.toHaveAttribute("aria-disabled", "true")
    );
    expect(router.state.location.pathname).toBe("/");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  test("com o seletor aberto, o botão fica inerte e mantém o rótulo", async () => {
    const pending = deferred<null>();
    vi.spyOn(transport, "openRepository").mockReturnValue(pending.promise);
    renderStart(READY);
    await screen.findByRole("main", { name: t.shell.regions.start });

    fireEvent.click(openButton());

    await waitFor(() =>
      expect(openButton()).toHaveAttribute("aria-disabled", "true")
    );
    expect(openButton()).toHaveAccessibleName(t.repository.open);
    pending.resolve(null);
    await waitFor(() =>
      expect(openButton()).not.toHaveAttribute("aria-disabled", "true")
    );
  });

  test("uma recusa ao abrir vira a mensagem em pt-BR, na própria tela", async () => {
    vi.spyOn(transport, "openRepository").mockRejectedValue({
      code: "REPO_ROOT_UNREADABLE",
      origin: "shell",
      params: {},
    });
    renderStart(READY);
    await screen.findByRole("main", { name: t.shell.regions.start });

    fireEvent.click(openButton());

    expect(
      await within(main()).findByText("Sem permissão para ler a pasta")
    ).toBeDefined();
  });

  test("CRASHED: o cartão com o código de saída, Reiniciar e o diagnóstico", async () => {
    renderStart({ exitCode: 137, phase: "CRASHED" });
    await screen.findByRole("main", { name: t.shell.regions.start });

    expect(openButton()).toHaveAttribute("aria-disabled", "true");
    expect(openButton()).toHaveAccessibleDescription(
      t.start.unavailable.CRASHED
    );
    expect(
      within(main()).getByRole("heading", { name: t.analyzer.crashed.title })
    ).toBeDefined();
    expect(
      within(main()).getByText(
        "O processo de análise encerrou com o código de saída 137. Ele não é reiniciado sozinho, porque reabrir o mesmo repositório pode repetir a falha."
      )
    ).toBeDefined();
  });

  test("CRASHED sem código de saída usa a frase sem número", async () => {
    renderStart({ exitCode: null, phase: "CRASHED" });

    expect(
      await within(
        await screen.findByRole("main", { name: t.shell.regions.start })
      ).findByText(t.analyzer.crashed.bodyNoCode)
    ).toBeDefined();
  });

  test("Reiniciar passa pela store e pela ponte, e o cartão some no READY", async () => {
    const restart = vi
      .spyOn(transport, "analyzerRestart")
      .mockResolvedValue(READY);
    renderStart({ exitCode: 137, phase: "CRASHED" });
    await screen.findByRole("main", { name: t.shell.regions.start });

    fireEvent.click(
      within(main()).getByRole("button", { name: t.repository.restart })
    );

    await waitFor(() => expect(useAppStore.getState().backend).toEqual(READY));
    expect(restart).toHaveBeenCalledTimes(1);
    expect(
      within(main()).queryByRole("heading", { name: t.analyzer.crashed.title })
    ).toBeNull();
    expect(openButton()).not.toHaveAttribute("aria-disabled", "true");
  });

  test("Exportar diagnóstico redige autores e caminhos, e o cancelamento é silêncio", async () => {
    const exported = vi
      .spyOn(transport, "exportDiagnostics")
      .mockResolvedValue({ written: false });
    renderStart({ exitCode: 137, phase: "CRASHED" });
    await screen.findByRole("main", { name: t.shell.regions.start });
    const button = within(main()).getByRole("button", {
      name: t.analyzer.exportDiagnostics,
    });

    fireEvent.click(button);

    await waitFor(() => expect(button).not.toBeDisabled());
    expect(exported).toHaveBeenCalledWith({
      redactAuthors: true,
      redactPaths: true,
    });
    expect(button).toHaveAccessibleDescription(t.analyzer.exportHint);
    expect(within(main()).getByText(t.analyzer.exportHint)).toBeDefined();
    expect(within(main()).queryByText("Código:", { exact: false })).toBeNull();
  });

  test("uma falha ao exportar o diagnóstico aparece no cartão", async () => {
    vi.spyOn(transport, "exportDiagnostics").mockRejectedValue({
      code: "PROC_SHUTTING_DOWN",
      origin: "shell",
      params: {},
    });
    renderStart({ exitCode: 137, phase: "CRASHED" });
    await screen.findByRole("main", { name: t.shell.regions.start });

    fireEvent.click(
      within(main()).getByRole("button", {
        name: t.analyzer.exportDiagnostics,
      })
    );

    expect(
      await within(main()).findByText("Código: PROC_SHUTTING_DOWN")
    ).toBeDefined();
  });

  test("INCOMPATIBLE: a divergência de versão, sem nova tentativa", async () => {
    const restart = vi.spyOn(transport, "analyzerRestart");
    renderStart({ expected: 1, phase: "INCOMPATIBLE", received: 2 });
    await screen.findByRole("main", { name: t.shell.regions.start });

    expect(openButton()).toHaveAttribute("aria-disabled", "true");
    expect(openButton()).toHaveAccessibleDescription(
      t.start.unavailable.INCOMPATIBLE
    );
    expect(
      within(main()).getByRole("heading", {
        level: 2,
        name: "Versões incompatíveis",
      })
    ).toBeDefined();
    expect(
      within(main())
        .getAllByRole("heading", { level: 1 })
        .map((h) => h.textContent)
    ).toStrictEqual([t.start.title]);
    expect(
      within(main()).getByText(
        "A interface fala a versão 1 do protocolo e o analisador fala a 2."
      )
    ).toBeDefined();
    expect(
      within(main()).queryByRole("button", { name: t.graph.retry })
    ).toBeNull();
    expect(
      within(main()).queryByRole("button", { name: t.repository.restart })
    ).toBeNull();
    expect(restart).not.toHaveBeenCalled();
  });
});
