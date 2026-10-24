import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";
import { z } from "zod";
import {
  ERROR_CODES,
  errorParamsSchemaByCode,
  isKnownErrorCode,
} from "../errors";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "../../..");
const FIXTURES_DIR = path.resolve(REPO_ROOT, "protocol/fixtures");

const FIXTURE_NAME = /^[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9-]*)*\.jsonl$/;
const SCORE = /score/i;
const INTEGER_LIKE_KEY = /^(0|[1-9][0-9]*)$/;
const UNICODE_ESCAPE = /\\u[0-9a-fA-F]{4}/;
const JSONL_SUFFIX = /\.jsonl$/;
const ABSOLUTE_POSIX = /^\//;
const ABSOLUTE_WINDOWS = /^[A-Za-z]:[\\/]/;
const ABSOLUTE_UNC = /^\\\\/;
const HOME_DIRECTORY = /\/(Users|home)\//;

const RESERVED_TOPIC = "protocol";

const PAYLOAD_MUST_FAIL = new Set([
  "element.describe.method.jsonl",
  "protocol.invalid-payload.jsonl",
]);

const ENVELOPE_MUST_FAIL = new Set(["protocol.discarded-frame.jsonl"]);

const LIFECYCLE_TOPICS = new Set([
  "analysis.cancel",
  "analysis.cancelled",
  "analysis.completed",
  "analysis.failed",
  "analysis.progress",
  "analysis.stage.completed",
  "analysis.start",
  "analysis.started",
  "analysis.status",
  "analysis.warning",
  "cache.invalidated",
  "protocol",
  "protocol.ready",
  "system.ping",
  "system.shutdown",
]);

const protocolModules = import.meta.glob<Record<string, unknown>>(
  ["../**/*.ts", "!../**/__tests__/**"],
  { eager: true }
);

const findProtocolExport = (name: string): unknown => {
  for (const module of Object.values(protocolModules)) {
    if (Object.hasOwn(module, name)) {
      return module[name];
    }
  }
};

interface MethodContract {
  readonly params: z.ZodType;
  readonly requiresRepository: boolean;
  readonly result: z.ZodType | null;
}

const methods = findProtocolExport("METHODS") as
  | Record<string, MethodContract>
  | undefined;
const events = findProtocolExport("EVENTS") as
  | Record<string, z.ZodType>
  | undefined;

interface ExtendableSchema {
  readonly extend: (shape: Record<string, z.ZodType>) => z.ZodType;
}

const protocolMessageSchema = findProtocolExport("protocolMessageSchema") as
  | z.ZodType
  | undefined;
const requestEnvelopeSchema = findProtocolExport("requestEnvelopeSchema") as
  | ExtendableSchema
  | undefined;
const okResponseEnvelopeSchema = findProtocolExport(
  "okResponseEnvelopeSchema"
) as ExtendableSchema | undefined;
const eventEnvelopeSchema = findProtocolExport("eventEnvelopeSchema") as
  | ExtendableSchema
  | undefined;

const methodNames = methods === undefined ? [] : Object.keys(methods).sort();
const eventNames = events === undefined ? [] : Object.keys(events).sort();
const coverageTopics = [...methodNames, ...eventNames].sort();
const resolvableTopics = [...coverageTopics, RESERVED_TOPIC];

const resolveTopic = (
  stem: string,
  topics: readonly string[]
): string | undefined => {
  const segments = stem.split(".");
  for (let take = segments.length; take > 0; take -= 1) {
    const candidate = segments.slice(0, take).join(".");
    if (topics.includes(candidate)) {
      return candidate;
    }
  }
};

interface Frame {
  readonly index: number;
  readonly line: string;
  readonly value: Record<string, unknown>;
}

interface Fixture {
  readonly frames: readonly Frame[];
  readonly name: string;
  readonly stem: string;
  readonly topic: string | undefined;
}

const readFixtures = (): Fixture[] => {
  if (!existsSync(FIXTURES_DIR)) {
    return [];
  }
  const collected: Fixture[] = [];
  for (const name of readdirSync(FIXTURES_DIR).sort()) {
    if (name.startsWith(".") || name === "README.md") {
      continue;
    }
    const raw = readFileSync(path.join(FIXTURES_DIR, name), "utf8");
    const lines = raw.split("\n").filter((line) => line.length > 0);
    const frames: Frame[] = [];
    for (const [index, line] of lines.entries()) {
      let value: Record<string, unknown> | null = null;
      try {
        value = JSON.parse(line) as Record<string, unknown>;
      } catch {
        value = null;
      }
      if (value !== null) {
        frames.push({ index, line, value });
      }
    }
    const stem = name.replace(JSONL_SUFFIX, "");
    collected.push({
      frames,
      name,
      stem,
      topic: resolveTopic(stem, resolvableTopics),
    });
  }
  return collected;
};

const fixtures = readFixtures();
const fixtureNames = fixtures.map((fixture) => fixture.name);
const allFrames = fixtures.flatMap((fixture) =>
  fixture.frames.map((frame) => ({ fixture, frame }))
);

const compareCodeUnits = (left: string, right: string): number => {
  if (left < right) {
    return -1;
  }
  return left > right ? 1 : 0;
};

const canonicalize = (value: unknown): string => {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value) ?? "null";
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalize).join(",")}]`;
  }
  const entries = Object.entries(value as Record<string, unknown>).sort(
    ([left], [right]) => compareCodeUnits(left, right)
  );
  const body = entries
    .map(([key, nested]) => `${JSON.stringify(key)}:${canonicalize(nested)}`)
    .join(",");
  return `{${body}}`;
};

const collectStrings = (value: unknown, into: string[]): string[] => {
  if (typeof value === "string") {
    into.push(value);
  } else if (Array.isArray(value)) {
    for (const item of value) {
      collectStrings(item, into);
    }
  } else if (value !== null && typeof value === "object") {
    for (const [key, nested] of Object.entries(value)) {
      into.push(key);
      collectStrings(nested, into);
    }
  }
  return into;
};

const collectKeys = (value: unknown, into: string[]): string[] => {
  if (Array.isArray(value)) {
    for (const item of value) {
      collectKeys(item, into);
    }
  } else if (value !== null && typeof value === "object") {
    for (const [key, nested] of Object.entries(value)) {
      into.push(key);
      collectKeys(nested, into);
    }
  }
  return into;
};

const collectNumbers = (value: unknown, into: number[]): number[] => {
  if (typeof value === "number") {
    into.push(value);
  } else if (Array.isArray(value)) {
    for (const item of value) {
      collectNumbers(item, into);
    }
  } else if (value !== null && typeof value === "object") {
    for (const nested of Object.values(value)) {
      collectNumbers(nested, into);
    }
  }
  return into;
};

const isAbsolutePathLike = (value: string): boolean =>
  ABSOLUTE_POSIX.test(value) ||
  ABSOLUTE_WINDOWS.test(value) ||
  ABSOLUTE_UNC.test(value) ||
  HOME_DIRECTORY.test(value);

const OPEN_MAP_PATHS = new Set([
  "error.params",
  "payload.counters",
  "payload.error.params",
  "payload.stages.[].counters",
]);

const normalizedPath = (at: readonly (number | string)[]): string =>
  at.map((segment) => (typeof segment === "number" ? "[]" : segment)).join(".");

const isOpenMap = (at: readonly (number | string)[]): boolean =>
  OPEN_MAP_PATHS.has(normalizedPath(at));

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const objectPaths = (
  value: unknown,
  prefix: readonly (number | string)[],
  into: (number | string)[][]
): (number | string)[][] => {
  if (Array.isArray(value)) {
    for (const [index, item] of value.entries()) {
      objectPaths(item, [...prefix, index], into);
    }
  } else if (value !== null && typeof value === "object") {
    into.push([...prefix]);
    for (const [key, nested] of Object.entries(value)) {
      objectPaths(nested, [...prefix, key], into);
    }
  }
  return into;
};

const UNKNOWN_KEY = "__unknown__";

const injectAt = (
  value: unknown,
  at: readonly (number | string)[]
): unknown => {
  if (at.length === 0) {
    return { ...(value as Record<string, unknown>), [UNKNOWN_KEY]: 1 };
  }
  const [head, ...rest] = at;
  if (Array.isArray(value)) {
    return value.map((item, index) =>
      index === head ? injectAt(item, rest) : item
    );
  }
  const clone = { ...(value as Record<string, unknown>) };
  clone[head as string] = injectAt(clone[head as string], rest);
  return clone;
};

const SCALAR_TAGS = new Set([
  "any",
  "bigint",
  "boolean",
  "custom",
  "date",
  "enum",
  "file",
  "int",
  "literal",
  "nan",
  "never",
  "null",
  "number",
  "string",
  "symbol",
  "template_literal",
  "undefined",
  "unknown",
  "void",
]);

interface SchemaNode {
  readonly at: string;
  readonly schema: z.ZodType;
}

interface ZodInternals {
  readonly def: Record<string, unknown>;
}

const walkSchema = (
  schema: z.ZodType,
  at: string,
  into: SchemaNode[],
  seen: Set<unknown>
): SchemaNode[] => {
  if (seen.has(schema)) {
    return into;
  }
  seen.add(schema);
  into.push({ at, schema });
  const { def } = schema as unknown as ZodInternals;
  const tag = def.type as string;
  const child = (next: unknown, suffix: string): void => {
    walkSchema(next as z.ZodType, `${at}${suffix}`, into, seen);
  };

  if (SCALAR_TAGS.has(tag)) {
    return into;
  }
  switch (tag) {
    case "object":
      for (const [key, nested] of Object.entries(
        def.shape as Record<string, z.ZodType>
      )) {
        child(nested, `.${key}`);
      }
      break;
    case "array":
      child(def.element, "[]");
      break;
    case "record":
    case "map":
      child(def.keyType, "{key}");
      child(def.valueType, "{}");
      break;
    case "set":
      child(def.valueType, "{}");
      break;
    case "tuple":
      for (const [index, nested] of (def.items as z.ZodType[]).entries()) {
        child(nested, `[${index}]`);
      }
      if (def.rest !== undefined && def.rest !== null) {
        child(def.rest, "[...]");
      }
      break;
    case "union":
      for (const [index, nested] of (def.options as z.ZodType[]).entries()) {
        child(nested, `|${index}`);
      }
      break;
    case "catch":
    case "default":
    case "nonoptional":
    case "nullable":
    case "optional":
    case "prefault":
    case "promise":
    case "readonly":
      child(def.innerType, "");
      break;
    case "pipe":
      child(def.in, "");
      child(def.out, "");
      break;
    case "lazy":
      child((def.getter as () => z.ZodType)(), "");
      break;
    case "transform":
    case "success":
      break;
    default:
      throw new Error(
        `o caminhador de schema não conhece o contêiner Zod "${tag}" em ${at}`
      );
  }
  return into;
};

const isStrictObject = (schema: z.ZodType): boolean => {
  const { def } = schema as unknown as ZodInternals;
  if (def.type !== "object") {
    return true;
  }
  const catchall = def.catchall as ZodInternals | undefined;
  return catchall?.def?.type === "never";
};

const reachableSchemas = (): SchemaNode[] => {
  const into: SchemaNode[] = [];
  const seen = new Set<unknown>();
  if (methods !== undefined) {
    for (const [name, contract] of Object.entries(methods)) {
      walkSchema(contract.params, `${name}.params`, into, seen);
      if (contract.result !== null) {
        walkSchema(contract.result, `${name}.result`, into, seen);
      }
    }
  }
  if (events !== undefined) {
    for (const [name, payload] of Object.entries(events)) {
      walkSchema(payload, `${name}.payload`, into, seen);
    }
  }
  return into;
};

const composedFrameSchema = (
  fixture: Fixture,
  frame: Frame
): z.ZodType | null => {
  const { topic } = fixture;
  if (topic === undefined || topic === RESERVED_TOPIC) {
    return null;
  }
  const { event, kind } = frame.value;
  if (
    kind === "event" &&
    events !== undefined &&
    eventEnvelopeSchema !== undefined
  ) {
    return typeof event === "string" && Object.hasOwn(events, event)
      ? eventEnvelopeSchema.extend({ payload: events[event] })
      : null;
  }
  if (methods === undefined) {
    return null;
  }
  const contract = methods[topic];
  if (contract === undefined) {
    return null;
  }
  if (kind === "request" && requestEnvelopeSchema !== undefined) {
    return requestEnvelopeSchema.extend({ payload: contract.params });
  }
  if (
    kind === "response" &&
    frame.value.ok === true &&
    contract.result !== null &&
    okResponseEnvelopeSchema !== undefined
  ) {
    return okResponseEnvelopeSchema.extend({ payload: contract.result });
  }
  return null;
};

const fixtureInMemory = (
  name: string,
  values: readonly Record<string, unknown>[]
): Fixture => {
  const stem = name.replace(JSONL_SUFFIX, "");
  return {
    frames: values.map((value, index) => ({
      index,
      line: canonicalize(value),
      value,
    })),
    name,
    stem,
    topic: resolveTopic(stem, resolvableTopics),
  };
};

const frameErrors = (frame: Frame): Record<string, unknown>[] => {
  const { error, payload } = frame.value;
  return [error, isRecord(payload) ? payload.error : undefined].filter(
    isRecord
  );
};

const catalogueViolation = (error: Record<string, unknown>): string | null => {
  const code = String(error.code);
  if (!isKnownErrorCode(code)) {
    return `${code} não está em ERROR_CODES`;
  }
  return errorParamsSchemaByCode[code].safeParse(error.params ?? {}).success
    ? null
    : `params de ${code} não bastam para a frase`;
};

interface UnknownKeyMutation {
  readonly accepted: readonly string[];
  readonly mutants: number;
}

const unknownKeyMutation = (
  fixture: Fixture,
  frame: Frame
): UnknownKeyMutation => {
  const composed = composedFrameSchema(fixture, frame);
  const schema = composed ?? (protocolMessageSchema as z.ZodType);
  const sites =
    composed === null
      ? [[] as (number | string)[]]
      : objectPaths(frame.value, [], []);
  const closed = sites.filter((at) => !isOpenMap(at));
  return {
    accepted: closed
      .filter((at) => schema.safeParse(injectAt(frame.value, at)).success)
      .map(
        (at) =>
          `${fixture.name}:${frame.index + 1} aceitou ${UNKNOWN_KEY} em "${at.join(".")}"`
      ),
    mutants: closed.length,
  };
};

const OPTIONAL_OUTPUT_KEYS = new Set([
  "payload.error.detail",
  "payload.error.origin",
  "payload.error.params",
]);

const PARTIAL_MAP_PATHS = new Set([
  "payload.edges.[].types",
  "payload.items.[].types",
]);

interface MissingKeyMutation {
  readonly accepted: readonly string[];
  readonly mutants: number;

  readonly reached: readonly string[];
}

const objectAt = (
  value: unknown,
  at: readonly (number | string)[]
): Record<string, unknown> =>
  at.reduce<unknown>(
    (node, segment) => (node as Record<number | string, unknown>)[segment],
    value
  ) as Record<string, unknown>;

const deleteAt = (
  value: unknown,
  at: readonly (number | string)[],
  key: string
): unknown => {
  if (at.length === 0) {
    const { [key]: _dropped, ...rest } = value as Record<string, unknown>;
    return rest;
  }
  const [head, ...rest] = at;
  if (Array.isArray(value)) {
    return value.map((item, index) =>
      index === head ? deleteAt(item, rest, key) : item
    );
  }
  const clone = { ...(value as Record<string, unknown>) };
  clone[head as string] = deleteAt(clone[head as string], rest, key);
  return clone;
};

const missingKeyMutation = (
  fixture: Fixture,
  frame: Frame
): MissingKeyMutation => {
  const composed = composedFrameSchema(fixture, frame);
  if (composed === null || frame.value.kind === "request") {
    return { accepted: [], mutants: 0, reached: [] };
  }
  const removals = objectPaths(frame.value, [], [])
    .filter(
      (at) =>
        at[0] === "payload" &&
        !(isOpenMap(at) || PARTIAL_MAP_PATHS.has(normalizedPath(at)))
    )
    .flatMap((at) =>
      Object.keys(objectAt(frame.value, at)).map((key) => ({
        at,
        key,
        keyPath: normalizedPath([...at, key]),
      }))
    )
    .filter(({ keyPath }) => !OPTIONAL_OUTPUT_KEYS.has(keyPath));
  return {
    accepted: removals
      .filter(
        ({ at, key }) =>
          composed.safeParse(deleteAt(frame.value, at, key)).success
      )
      .map(
        ({ keyPath }) =>
          `${fixture.name}:${frame.index + 1} aceitou a falta de "${keyPath}"`
      ),
    mutants: removals.length,
    reached: removals.map(({ keyPath }) => keyPath),
  };
};

interface OpenMapMutation {
  readonly checked: number;
  readonly refused: readonly string[];
}

const openMapMutation = (fixture: Fixture, frame: Frame): OpenMapMutation => {
  const composed = composedFrameSchema(fixture, frame);
  if (composed === null) {
    return { checked: 0, refused: [] };
  }
  const open = objectPaths(frame.value, [], []).filter(isOpenMap);
  return {
    checked: open.length,
    refused: open
      .filter((at) => !composed.safeParse(injectAt(frame.value, at)).success)
      .map(
        (at) =>
          `${fixture.name}: mapa aberto "${at.join(".")}" recusou uma chave nova`
      ),
  };
};

const AN = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

const coveredTopics = new Set(
  fixtures
    .map((fixture) => fixture.topic)
    .filter((topic): topic is string => topic !== undefined)
);
const coverageGap = coverageTopics.filter((topic) => !coveredTopics.has(topic));

const EXPECTED_COVERAGE_GAP = [
  "archaeology.timeline",
  "diagnostics.report",
  "evolution.changeMetrics",
  "evolution.cochange",
  "hotspot.profile",
  "hotspots.ids",
  "hotspots.list",
  "tour",
];

describe("descoberta", () => {
  test("a pasta protocol/fixtures existe e foi lida", () => {
    expect(existsSync(FIXTURES_DIR)).toBe(true);
    expect(fixtureNames).toContain("protocol.ready.mvp.jsonl");
  });

  test("todo arquivo casa <tópico>[.<caso>].jsonl", () => {
    for (const name of fixtureNames) {
      expect(FIXTURE_NAME.test(name), name).toBe(true);
    }
  });

  test("todo nome de fixture resolve para um tópico conhecido", () => {
    for (const fixture of fixtures) {
      expect(fixture.topic, fixture.name).toBeDefined();
    }
  });

  test("toda fixture tem ao menos um quadro", () => {
    for (const fixture of fixtures) {
      expect(fixture.frames.length, fixture.name).toBeGreaterThan(0);
    }
  });

  test("toda linha de toda fixture é JSON válido", () => {
    for (const fixture of fixtures) {
      const lines = readFileSync(path.join(FIXTURES_DIR, fixture.name), "utf8")
        .split("\n")
        .filter((line) => line.length > 0);
      expect(fixture.frames.length, fixture.name).toBe(lines.length);
    }
  });
});

describe("o registro do protocolo", () => {
  test("a descoberta encontra METHODS e EVENTS em src/protocol", () => {
    expect(
      methods,
      "METHODS não foi exportado por nenhum módulo de src/protocol"
    ).toBeDefined();
    expect(
      events,
      "EVENTS não foi exportado por nenhum módulo de src/protocol"
    ).toBeDefined();
  });

  test("o registro tem exatamente 20 métodos e 9 eventos", () => {
    expect(methodNames).toHaveLength(20);
    expect(eventNames).toHaveLength(9);
  });

  test("o caminhador percorre todo schema alcançável sem contêiner desconhecido", () => {
    expect(() => reachableSchemas()).not.toThrow();
    expect(reachableSchemas().length).toBeGreaterThan(0);
  });
});

describe("validação", () => {
  test("todo quadro satisfaz o schema de envelope", () => {
    expect(
      protocolMessageSchema,
      "protocolMessageSchema não foi exportado"
    ).toBeDefined();
    for (const { fixture, frame } of allFrames) {
      if (ENVELOPE_MUST_FAIL.has(fixture.name)) {
        continue;
      }
      const result = (protocolMessageSchema as z.ZodType).safeParse(
        frame.value
      );
      expect(result.success, `${fixture.name}:${frame.index + 1}`).toBe(true);
    }
  });

  test("a fixture de quadro descartado reprova no envelope", () => {
    expect(protocolMessageSchema).toBeDefined();
    let checked = 0;
    for (const { fixture, frame } of allFrames) {
      if (!ENVELOPE_MUST_FAIL.has(fixture.name)) {
        continue;
      }
      checked += 1;
      expect(
        (protocolMessageSchema as z.ZodType).safeParse(frame.value).success,
        fixture.name
      ).toBe(false);
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("todo quadro satisfaz o schema composto do seu tópico", () => {
    let checked = 0;
    for (const { fixture, frame } of allFrames) {
      if (
        ENVELOPE_MUST_FAIL.has(fixture.name) ||
        PAYLOAD_MUST_FAIL.has(fixture.name)
      ) {
        continue;
      }
      const composed = composedFrameSchema(fixture, frame);
      if (composed === null) {
        continue;
      }
      checked += 1;
      const result = composed.safeParse(frame.value);
      expect(result.success, `${fixture.name}:${frame.index + 1}`).toBe(true);
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("um evento dentro da fixture de um método é conferido pelo schema do próprio evento", () => {
    const tick = {
      analysisId: AN,
      completed: 0,
      stage: "SOURCE_ANALYSIS",
      total: 10,
      unit: "JAVA_FILES",
    };
    const fixture = fixtureInMemory("analysis.start.ok.jsonl", [
      { event: "analysis.progress", kind: "event", payload: tick, protocol: 1 },
      {
        event: "analysis.progress",
        kind: "event",
        payload: {
          ...tick,
          analysisId: "not-an-id",
          completed: -5,
          stage: "NOT_A_STAGE",
        },
        protocol: 1,
      },
    ]);
    const [good, bad] = fixture.frames;

    expect(fixture.topic).toBe("analysis.start");
    expect(
      (protocolMessageSchema as z.ZodType).safeParse(bad.value).success
    ).toBe(true);
    expect(
      composedFrameSchema(fixture, good)?.safeParse(good.value).success
    ).toBe(true);
    expect(
      composedFrameSchema(fixture, bad)?.safeParse(bad.value).success
    ).toBe(false);
    expect(unknownKeyMutation(fixture, good)).toStrictEqual({
      accepted: [],
      mutants: 2,
    });
  });

  test("a fixture de payload inválido reprova no schema do seu método", () => {
    expect(methods).toBeDefined();
    for (const fixture of fixtures) {
      if (!PAYLOAD_MUST_FAIL.has(fixture.name)) {
        continue;
      }
      const request = fixture.frames.find(
        (frame) => frame.value.kind === "request"
      );
      expect(request, fixture.name).toBeDefined();
      const { method } = (request as Frame).value;
      const contract = (methods as Record<string, MethodContract>)[
        method as string
      ];
      expect(contract, method as string).toBeDefined();
      expect(
        contract.params.safeParse((request as Frame).value.payload).success
      ).toBe(false);
    }
  });

  test("todo erro de fixture carrega um code que existe em ERROR_CODES", () => {
    const checked = allFrames.flatMap(({ fixture, frame }) =>
      frameErrors(frame).map((error) => ({ error, fixture, frame }))
    );
    for (const { error, fixture, frame } of checked) {
      expect(
        catalogueViolation(error),
        `${fixture.name}:${frame.index + 1}`
      ).toBeNull();
    }
    expect(checked.length).toBeGreaterThan(0);
  });

  test("um code dentro do payload de um evento é conferido contra o catálogo", () => {
    const failed = (
      error: Record<string, unknown>
    ): Record<string, unknown> => ({
      event: "analysis.failed",
      kind: "event",
      payload: { analysisId: AN, error },
      protocol: 1,
    });
    const fixture = fixtureInMemory("analysis.failed.bogus.jsonl", [
      failed({ code: "NOT_IN_THE_CATALOGUE", origin: "analyzer", params: {} }),
      failed({
        code: "CACHE_UNAVAILABLE",
        origin: "analyzer",
        params: { cause: "DISK_ON_FIRE" },
      }),
      failed({ code: "INTERNAL_UNEXPECTED", origin: "analyzer", params: {} }),
    ]);
    const [unknown] = fixture.frames;

    expect(
      composedFrameSchema(fixture, unknown)?.safeParse(unknown.value).success
    ).toBe(true);
    expect(
      fixture.frames.flatMap(frameErrors).map(catalogueViolation)
    ).toStrictEqual([
      "NOT_IN_THE_CATALOGUE não está em ERROR_CODES",
      "params de CACHE_UNAVAILABLE não bastam para a frase",
      null,
    ]);
  });
});

describe("chave desconhecida", () => {
  test("uma chave a mais em qualquer objeto de qualquer quadro reprova", () => {
    expect(protocolMessageSchema).toBeDefined();
    const mutations = allFrames
      .filter(({ fixture }) => !ENVELOPE_MUST_FAIL.has(fixture.name))
      .map(({ fixture, frame }) => unknownKeyMutation(fixture, frame));

    expect(mutations.flatMap((mutation) => mutation.accepted)).toStrictEqual(
      []
    );
    expect(
      mutations.reduce((sum, mutation) => sum + mutation.mutants, 0)
    ).toBeGreaterThan(0);
  });

  test("uma chave a mais em um mapa aberto continua passando", () => {
    const mutations = allFrames.map(({ fixture, frame }) =>
      openMapMutation(fixture, frame)
    );

    expect(mutations.flatMap((mutation) => mutation.refused)).toStrictEqual([]);
    expect(
      mutations.reduce((sum, mutation) => sum + mutation.checked, 0)
    ).toBeGreaterThan(0);
  });

  test("o `counters` de cada estágio do relatório de status é mapa aberto", () => {
    const request = "req_01JQ8ZD3B2C3D4E5F6G7H8J9K2";
    const fixture = fixtureInMemory("analysis.status.completed.jsonl", [
      {
        id: request,
        kind: "response",
        ok: true,
        payload: {
          analysisId: AN,
          completeness: {
            historyTruncatedAtCommits: null,
            parseFailures: 0,
            shallowClone: false,
            skippedFiles: 0,
            totalFiles: 3,
          },
          error: null,
          finishedAt: "2026-10-21T12:00:01Z",
          headCommitId: null,
          stages: [
            {
              counters: { files: 3 },
              durationMs: 0,
              stage: "SOURCE_ANALYSIS",
              startedAt: "2026-10-21T12:00:00Z",
              status: "COMPLETED",
            },
          ],
          startedAt: "2026-10-21T12:00:00Z",
          status: "COMPLETED",
        },
        protocol: 1,
      },
    ]);
    const [response] = fixture.frames;

    expect(
      composedFrameSchema(fixture, response)?.safeParse(response.value).success
    ).toBe(true);
    expect(unknownKeyMutation(fixture, response).accepted).toStrictEqual([]);
    expect(openMapMutation(fixture, response)).toStrictEqual({
      checked: 1,
      refused: [],
    });
  });

  test("uma chave a menos em qualquer objeto da saída do analisador reprova: nulo, nunca ausente", () => {
    const mutations = allFrames
      .filter(({ fixture }) => !ENVELOPE_MUST_FAIL.has(fixture.name))
      .map(({ fixture, frame }) => missingKeyMutation(fixture, frame));

    expect(mutations.flatMap((mutation) => mutation.accepted)).toStrictEqual(
      []
    );
    expect(
      mutations.reduce((sum, mutation) => sum + mutation.mutants, 0)
    ).toBeGreaterThan(0);
  });

  test("a varredura de chave ausente desce até os objetos aninhados dos payloads do inspetor", () => {
    const reached = new Set(
      allFrames.flatMap(
        ({ fixture, frame }) => missingKeyMutation(fixture, frame).reached
      )
    );

    for (const keyPath of [
      "payload.evidence.[].filePath",
      "payload.evidence.[].fromMethod",
      "payload.evidence.[].line",
      "payload.evidence.[].toMethod",
      "payload.evidence.[].toMethod.ownerId",
      "payload.graph.cycle",
      "payload.items.[].packageId",
      "payload.methods.[].cc",
      "payload.methods.[].lines",
      "payload.metrics.ccP90",
      "payload.totals.outExternal",
    ]) {
      expect(reached.has(keyPath), keyPath).toBe(true);
    }
  });

  test("a chave opcional do erro de um evento continua opcional", () => {
    const optionalSeen = allFrames.flatMap(({ fixture, frame }) => {
      const composed = composedFrameSchema(fixture, frame);
      const error = isRecord(frame.value.payload)
        ? frame.value.payload.error
        : undefined;
      if (
        composed === null ||
        frame.value.kind !== "event" ||
        !isRecord(error)
      ) {
        return [];
      }
      return Object.keys(error)
        .filter((key) => OPTIONAL_OUTPUT_KEYS.has(`payload.error.${key}`))
        .map((key) => ({
          accepted: composed.safeParse(
            deleteAt(frame.value, ["payload", "error"], key)
          ).success,
          at: `${fixture.name}:${frame.index + 1} payload.error.${key}`,
        }));
    });

    expect(
      optionalSeen.filter(({ accepted }) => !accepted).map(({ at }) => at)
    ).toStrictEqual([]);
    expect(optionalSeen.length).toBeGreaterThan(0);
  });

  test("todo objeto alcançável a partir do registro é estrito", () => {
    const permissive = reachableSchemas()
      .filter((node) => !isStrictObject(node.schema))
      .map((node) => node.at);
    expect(permissive).toStrictEqual([]);
  });

  test("a auditoria sabe reprovar um objeto permissivo sob cada invólucro", () => {
    const canary = z.strictObject({
      underArray: z.array(z.object({ a: z.number() })),
      underOptional: z.object({ b: z.number() }).optional(),
      underRecord: z.record(z.string().max(8), z.object({ c: z.number() })),
      underUnion: z.union([z.object({ d: z.number() }), z.string().max(8)]),
    });
    const permissive = walkSchema(canary, "canary", [], new Set()).filter(
      (node) => !isStrictObject(node.schema)
    );
    expect(permissive).toHaveLength(4);
  });

  test("z.toJSONSchema não distingue estrito de permissivo", () => {
    const loose = z.toJSONSchema(z.object({ a: z.number() }));
    const strict = z.toJSONSchema(z.strictObject({ a: z.number() }));
    expect(loose.additionalProperties).toBe(false);
    expect(strict.additionalProperties).toBe(false);
  });
});

describe("forma canônica", () => {
  test("nenhuma chave de fixture parece um inteiro", () => {
    for (const { fixture, frame } of allFrames) {
      for (const key of collectKeys(frame.value, [])) {
        expect(
          INTEGER_LIKE_KEY.test(key),
          `${fixture.name}: chave "${key}"`
        ).toBe(false);
      }
    }
  });

  test("cada quadro é idêntico à sua forma canônica", () => {
    for (const { fixture, frame } of allFrames) {
      expect(
        canonicalize(frame.value),
        `${fixture.name}:${frame.index + 1}`
      ).toBe(frame.line);
    }
  });

  test("nenhum quadro usa escape \\uXXXX", () => {
    for (const { fixture, frame } of allFrames) {
      expect(
        UNICODE_ESCAPE.test(frame.line),
        `${fixture.name}:${frame.index + 1}`
      ).toBe(false);
    }
  });

  test("toda string de fixture está em NFC", () => {
    for (const { fixture, frame } of allFrames) {
      for (const value of collectStrings(frame.value, [])) {
        expect(value.normalize("NFC"), `${fixture.name}: "${value}"`).toBe(
          value
        );
      }
    }
  });

  test("todo quadro de um tópico de ciclo de vida tem só número inteiro", () => {
    let checked = 0;
    for (const { fixture, frame } of allFrames) {
      if (fixture.topic === undefined || !LIFECYCLE_TOPICS.has(fixture.topic)) {
        continue;
      }
      checked += 1;
      for (const value of collectNumbers(frame.value, [])) {
        expect(Number.isInteger(value), `${fixture.name}: ${value}`).toBe(true);
      }
    }
    expect(checked).toBeGreaterThan(0);
  });
});

describe("cobertura", () => {
  test("a lacuna é exatamente a baseline declarada", () => {
    expect(coverageGap).toStrictEqual(EXPECTED_COVERAGE_GAP);
  });

  test("nenhum tópico já coberto continua declarado como dívida", () => {
    for (const topic of coveredTopics) {
      expect(EXPECTED_COVERAGE_GAP, topic).not.toContain(topic);
    }
  });

  test("todos os 9 eventos têm fixture", () => {
    for (const name of eventNames) {
      expect(coveredTopics.has(name), name).toBe(true);
    }
  });
});

describe("varreduras", () => {
  test("nenhuma string de fixture é um caminho absoluto", () => {
    for (const { fixture, frame } of allFrames) {
      for (const value of collectStrings(frame.value, [])) {
        expect(isAbsolutePathLike(value), `${fixture.name}: "${value}"`).toBe(
          false
        );
      }
    }
  });

  test("a varredura reconhece as quatro formas e poupa caminho relativo", () => {
    for (const bad of [
      "/Users/x/y",
      "/home/x/y",
      "C:\\Users\\x",
      "C:/Users/x",
      "\\\\srv\\share",
    ]) {
      expect(isAbsolutePathLike(bad), bad).toBe(true);
    }
    for (const good of [
      "core/src/main/java/br/pucpr/legacy/core/Catalog.java",
      "pkg:br.pucpr.legacy.core",
      "req_home123",
      "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W",
    ]) {
      expect(isAbsolutePathLike(good), good).toBe(false);
    }
  });

  test("nenhum nome de campo de fixture casa /score/i", () => {
    for (const { fixture, frame } of allFrames) {
      for (const key of collectKeys(frame.value, [])) {
        expect(SCORE.test(key), `${fixture.name}: chave "${key}"`).toBe(false);
      }
    }
  });

  test("nenhum nome de campo dos schemas casa /score/i", () => {
    const offenders: string[] = [];
    for (const node of reachableSchemas()) {
      const { def } = node.schema as unknown as ZodInternals;
      if (def.type !== "object") {
        continue;
      }
      for (const key of Object.keys(def.shape as Record<string, unknown>)) {
        if (SCORE.test(key)) {
          offenders.push(`${node.at}.${key}`);
        }
      }
    }
    expect(offenders).toStrictEqual([]);
  });

  test("nenhum membro de enum do protocolo casa /score/i", () => {
    const offenders: string[] = [];
    for (const [file, module] of Object.entries(protocolModules)) {
      for (const [name, value] of Object.entries(module)) {
        if (!(value instanceof z.ZodEnum)) {
          continue;
        }
        for (const option of value.options) {
          if (SCORE.test(String(option))) {
            offenders.push(`${file}: ${name}.${String(option)}`);
          }
        }
      }
    }
    expect(offenders).toStrictEqual([]);
  });

  test("nenhum payload do registro tem chave repositoryPath", () => {
    const offenders = reachableSchemas()
      .filter((node) => {
        const { def } = node.schema as unknown as ZodInternals;
        if (def.type !== "object") {
          return false;
        }
        return Object.hasOwn(
          def.shape as Record<string, unknown>,
          "repositoryPath"
        );
      })
      .map((node) => node.at);
    expect(offenders).toStrictEqual([]);
  });
});

describe("estritude fora do fio", () => {
  test("errorParamsSchemaByCode é permissivo de propósito", () => {
    const sample = errorParamsSchemaByCode.PROTO_UNKNOWN_METHOD;
    expect(
      sample.safeParse({ method: "graph.slice", sobrando: 1 }).success
    ).toBe(true);
    expect(ERROR_CODES).toHaveLength(34);
  });
});
