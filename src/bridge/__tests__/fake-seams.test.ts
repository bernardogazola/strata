import { afterEach, describe, expect, test, vi } from "vitest";
import { type FakeEvent, fakeRequest, fakeSubscribe } from "@/bridge/fake";
import startedSource from "../../../protocol/fixtures/analysis.started.ok.jsonl?raw";

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const payloadOf = (source: string): unknown => {
  const [line = ""] = source.trim().split("\n");
  return (JSON.parse(line) as { payload: unknown }).payload;
};

const STARTED = payloadOf(startedSource);

let unsubscribers: (() => void)[] = [];

const listen = (): FakeEvent[] => {
  const seen: FakeEvent[] = [];
  unsubscribers = [
    ...unsubscribers,
    fakeSubscribe((event) => seen.push(event)),
  ];
  return seen;
};

const emit = (event: string, payload: unknown): number => {
  const seam = window.__STRATA_FAKE_EMIT__;
  if (seam === undefined) {
    throw new Error("window.__STRATA_FAKE_EMIT__ não está instalado");
  }
  return seam(event, payload);
};

const settle = async (method: string, payload: unknown): Promise<void> => {
  await fakeRequest(method, payload).catch(() => undefined);
};

afterEach(() => {
  for (const unsubscribe of unsubscribers) {
    unsubscribe();
  }
  unsubscribers = [];
  vi.unstubAllEnvs();
  vi.resetModules();
});

test("o Vitest roda sob DEV, que é onde as duas costuras existem", () => {
  expect(import.meta.env.DEV).toBe(true);
});

describe("window.__STRATA_FAKE_EMIT__", () => {
  test("depois que o bridge falso assina, entrega o evento a todo ouvinte e devolve quantos receberam", () => {
    const first = listen();
    const second = listen();

    const delivered = emit("analysis.started", STARTED);

    expect(delivered).toBe(2);
    expect(first).toStrictEqual([
      { event: "analysis.started", payload: STARTED },
    ]);
    expect(second).toStrictEqual(first);
  });

  test("a contagem acompanha quem desassina, até zero", () => {
    listen();
    listen();
    const [unsubscribeFirst, unsubscribeSecond] = unsubscribers;

    unsubscribeFirst?.();
    const afterOne = emit("analysis.started", STARTED);
    unsubscribeSecond?.();
    const afterBoth = emit("analysis.started", STARTED);

    expect([afterOne, afterBoth]).toStrictEqual([1, 0]);
  });

  test("um payload que não passa no schema do evento lança, e nenhum ouvinte recebe nada", () => {
    const seen = listen();
    const valid = emit("analysis.started", STARTED);

    expect(() =>
      emit("analysis.started", { analysisId: "an_minúsculo" })
    ).toThrow();
    expect(valid).toBe(1);
    expect(seen).toStrictEqual([
      { event: "analysis.started", payload: STARTED },
    ]);
  });

  test("um evento fora do registro lança, e nenhum ouvinte recebe nada", () => {
    const seen = listen();
    const valid = emit("analysis.started", STARTED);

    expect(() => emit("analysis.invented", { analysisId: AN })).toThrow();
    expect(valid).toBe(1);
    expect(seen).toStrictEqual([
      { event: "analysis.started", payload: STARTED },
    ]);
  });

  test("fora do DEV o bridge falso não instala a costura", async () => {
    vi.stubGlobal("__STRATA_FAKE_EMIT__", undefined);
    vi.stubEnv("DEV", false);
    vi.resetModules();
    const fresh = await import("@/bridge/fake");

    fresh.fakeSubscribe(() => undefined)();

    expect(window.__STRATA_FAKE_EMIT__).toBeUndefined();
  });
});

describe("window.__STRATA_FAKE_LOG__", () => {
  test("sem o array criado pelo teste, nada é registrado e o global fica indefinido", async () => {
    await settle("system.ping", {});

    expect(window.__STRATA_FAKE_LOG__).toBeUndefined();
  });

  test("criado o array, todo pedido entra no fim, recusado ou não, sempre em um array novo", async () => {
    vi.stubGlobal("__STRATA_FAKE_LOG__", []);
    const initial = window.__STRATA_FAKE_LOG__;
    const unknownElement = { analysisId: AN, elementId: "type:fora.DoMundo" };
    const invalidSlice = { analysisId: AN, level: "GALAXY" };

    await settle("system.ping", {});
    const afterPing = window.__STRATA_FAKE_LOG__;
    await settle("analysis.invented", { analysisId: AN });
    await settle("graph.slice", invalidSlice);
    await settle("element.describe", unknownElement);

    expect(window.__STRATA_FAKE_LOG__).toStrictEqual([
      { method: "system.ping", payload: {} },
      { method: "analysis.invented", payload: { analysisId: AN } },
      { method: "graph.slice", payload: invalidSlice },
      { method: "element.describe", payload: unknownElement },
    ]);
    expect(initial).toStrictEqual([]);
    expect(afterPing).not.toBe(initial);
    expect(afterPing).toHaveLength(1);
    expect(window.__STRATA_FAKE_LOG__).not.toBe(afterPing);
  });

  test("fora do DEV, nem com o array criado o pedido é registrado", async () => {
    vi.stubEnv("DEV", false);
    vi.stubGlobal("__STRATA_FAKE_LOG__", []);
    const initial = window.__STRATA_FAKE_LOG__;

    await settle("system.ping", {});

    expect(window.__STRATA_FAKE_LOG__).toBe(initial);
    expect(initial).toStrictEqual([]);
  });
});
