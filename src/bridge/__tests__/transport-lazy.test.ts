import { afterEach, describe, expect, test, vi } from "vitest";
import type { RawFrame } from "@/bridge/transport";
import readySource from "../../../protocol/fixtures/protocol.ready.mvp.jsonl?raw";
import transportSource from "../transport.ts?raw";

const payloadOf = (source: string): unknown => {
  const [line = ""] = source.trim().split("\n");
  return (JSON.parse(line) as { payload: unknown }).payload;
};

const STATIC_FAKE_IMPORT =
  /^\s*(?:import(?!\s+type\b)|export)\b[^;]*?\bfrom\s*["']\.\/fake(?:\/index(?:\.ts)?)?["']/m;
const SIDE_EFFECT_FAKE_IMPORT =
  /^\s*import\s*["']\.\/fake(?:\/index(?:\.ts)?)?["']/m;
const DYNAMIC_FAKE_IMPORT =
  /\bimport\(\s*["']\.\/fake(?:\/index(?:\.ts)?)?["']\s*\)/;

const freshModules = async () => {
  vi.resetModules();
  const { transport } = await import("@/bridge/transport");
  const fake = await import("@/bridge/fake");
  return { fake, transport };
};

afterEach(() => {
  vi.doUnmock("@/bridge/fake");
  vi.resetModules();
});

describe("transport.ts no fonte", () => {
  test("não importa o bridge falso estaticamente", () => {
    expect(transportSource).not.toMatch(STATIC_FAKE_IMPORT);
    expect(transportSource).not.toMatch(SIDE_EFFECT_FAKE_IMPORT);
  });

  test("carrega o bridge falso com um import dinâmico", () => {
    expect(transportSource).toMatch(DYNAMIC_FAKE_IMPORT);
  });
});

describe("transport.ts em execução", () => {
  test("sob o Tauri, carregar o transporte não carrega o bridge falso", async () => {
    vi.stubGlobal("__TAURI_INTERNALS__", {});
    let loads = 0;
    vi.doMock("@/bridge/fake", async (importOriginal) => {
      loads += 1;
      return await importOriginal();
    });
    vi.resetModules();

    await import("@/bridge/transport");

    expect(loads).toBe(0);
  });

  test("no navegador, o transporte responde um pedido e entrega um evento pelo bridge falso", async () => {
    const { fake, transport } = await freshModules();
    const frames: RawFrame[] = [];

    const stop = await transport.subscribe((frame) => frames.push(frame));
    const answer = await transport.request("system.ping", {});
    const delivered = fake.fakeEmit("protocol.ready");
    stop();
    fake.fakeEmit("protocol.ready");

    expect(answer).toStrictEqual(await fake.fakeRequest("system.ping", {}));
    expect(delivered).toBe(true);
    expect(frames).toStrictEqual([
      {
        kind: "forwarded",
        value: { event: "protocol.ready", payload: payloadOf(readySource) },
      },
    ]);
  });
});
