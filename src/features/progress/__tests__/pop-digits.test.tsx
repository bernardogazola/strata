import { fireEvent, render } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import { PopDigits } from "@/features/progress/pop-digits";

const rising = (container: HTMLElement): HTMLElement[] => [
  ...container.querySelectorAll<HTMLElement>(".animate-digit-pop"),
];

const settle = (container: HTMLElement): void => {
  for (const digit of rising(container)) {
    fireEvent.animationEnd(digit);
  }
};

describe("PopDigits", () => {
  test("o primeiro número aparece sem animar", () => {
    const { container } = render(<PopDigits value="124" />);

    expect(rising(container)).toStrictEqual([]);
    expect(container.textContent).toBe("124");
  });

  test("uma troca sobe só os algarismos que mudaram, da esquerda para a direita", () => {
    const { container, rerender } = render(<PopDigits value="124" />);

    rerender(<PopDigits value="190" />);

    const digits = rising(container);
    expect(digits.map((digit) => digit.textContent)).toStrictEqual(["9", "0"]);
    expect(digits.map((digit) => digit.style.animationDelay)).toStrictEqual([
      "0ms",
      "30ms",
    ]);
    expect(container.textContent).toBe("190");
  });

  test("um algarismo novo à esquerda também sobe", () => {
    const { container, rerender } = render(<PopDigits value="99" />);

    rerender(<PopDigits value="100" />);

    expect(rising(container).map((digit) => digit.textContent)).toStrictEqual([
      "1",
      "0",
      "0",
    ]);
  });

  test("toda troca anima, por mais perto que venha da anterior", () => {
    const { container, rerender } = render(<PopDigits value="450" />);

    rerender(<PopDigits value="519" />);
    settle(container);
    rerender(<PopDigits value="561" />);

    expect(rising(container).map((digit) => digit.textContent)).toStrictEqual([
      "6",
      "1",
    ]);
  });

  test("um algarismo que ainda sobe não recomeça: troca por baixo e segue a mesma subida", () => {
    const { container, rerender } = render(<PopDigits value="10" />);

    rerender(<PopDigits value="11" />);
    const [units] = rising(container);
    rerender(<PopDigits value="12" />);

    expect(rising(container)).toStrictEqual([units]);
    expect(units?.textContent).toBe("2");
    expect(container.textContent).toBe("12");
  });

  test("quando a subida termina, o algarismo volta a ser texto", () => {
    const { container, rerender } = render(<PopDigits value="124" />);

    rerender(<PopDigits value="125" />);
    settle(container);

    expect(rising(container)).toStrictEqual([]);
    expect(container.querySelector("span")).toBeNull();
    expect(container.textContent).toBe("125");
  });

  test("os algarismos ficam em linha, sem bloco próprio: o número não se parte em pedaços", () => {
    const { container, rerender } = render(<PopDigits value="124" />);

    rerender(<PopDigits value="125" />);

    for (const digit of rising(container)) {
      expect(digit.tagName).toBe("SPAN");
      expect(digit.className).not.toContain("inline-block");
      expect(digit.className).toContain("relative");
    }
  });
});
