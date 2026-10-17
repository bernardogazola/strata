import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { SkipToOutline } from "@/features/shell/skip-to-outline";
import { t } from "@/i18n/messages";

describe("SkipToOutline", () => {
  test("é um <button type=button> com o nome do catálogo, e não um link", () => {
    render(<SkipToOutline onSkip={vi.fn()} />);

    const skip = screen.getByRole("button", { name: t.shell.skipToOutline });
    expect(skip.tagName).toBe("BUTTON");
    expect(skip.getAttribute("type")).toBe("button");
    expect(screen.queryByRole("link")).toBeNull();
  });

  test("fica fora da vista até receber foco pelo teclado", () => {
    render(<SkipToOutline onSkip={vi.fn()} />);

    const classes = screen
      .getByRole("button", { name: t.shell.skipToOutline })
      .className.split(" ");
    expect(classes).toStrictEqual(
      expect.arrayContaining([
        "sr-only",
        "focus-visible:not-sr-only",
        "focus-visible:absolute",
      ])
    );
  });

  test("ativar chama onSkip uma vez", () => {
    const onSkip = vi.fn<() => void>();
    render(<SkipToOutline onSkip={onSkip} />);

    fireEvent.click(
      screen.getByRole("button", { name: t.shell.skipToOutline })
    );

    expect(onSkip).toHaveBeenCalledTimes(1);
  });
});
