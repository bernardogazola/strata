import { render } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { PopDigits } from "@/features/progress/pop-digits";

const at = (milliseconds: number): void => {
  vi.spyOn(performance, "now").mockReturnValue(milliseconds);
};

const popping = (container: HTMLElement): HTMLElement[] => [
  ...container.querySelectorAll<HTMLElement>(".animate-digit-pop"),
];

afterEach(() => {
  vi.restoreAllMocks();
});

describe("PopDigits", () => {
  test("o primeiro número aparece sem animar", () => {
    at(0);
    const { container } = render(<PopDigits value="124" />);

    expect(popping(container)).toStrictEqual([]);
    expect(container.textContent).toBe("124");
  });

  test("um salto depois de o número estar parado sobe só os algarismos que mudaram, da esquerda para a direita", () => {
    at(0);
    const { container, rerender } = render(<PopDigits value="124" />);

    at(1000);
    rerender(<PopDigits value="190" />);

    const digits = popping(container);
    expect(digits.map((digit) => digit.textContent)).toStrictEqual(["9", "0"]);
    expect(digits.map((digit) => digit.style.animationDelay)).toStrictEqual([
      "0ms",
      "30ms",
    ]);
    expect(container.textContent).toBe("190");
  });

  test("um algarismo novo à esquerda também sobe", () => {
    at(0);
    const { container, rerender } = render(<PopDigits value="99" />);

    at(1000);
    rerender(<PopDigits value="100" />);

    expect(popping(container).map((digit) => digit.textContent)).toStrictEqual([
      "1",
      "0",
      "0",
    ]);
  });

  test("uma contagem que corre rápido troca os algarismos sem animar", () => {
    at(0);
    const { container, rerender } = render(<PopDigits value="10" />);

    at(100);
    rerender(<PopDigits value="11" />);
    at(200);
    rerender(<PopDigits value="12" />);

    expect(popping(container)).toStrictEqual([]);
    expect(container.textContent).toBe("12");
  });

  test("depois de uma corrida, um salto que vem de um número parado volta a subir", () => {
    at(0);
    const { container, rerender } = render(<PopDigits value="10" />);

    at(100);
    rerender(<PopDigits value="11" />);
    at(2000);
    rerender(<PopDigits value="64" />);

    expect(popping(container).map((digit) => digit.textContent)).toStrictEqual([
      "6",
      "4",
    ]);
  });

  test("os algarismos ficam em linha, sem bloco próprio: o número não se parte em pedaços", () => {
    at(0);
    const { container, rerender } = render(<PopDigits value="124" />);

    at(1000);
    rerender(<PopDigits value="125" />);

    for (const digit of popping(container)) {
      expect(digit.tagName).toBe("SPAN");
      expect(digit.className).not.toContain("inline-block");
      expect(digit.className).toContain("relative");
    }
  });
});
