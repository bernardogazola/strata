import { describe, expect, test } from "vitest";
import {
  formatDuration,
  formatInteger,
  formatPercent,
  formatRelativeTime,
  hasLocaleData,
  plural,
} from "../format";

describe("formatDuration", () => {
  test("mostra apenas segundos abaixo de um minuto", () => {
    expect(formatDuration(0)).toBe("0 s");
    expect(formatDuration(45_000)).toBe("45 s");
  });

  test("arredonda o total antes de decompor, nunca produzindo 60 s", () => {
    expect(formatDuration(119_600)).toBe("2 min 0 s");
    expect(formatDuration(179_600)).toBe("3 min 0 s");
    expect(formatDuration(3_599_600)).toBe("1 h 0 min");
  });

  test("promove para minutos exatamente em 60 s", () => {
    expect(formatDuration(59_950)).toBe("1 min 0 s");
  });

  test("usa horas e minutos acima de uma hora", () => {
    expect(formatDuration(3_900_000)).toBe("1 h 5 min");
  });
});

describe("plural", () => {
  test("segue o CLDR, onde zero cai na categoria singular em português", () => {
    const forms = { one: "commit", other: "commits" };
    expect(plural(1, forms)).toBe("commit");
    expect(plural(2, forms)).toBe("commits");
    expect(plural(0, forms)).toBe("commit");
  });

  test("aceita uma forma própria para zero, que lê melhor na interface", () => {
    const forms = { one: "commit", other: "commits", zero: "nenhum commit" };
    expect(plural(0, forms)).toBe("nenhum commit");
    expect(plural(1, forms)).toBe("commit");
    expect(plural(5, forms)).toBe("commits");
  });
});

describe("formatação numérica em pt-BR", () => {
  test("usa ponto como separador de milhar", () => {
    expect(formatInteger(28_841)).toBe("28.841");
  });

  test("recebe razão e não porcentagem já multiplicada", () => {
    expect(formatPercent(0.82)).toBe("82%");
  });
});

describe("formatRelativeTime", () => {
  test("escolhe a maior unidade que couber", () => {
    const now = new Date("2026-08-30T12:00:00Z");
    expect(formatRelativeTime(new Date("2026-08-29T12:00:00Z"), now)).toBe(
      "ontem"
    );
    expect(formatRelativeTime(new Date("2026-05-30T12:00:00Z"), now)).toBe(
      "há 3 meses"
    );
  });
});

describe("hasLocaleData", () => {
  test("confirma que o runtime traz os dados de pt-BR", () => {
    expect(hasLocaleData()).toBe(true);
  });
});
