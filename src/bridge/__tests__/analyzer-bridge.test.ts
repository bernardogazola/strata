import { describe, expect, test } from "vitest";
import {
  BridgeError,
  REPOSITORY_METHODS,
  toProtocolError,
} from "@/bridge/analyzer";
import {
  ANALYSIS_LIFECYCLE,
  ANALYSIS_PROGRESS,
  ANALYSIS_STAGE,
  ANALYSIS_WARNING,
  ANALYZER_STATE,
  CACHE_INVALIDATED,
  FORWARDED_CHANNELS,
} from "@/bridge/contracts";
import { t } from "@/i18n/messages";
import { METHODS, PROTOCOL_METHODS } from "@/protocol/registry";
import {
  backendStateSchema,
  MAX_RETAINED_WARNINGS,
  useAppStore,
} from "@/state/app-store";

describe("a lista de métodos de repositório", () => {
  test("é exatamente o que o registro marca como requiresRepository", () => {
    const fromRegistry = PROTOCOL_METHODS.filter(
      (method) => METHODS[method].requiresRepository
    ).sort();
    expect([...REPOSITORY_METHODS].sort()).toStrictEqual(fromRegistry);
  });
});

describe("os rótulos das fases do backend", () => {
  test("cobrem exatamente as fases que o schema aceita", () => {
    const phases = backendStateSchema.options
      .map((option) => option.shape.phase.value)
      .sort();
    expect(Object.keys(t.backend).sort()).toStrictEqual(phases);
    for (const phase of phases) {
      expect(t.backend[phase as keyof typeof t.backend].length).toBeGreaterThan(
        0
      );
    }
  });
});

describe("normalização de erro", () => {
  test("uma rejeição em forma de ProtocolError passa intacta", () => {
    const wire = { code: "REPO_HANDLE_UNKNOWN", origin: "shell" as const };
    expect(toProtocolError(wire)).toStrictEqual(wire);
  });

  test("uma string crua vira INTERNAL_UNEXPECTED com origem no frontend", () => {
    const normalized = toProtocolError(
      "invalid args `handle` for command `close_repository`"
    );
    expect(normalized.code).toBe("INTERNAL_UNEXPECTED");
    expect(normalized.origin).toBe("frontend");
    expect(normalized.detail).toContain("close_repository");
  });

  test("um BridgeError devolve o erro que carrega, sem embrulhar de novo", () => {
    const inner = { code: "PROC_ANALYZER_NOT_READY", origin: "shell" as const };
    expect(toProtocolError(new BridgeError(inner))).toStrictEqual(inner);
  });
});

describe("os canais", () => {
  test("são os seis nomes documentados, cinco deles encaminhados", () => {
    expect(ANALYZER_STATE).toBe("strata://analyzer-state");
    expect([...FORWARDED_CHANNELS]).toStrictEqual([
      ANALYSIS_PROGRESS,
      ANALYSIS_STAGE,
      ANALYSIS_LIFECYCLE,
      ANALYSIS_WARNING,
      CACHE_INVALIDATED,
    ]);
    expect(FORWARDED_CHANNELS).not.toContain(ANALYZER_STATE);
  });
});

describe("o teto da amostra de avisos", () => {
  test("para no teto em vez de crescer sem limite", () => {
    const initial = useAppStore.getState();
    useAppStore.setState(initial, true);
    const { appendWarning } = useAppStore.getState();
    for (let n = 0; n < MAX_RETAINED_WARNINGS + 50; n += 1) {
      appendWarning({
        analysisId: "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W",
        error: { code: "SOURCE_PARSE_FAILED" },
      });
    }
    expect(useAppStore.getState().warnings).toHaveLength(MAX_RETAINED_WARNINGS);
    useAppStore.setState(initial, true);
  });

  test("o teto é 500", () => {
    expect(MAX_RETAINED_WARNINGS).toBe(500);
  });
});
