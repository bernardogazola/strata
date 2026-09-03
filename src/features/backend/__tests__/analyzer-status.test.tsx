import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, test } from "vitest";
import { AnalyzerStatus } from "@/features/backend/analyzer-status";
import { t } from "@/i18n/messages";
import { type BackendState, useAppStore } from "@/state/app-store";

const EXIT_CODE = /70/;
const initialStore = useAppStore.getState();

beforeEach(() => {
  useAppStore.setState(initialStore, true);
});

const renderWith = (backend: BackendState) => {
  useAppStore.setState({ backend });
  render(<AnalyzerStatus />);
};

describe("AnalyzerStatus", () => {
  test("READY mostra a string que o smoke do pacote procura", () => {
    renderWith({ phase: "READY", protocolVersion: 1 });
    expect(screen.getByText(t.backend.READY)).toBeDefined();
    expect(t.backend.READY).toBe("Analisador pronto");
  });

  test("cada fase tem o seu rótulo, e nenhuma cai em vazio", () => {
    const phases: readonly BackendState[] = [
      { phase: "STARTING" },
      { phase: "READY", protocolVersion: 1 },
      { exitCode: 70, phase: "CRASHED" },
      { expected: 1, phase: "INCOMPATIBLE", received: 2 },
    ];
    for (const backend of phases) {
      useAppStore.setState({ backend });
      const view = render(<AnalyzerStatus />);
      expect(
        view.getByText(new RegExp(t.backend[backend.phase]))
      ).toBeDefined();
      view.unmount();
    }
  });

  test("só CRASHED oferece Reiniciar, e o código de saída aparece", () => {
    renderWith({ exitCode: 70, phase: "CRASHED" });
    expect(
      screen.getByRole("button", { name: t.repository.restart })
    ).toBeDefined();
    expect(screen.getByText(EXIT_CODE)).toBeDefined();
  });

  test("INCOMPATIBLE é terminal e não oferece Reiniciar", () => {
    renderWith({ expected: 1, phase: "INCOMPATIBLE", received: 2 });
    expect(
      screen.queryByRole("button", { name: t.repository.restart })
    ).toBeNull();
  });

  test("STARTING e READY também não oferecem Reiniciar", () => {
    for (const backend of [
      { phase: "STARTING" } as const,
      { phase: "READY", protocolVersion: 1 } as const,
    ]) {
      useAppStore.setState({ backend });
      const view = render(<AnalyzerStatus />);
      expect(
        view.queryByRole("button", { name: t.repository.restart })
      ).toBeNull();
      view.unmount();
    }
  });
});
