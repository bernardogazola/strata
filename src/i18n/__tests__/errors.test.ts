import { describe, expect, test } from "vitest";
import { cacheUnavailableCauseSchema } from "@/protocol/enums";
import { ERROR_CODES, errorParamsSchemaByCode } from "@/protocol/errors";
import { type ErrorMessage, formatProtocolError } from "../errors";

const EVERY_PARAM = {
  cause: "LOCKED",
  exitCode: 1,
  expected: 1,
  filePath: "src/main/java/Exemplo.java",
  firstFilePath: "src/main/java/Primeiro.java",
  found: 10,
  fromVersion: 3,
  limit: 5,
  limitBytes: 1024,
  method: "graph.slice",
  received: 2,
  receivedBytes: 2048,
  timeoutMs: 30_000,
  toVersion: 4,
};

const plainText = (message: ErrorMessage): string =>
  message.body
    .map((segment) =>
      segment.kind === "chrome" ? segment.text : segment.value
    )
    .join("");

const UNTRUSTED_SENTINEL = "SENTINELA-DO-REPOSITORIO/Estranho.java";

const CODES_RENDERING_A_PATH = [
  "REPO_PATH_ESCAPES_ROOT",
  "REPO_UNREADABLE",
  "SOURCE_DUPLICATE_ELEMENT",
  "SOURCE_ELEMENT_ID_TOO_LONG",
  "SOURCE_FILE_TOO_LARGE",
  "SOURCE_PARSE_FAILED",
];

describe("formatProtocolError", () => {
  test("todo código do catálogo produz uma mensagem em pt-BR", () => {
    for (const code of ERROR_CODES) {
      const message = formatProtocolError({ code, params: EVERY_PARAM });

      expect(message.title.length, code).toBeGreaterThan(0);
      expect(message.body.length, code).toBeGreaterThan(0);
      expect(message.title, code).not.toBe("Erro não reconhecido");
    }
  });

  test("nenhum código leva um valor do repositório para dentro da copy", () => {
    const rendered: string[] = [];

    for (const code of ERROR_CODES) {
      const message = formatProtocolError({
        code,
        params: { ...EVERY_PARAM, filePath: UNTRUSTED_SENTINEL },
      });

      const chrome = message.body
        .filter((segment) => segment.kind === "chrome")
        .map((segment) => segment.text)
        .join("");

      expect(chrome, code).not.toContain(UNTRUSTED_SENTINEL);
      expect(message.title, code).not.toContain(UNTRUSTED_SENTINEL);
      expect(message.hint ?? "", code).not.toContain(UNTRUSTED_SENTINEL);

      if (
        message.body.some(
          (segment) =>
            segment.kind === "untrusted" && segment.value === UNTRUSTED_SENTINEL
        )
      ) {
        rendered.push(code);
      }
    }

    expect(rendered).toStrictEqual(CODES_RENDERING_A_PATH);
  });

  test("PROC_ANALYZER_START_FAILED não tem mais parâmetro próprio", () => {
    expect(
      Object.keys(errorParamsSchemaByCode.PROC_ANALYZER_START_FAILED.shape)
    ).toStrictEqual([]);
  });

  test("cai no fallback quando o código é desconhecido", () => {
    const message = formatProtocolError({ code: "ALGO_NOVO_DO_FUTURO" });
    expect(message.title).toBe("Erro não reconhecido");
    expect(message.body).toContainEqual({
      kind: "untrusted",
      value: "ALGO_NOVO_DO_FUTURO",
    });
  });

  test("cai no fallback em vez de renderizar undefined quando faltam parâmetros", () => {
    const message = formatProtocolError({ code: "SOURCE_PARSE_FAILED" });
    expect(message.title).toBe("Erro não reconhecido");
  });

  test("severidade e retryable vêm do registro, não do fio", () => {
    const rebuilt = formatProtocolError({ code: "CACHE_CORRUPT" });
    expect(rebuilt.severity).toBe("warning");
    expect(rebuilt.retryable).toBe(true);

    const bare = formatProtocolError({ code: "REPO_IS_BARE" });
    expect(bare.severity).toBe("error");
    expect(bare.retryable).toBe(false);

    const timeout = formatProtocolError({
      code: "PROC_REQUEST_TIMEOUT",
      params: { method: "graph.slice", timeoutMs: 30_000 },
    });
    expect(timeout.severity).toBe("error");
    expect(timeout.retryable).toBe(true);
  });

  test("a raiz ilegível bloqueia e admite nova tentativa; um caminho ilegível só avisa", () => {
    const root = formatProtocolError({ code: "REPO_ROOT_UNREADABLE" });
    expect(root.severity).toBe("error");
    expect(root.retryable).toBe(true);

    const entry = formatProtocolError({
      code: "REPO_UNREADABLE",
      params: { filePath: "src/main/java/Exemplo.java" },
    });
    expect(entry.severity).toBe("warning");
  });

  test("REPO_TOO_MANY_FILES não imprime `found`, que é só um piso", () => {
    const message = formatProtocolError({
      code: "REPO_TOO_MANY_FILES",
      params: { found: 200_001, limit: 200_000 },
    });
    const text = plainText(message);

    expect(text).toContain("mais de 200.000 arquivos e pastas");
    expect(text).not.toContain("200.001");
  });

  test("ANALYSIS_TOO_MANY_JAVA_SOURCES imprime a contagem e o limite", () => {
    const message = formatProtocolError({
      code: "ANALYSIS_TOO_MANY_JAVA_SOURCES",
      params: { found: 61_234, limit: 60_000 },
    });

    expect(message.title).toBe("Código demais para uma análise");
    expect(plainText(message)).toBe(
      "A seleção tem 61.234 arquivos Java, acima do limite de 60.000 que o Strata analisa com segurança."
    );
    expect(message.hint).toBe(
      "Deixe os testes de fora ou escolha menos raízes de código."
    );
    expect(message.severity).toBe("error");
    expect(message.retryable).toBe(false);
  });

  test("CACHE_UNAVAILABLE: cada causa tem a sua frase, a sua dica e a sua nova tentativa", () => {
    const expectedRetryable = {
      LOCATION_INSIDE_REPOSITORY: false,
      LOCKED: true,
      PRAGMA_REFUSED: false,
      STORAGE: true,
    } as const;
    const bodies = new Set<string>();
    const hints = new Set<string>();

    expect(Object.keys(expectedRetryable).sort()).toStrictEqual(
      [...cacheUnavailableCauseSchema.options].sort()
    );
    for (const cause of cacheUnavailableCauseSchema.options) {
      const message = formatProtocolError({
        code: "CACHE_UNAVAILABLE",
        params: { cause },
      });

      expect(message.title, cause).toBe("Cache de análise indisponível");
      expect(message.severity, cause).toBe("error");
      expect(message.retryable, cause).toBe(expectedRetryable[cause]);
      expect(message.hint ?? "", cause).not.toBe("");
      bodies.add(plainText(message));
      hints.add(message.hint ?? "");
    }
    expect(bodies.size).toBe(cacheUnavailableCauseSchema.options.length);
    expect(hints.size).toBe(cacheUnavailableCauseSchema.options.length);
  });

  test("CACHE_UNAVAILABLE com uma causa que esta versão não conhece cai no fallback", () => {
    const message = formatProtocolError({
      code: "CACHE_UNAVAILABLE",
      params: { cause: "DISK_ON_FIRE" },
    });

    expect(message.title).toBe("Erro não reconhecido");
    expect(message.retryable).toBe(false);
  });

  test("ANALYSIS_ALREADY_RUNNING não promete uma análise por repositório", () => {
    const message = formatProtocolError({ code: "ANALYSIS_ALREADY_RUNNING" });

    expect(plainText(message)).toBe(
      "Já existe uma análise em andamento, e o Strata faz uma de cada vez."
    );
    expect(plainText(message)).not.toContain("este repositório");
  });

  test("REPO_NOT_A_GIT_REPOSITORY tem a cópia exata, verdadeira também para o clone que a guarda recusa", () => {
    const message = formatProtocolError({ code: "REPO_NOT_A_GIT_REPOSITORY" });

    expect(message.title).toBe("Repositório Git não suportado");
    expect(plainText(message)).toBe(
      "A pasta selecionada não é um repositório Git, ou guarda os objetos do Git de um jeito que o Strata não lê com segurança."
    );
    expect(message.hint).toBe(
      "Selecione a raiz de um repositório clonado. Se ele já é um, compacte os objetos com git gc e tente de novo."
    );
    expect(message.severity).toBe("error");
    expect(message.retryable).toBe(false);
  });

  test("ANALYSIS_CACHE_CORRUPTED tem a cópia exata e não promete que nada se perdeu", () => {
    const message = formatProtocolError({ code: "ANALYSIS_CACHE_CORRUPTED" });

    expect(message.title).toBe("Cache danificado durante a análise");
    expect(plainText(message)).toBe(
      "O cache de análise deste repositório se danificou durante a análise. O Strata o recriou, e o que esta análise já tinha gravado se perdeu com ele."
    );
    expect(message.hint).toBe("Analise de novo.");
    expect(message.severity).toBe("error");
    expect(message.retryable).toBe(true);
    expect(plainText(message)).not.toContain("Nenhum resultado foi perdido");
  });
});
