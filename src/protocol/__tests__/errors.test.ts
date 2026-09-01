import { describe, expect, test } from "vitest";
import { formatProtocolError } from "@/i18n/errors";
import { ERROR_CODES, isKnownErrorCode } from "../errors";

describe("isKnownErrorCode", () => {
  test("reconhece os códigos do catálogo", () => {
    expect(isKnownErrorCode("REPO_NOT_A_GIT_REPOSITORY")).toBe(true);
  });

  test("não confunde membros herdados de Object.prototype com códigos", () => {
    expect(isKnownErrorCode("toString")).toBe(false);
    expect(isKnownErrorCode("constructor")).toBe(false);
    expect(isKnownErrorCode("hasOwnProperty")).toBe(false);
    expect(isKnownErrorCode("__proto__")).toBe(false);
  });
});

describe("formatProtocolError", () => {
  test("todo código do catálogo produz uma mensagem em pt-BR", () => {
    for (const code of ERROR_CODES) {
      const message = formatProtocolError({
        code,
        params: {
          detail: "boom",
          exitCode: 1,
          expected: 1,
          filePath: "src/main/java/Exemplo.java",
          found: 10,
          limit: 5,
          limitBytes: 1024,
          method: "graph.slice",
          receivedBytes: 2048,
        },
      });

      expect(message.title.length).toBeGreaterThan(0);
      expect(message.body.length).toBeGreaterThan(0);
    }
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

  test("marca valor vindo do repositório como não confiável", () => {
    const message = formatProtocolError({
      code: "SOURCE_PARSE_FAILED",
      params: { filePath: "src/main/java/Estranho.java" },
    });

    expect(message.body).toContainEqual({
      kind: "untrusted",
      value: "src/main/java/Estranho.java",
    });
    const chrome = message.body
      .filter((segment) => segment.kind === "chrome")
      .map((segment) => segment.text)
      .join("");
    expect(chrome).not.toContain("Estranho.java");
  });

  test("severidade e retryable vêm do registro, não do fio", () => {
    const cancelled = formatProtocolError({ code: "ANALYSIS_CANCELLED" });
    expect(cancelled.severity).toBe("warning");
    expect(cancelled.retryable).toBe(true);

    const bare = formatProtocolError({ code: "REPO_IS_BARE" });
    expect(bare.severity).toBe("error");
    expect(bare.retryable).toBe(false);
  });
});
