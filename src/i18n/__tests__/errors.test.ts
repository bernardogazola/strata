import { describe, expect, test } from "vitest";
import { ERROR_CODES, errorParamsSchemaByCode } from "@/protocol/errors";
import { formatProtocolError } from "../errors";

const EVERY_PARAM = {
  exitCode: 1,
  expected: 1,
  filePath: "src/main/java/Exemplo.java",
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

const UNTRUSTED_SENTINEL = "SENTINELA-DO-REPOSITORIO/Estranho.java";

const CODES_RENDERING_A_PATH = [
  "REPO_PATH_ESCAPES_ROOT",
  "REPO_UNREADABLE",
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
    const cancelled = formatProtocolError({ code: "ANALYSIS_CANCELLED" });
    expect(cancelled.severity).toBe("warning");
    expect(cancelled.retryable).toBe(true);

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
    const text = message.body
      .map((segment) =>
        segment.kind === "chrome" ? segment.text : segment.value
      )
      .join("");

    expect(text).toContain("mais de 200.000 arquivos");
    expect(text).not.toContain("200.001");
  });
});
