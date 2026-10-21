import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { ErrorSurface } from "@/features/errors/error-surface";
import { formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";
import type { ErrorCode, ProtocolError } from "@/protocol/errors";

const RETRY = { name: t.graph.retry } as const;
const CODE_LINE = /^Código:/;
const MACOS_PRIVACY = /Privacidade e Segurança/;
const BIDI_CONTROL = /\p{Bidi_Control}/u;

const REPOSITORY_ERRORS = [
  { code: "REPO_HANDLE_UNKNOWN" },
  { code: "REPO_IS_BARE" },
  { code: "REPO_NO_HISTORY" },
  { code: "REPO_NO_JAVA_SOURCES" },
  { code: "REPO_NOT_A_GIT_REPOSITORY" },
  { code: "REPO_PATH_CHANGED" },
  { code: "REPO_PATH_ESCAPES_ROOT", params: { filePath: "../fora.java" } },
  { code: "REPO_ROOT_UNREADABLE" },
  { code: "REPO_TOO_MANY_FILES", params: { found: 200_001, limit: 200_000 } },
  { code: "REPO_UNREADABLE", params: { filePath: "src/Trancado.java" } },
] as const satisfies readonly (ProtocolError & {
  code: Extract<ErrorCode, `REPO_${string}`>;
})[];

describe("ErrorSurface, forma page", () => {
  test.each(REPOSITORY_ERRORS)(
    "$code tem título, corpo e código próprios",
    (error) => {
      const message = formatProtocolError(error);
      const { container } = render(<ErrorSurface error={error} />);

      const heading = screen.getByRole("heading", { level: 1 });
      expect(heading).toHaveTextContent(message.title);
      expect(heading).toHaveAttribute("tabindex", "-1");
      expect(screen.getByText(`Código: ${error.code}`)).toHaveClass(
        "font-mono"
      );
      expect(container.firstElementChild).toHaveClass("select-text");
    }
  );

  test("cada REPO_* diz uma coisa diferente", () => {
    const titles = REPOSITORY_ERRORS.map(
      (error) => formatProtocolError(error).title
    );
    expect(new Set(titles).size).toBe(titles.length);
  });

  test("repositório bare: a frase dele, a dica e nenhuma nova tentativa", () => {
    const onRetry = vi.fn();
    render(<ErrorSurface error={{ code: "REPO_IS_BARE" }} onRetry={onRetry} />);

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: "Repositório sem árvore de trabalho",
      })
    ).toBeDefined();
    expect(
      screen.getByText(
        "A pasta selecionada é um repositório bare, sem árvore de trabalho para analisar."
      )
    ).toBeDefined();
    expect(
      screen.getByText("Selecione um clone com os arquivos de trabalho.")
    ).toBeDefined();
    expect(screen.queryByRole("button", RETRY)).toBeNull();
  });

  test("sem código Java: diz que esta versão só lê Java", () => {
    render(<ErrorSurface error={{ code: "REPO_NO_JAVA_SOURCES" }} />);

    expect(
      screen.getByRole("heading", { level: 1, name: "Sem código Java" })
    ).toBeDefined();
    expect(
      screen.getByText(
        "Esta versão do Strata analisa apenas repositórios Java."
      )
    ).toBeDefined();
  });

  test("pasta ilegível: a dica do macOS e a nova tentativa", () => {
    const onRetry = vi.fn();
    render(
      <ErrorSurface
        error={{ code: "REPO_ROOT_UNREADABLE" }}
        onRetry={onRetry}
      />
    );

    expect(screen.getByText(MACOS_PRIVACY)).toBeDefined();
    fireEvent.click(screen.getByRole("button", RETRY));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  test("repositório grande demais imprime só o limite, nunca o piso found", () => {
    const { container } = render(
      <ErrorSurface
        error={{
          code: "REPO_TOO_MANY_FILES",
          params: { found: 200_001, limit: 200_000 },
        }}
      />
    );

    expect(container).toHaveTextContent("200.000");
    expect(container).not.toHaveTextContent("200.001");
  });

  test("o resumo de uma análise que falhou pede o título em h2", () => {
    render(<ErrorSurface error={{ code: "REPO_IS_BARE" }} headingLevel={2} />);

    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(
      screen.getByRole("heading", {
        level: 2,
        name: "Repositório sem árvore de trabalho",
      })
    ).toBeDefined();
  });

  test("a página não é um alerta: é conteúdo que a tela foca", () => {
    render(<ErrorSurface error={{ code: "REPO_IS_BARE" }} />);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  test("as ações extras vêm depois da nova tentativa", () => {
    render(
      <ErrorSurface
        actions={<button type="button">{t.repository.chooseAnother}</button>}
        error={{ code: "REPO_ROOT_UNREADABLE" }}
        onRetry={vi.fn()}
      />
    );

    const buttons = screen.getAllByRole("button");
    expect(buttons.map((button) => button.textContent)).toEqual([
      t.graph.retry,
      t.repository.chooseAnother,
    ]);
  });

  test("o detail em inglês do fio nunca chega à tela", () => {
    const { container } = render(
      <ErrorSurface
        error={{
          code: "REPO_IS_BARE",
          detail: "repository has no work tree at /Users/someone/repo.git",
        }}
      />
    );

    expect(container).not.toHaveTextContent("work tree");
    expect(container).not.toHaveTextContent("/Users/");
  });
});

describe("ErrorSurface, nova tentativa", () => {
  test("aparece se e somente se o código admite repetir e há onRetry", () => {
    const cases = [
      {
        error: { code: "REPO_ROOT_UNREADABLE" },
        onRetry: vi.fn(),
        shown: true,
      },
      {
        error: { code: "REPO_ROOT_UNREADABLE" },
        onRetry: undefined,
        shown: false,
      },
      { error: { code: "REPO_IS_BARE" }, onRetry: vi.fn(), shown: false },
      {
        error: { code: "CACHE_UNAVAILABLE", params: { cause: "LOCKED" } },
        onRetry: vi.fn(),
        shown: true,
      },
      {
        error: {
          code: "CACHE_UNAVAILABLE",
          params: { cause: "LOCATION_INSIDE_REPOSITORY" },
        },
        onRetry: vi.fn(),
        shown: false,
      },
    ] as const;

    for (const { error, onRetry, shown } of cases) {
      const view = render(<ErrorSurface error={error} onRetry={onRetry} />);
      expect(view.queryByRole("button", RETRY) !== null).toBe(shown);
      view.unmount();
    }
  });
});

describe("ErrorSurface, código desconhecido", () => {
  test("cai na mensagem genérica com o código isolado como texto do fio", () => {
    const { container } = render(
      <ErrorSurface error={{ code: "FUTURE_UNKNOWN_CODE" }} />
    );

    expect(
      screen.getByRole("heading", { level: 1, name: "Erro não reconhecido" })
    ).toBeDefined();
    const bdi = container.querySelector("bdi");
    expect(bdi?.textContent).toBe("FUTURE_UNKNOWN_CODE");
    expect(bdi).toHaveAttribute("translate", "no");
    expect(screen.queryByText(CODE_LINE)).toBeNull();
  });

  test("um código forjado com controle de direção chega saneado", () => {
    const forged = `REPO_IS_BARE${String.fromCodePoint(0x20_2e)}esrever`;
    const { container } = render(<ErrorSurface error={{ code: forged }} />);

    expect(BIDI_CONTROL.test(container.textContent ?? "")).toBe(false);
    expect(container.querySelector("bdi")?.textContent).toBe(
      "REPO_IS_BAREesrever"
    );
  });

  test("um código conhecido com parâmetros fora do schema também cai na genérica", () => {
    render(
      <ErrorSurface
        error={{ code: "SOURCE_PARSE_FAILED", params: { filePath: 42 } }}
      />
    );

    expect(
      screen.getByRole("heading", { level: 1, name: "Erro não reconhecido" })
    ).toBeDefined();
  });
});

describe("ErrorSurface, forma inline", () => {
  test("um erro novo se anuncia, e o título não é um cabeçalho", () => {
    render(
      <ErrorSurface
        error={{ code: "ANALYSIS_ALREADY_RUNNING" }}
        variant="inline"
      />
    );

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Análise em andamento");
    expect(alert).toHaveTextContent(
      "Aguarde a conclusão ou cancele a análise atual."
    );
    expect(alert).toHaveClass("select-text");
    expect(screen.queryByRole("heading")).toBeNull();
    expect(screen.getByText("Código: ANALYSIS_ALREADY_RUNNING")).toHaveClass(
      "font-mono"
    );
  });

  test("em uma lista de avisos não se anuncia", () => {
    render(
      <ErrorSurface
        announce={false}
        error={{
          code: "SOURCE_PARSE_FAILED",
          params: { filePath: "src/Quebrado.java" },
        }}
        variant="inline"
      />
    );

    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByText("src/Quebrado.java").tagName).toBe("BDI");
  });

  test("aviso e erro têm glifos diferentes, não só cores", () => {
    const warning = render(
      <ErrorSurface error={{ code: "REPO_NO_HISTORY" }} variant="inline" />
    );
    expect(
      warning.container.querySelector(".tabler-icon-alert-circle")
    ).not.toBeNull();
    warning.unmount();

    const error = render(
      <ErrorSurface error={{ code: "REPO_IS_BARE" }} variant="inline" />
    );
    expect(
      error.container.querySelector(".tabler-icon-circle-x")
    ).not.toBeNull();
    expect(
      error.container.querySelector("svg")?.getAttribute("aria-hidden")
    ).toBe("true");
  });
});
