import { describe, expect, test } from "vitest";
import { clampText, sanitizeUntrusted } from "../untrusted";

describe("sanitizeUntrusted", () => {
  test("remove overrides de direção usados no ataque Trojan Source", () => {
    const rtlOverride = String.fromCodePoint(0x20_2e);
    const popDirectional = String.fromCodePoint(0x20_2c);
    const attack = `fix: ${rtlOverride}gnp.eldnah${popDirectional} handler`;

    const clean = sanitizeUntrusted(attack);
    expect(clean).toBe("fix: gnp.eldnah handler");
    expect(clean).not.toContain(rtlOverride);
  });

  test("remove a marca da direita para a esquerda", () => {
    expect(sanitizeUntrusted(`a${String.fromCodePoint(0x20_0f)}b`)).toBe("ab");
  });

  test("remove caracteres de controle", () => {
    expect(sanitizeUntrusted(`a${String.fromCodePoint(0)}b`)).toBe("ab");
  });

  test("preserva tab e quebra de linha, legítimos em mensagem de commit", () => {
    expect(sanitizeUntrusted("linha1\n\tlinha2")).toBe("linha1\n\tlinha2");
  });

  test("não mexe em texto comum, inclusive acentuado", () => {
    expect(sanitizeUntrusted("Correção de concorrência")).toBe(
      "Correção de concorrência"
    );
  });
});

describe("clampText", () => {
  test("deixa passar texto dentro do limite", () => {
    expect(clampText("curto", 10)).toBe("curto");
  });

  test("corta e sinaliza com reticências", () => {
    expect(clampText("abcdefghij", 4)).toBe("abcd…");
  });

  test("conta pontos de código, não unidades UTF-16", () => {
    expect(clampText("👩‍💻🚀🚀🚀", 1)).toBe("👩…");
  });
});
