import { describe, expect, test } from "vitest";
import { pinIndexes } from "@/lib/virtual-range";

describe("pinIndexes", () => {
  test("a janela sozinha quando as presas já estão nela", () => {
    expect(pinIndexes([3, 4, 5], [4, null])).toStrictEqual([3, 4, 5]);
  });

  test("as presas fora da janela entram em ordem de leitura, sem repetir", () => {
    expect(pinIndexes([10, 11, 12], [999, 0])).toStrictEqual([
      0, 10, 11, 12, 999,
    ]);
    expect(pinIndexes([10, 11], [3, 3])).toStrictEqual([3, 10, 11]);
  });

  test("devolve uma lista nova, e nunca a janela que recebeu", () => {
    const range = [3, 4, 5];

    const pinned = pinIndexes(range, [null, null]);

    expect(pinned).toStrictEqual(range);
    expect(pinned).not.toBe(range);
  });
});
