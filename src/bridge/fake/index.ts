import {
  eventEnvelopeSchema,
  okResponseEnvelopeSchema,
  type ProtocolEventName,
  type ProtocolMethod,
  requestEnvelopeSchema,
} from "@/protocol/envelope";
import type { ProtocolError } from "@/protocol/errors";
import { EVENTS, METHODS, PROTOCOL_METHODS } from "@/protocol/registry";

const JSONL_SUFFIX = /\.jsonl$/;
const PATH_STEM = /([^/]+)\.jsonl$/;

const FIXTURE_SOURCES: Record<string, string> = import.meta.glob(
  "../../../protocol/fixtures/*.jsonl",
  { eager: true, import: "default", query: "?raw" }
);

const DISCRIMINANTS: Partial<Record<ProtocolMethod, readonly string[]>> = {
  "graph.slice": ["level", "parentId"],
  "outline.children": ["parentId"],
};

const UNSERVED: ReadonlySet<string> = new Set([
  "graph.slice.package-aggregated",
]);

export interface Transcript {
  readonly caseName: string;
  readonly request: Record<string, unknown>;
  readonly result: unknown;
}

interface EventTranscript {
  readonly caseName: string;
  readonly payload: unknown;
}

export class FakeBridgeError extends Error {
  readonly protocolError: ProtocolError;

  constructor(protocolError: ProtocolError) {
    super(protocolError.detail ?? protocolError.code);
    this.name = "FakeBridgeError";
    this.protocolError = protocolError;
  }
}

const parseFrames = (text: string): Record<string, unknown>[] =>
  text
    .split("\n")
    .filter((line) => line.length > 0)
    .map((line) => JSON.parse(line) as Record<string, unknown>);

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
  return undefined;
};

const stemOf = (filePath: string): string =>
  PATH_STEM.exec(filePath)?.[1] ?? filePath.replace(JSONL_SUFFIX, "");

const caseNameOf = (stem: string, topic: string): string =>
  stem.slice(topic.length + 1) || "default";

const EVENT_NAMES = Object.keys(EVENTS) as ProtocolEventName[];

export const readMethodTranscripts = (
  sources: Readonly<Record<string, string>>,
  unserved: ReadonlySet<string>
): Map<ProtocolMethod, Transcript[]> => {
  const routes = new Map<ProtocolMethod, Transcript[]>();
  const unservedSeen = new Set<string>();
  for (const [filePath, text] of Object.entries(sources)) {
    const stem = stemOf(filePath);
    const topic = resolveTopic(stem, PROTOCOL_METHODS);
    if (topic === undefined) {
      continue;
    }
    const method = topic as ProtocolMethod;
    const frames = parseFrames(text);
    const request = frames.find((frame) => frame.kind === "request");
    const response = frames.find(
      (frame) => frame.kind === "response" && frame.ok === true
    );
    if (request === undefined || response === undefined) {
      continue;
    }
    const parsedRequest = requestEnvelopeSchema.parse(request);
    const parsedResponse = okResponseEnvelopeSchema.parse(response);
    const contract = METHODS[method];
    const parsedParams = contract.params.parse(parsedRequest.payload);
    const parsedResult =
      contract.result === null
        ? parsedResponse.payload
        : contract.result.parse(parsedResponse.payload);
    if (unserved.has(stem)) {
      unservedSeen.add(stem);
      continue;
    }
    routes.set(method, [
      ...(routes.get(method) ?? []),
      {
        caseName: caseNameOf(stem, topic),
        request: parsedParams as Record<string, unknown>,
        result: parsedResult,
      },
    ]);
  }
  const stale = [...unserved].filter((stem) => !unservedSeen.has(stem));
  if (stale.length > 0) {
    throw new Error(
      `UNSERVED cita fixtures que não existem: ${stale.join(", ")}`
    );
  }
  return routes;
};

const readEventTranscripts = (): Map<ProtocolEventName, EventTranscript[]> => {
  const routes = new Map<ProtocolEventName, EventTranscript[]>();
  for (const [filePath, text] of Object.entries(FIXTURE_SOURCES)) {
    const stem = stemOf(filePath);
    const topic = resolveTopic(stem, EVENT_NAMES);
    if (topic === undefined) {
      continue;
    }
    const name = topic as ProtocolEventName;
    const frame = parseFrames(text).find((line) => line.kind === "event");
    if (frame === undefined) {
      continue;
    }
    const parsed = eventEnvelopeSchema.parse(frame);
    routes.set(name, [
      ...(routes.get(name) ?? []),
      {
        caseName: caseNameOf(stem, topic),
        payload: EVENTS[name].parse(parsed.payload),
      },
    ]);
  }
  return routes;
};

const METHOD_ROUTES = readMethodTranscripts(FIXTURE_SOURCES, UNSERVED);
const EVENT_ROUTES = readEventTranscripts();

const matchesDiscriminants = (
  method: ProtocolMethod,
  request: Record<string, unknown>,
  payload: Record<string, unknown>
): boolean => {
  const fields = DISCRIMINANTS[method];
  if (fields === undefined) {
    return true;
  }
  return fields.every((field) => request[field] === payload[field]);
};

const findOverride = (
  method: ProtocolMethod,
  payload: Record<string, unknown>
): FakeTranscriptOverride | undefined => {
  if (!import.meta.env.DEV) {
    return undefined;
  }
  return window.__STRATA_FAKE_TRANSCRIPTS__?.find(
    (override) =>
      override.method === method &&
      matchesDiscriminants(method, override.request, payload)
  );
};

const reject = (protocolError: ProtocolError): Promise<never> =>
  Promise.reject(new FakeBridgeError(protocolError));

const isProtocolMethod = (method: string): method is ProtocolMethod =>
  Object.hasOwn(METHODS, method);

export const fakeBridgeRoutes = (): Record<string, string[]> =>
  Object.fromEntries(
    [...METHOD_ROUTES.entries()].map(([method, transcripts]) => [
      method,
      transcripts.map((transcript) => transcript.caseName).sort(),
    ])
  );

export interface FakeEvent {
  readonly event: ProtocolEventName;
  readonly payload: unknown;
}

export type FakeEventListener = (event: FakeEvent) => void;

const listeners = new Set<FakeEventListener>();

export const fakeRequest = (
  method: string,
  payload: unknown
): Promise<unknown> => {
  if (!isProtocolMethod(method)) {
    return reject({
      code: "PROTO_UNKNOWN_METHOD",
      detail: "nenhum handler está registrado para o método pedido",
      origin: "analyzer",
      params: { method },
    });
  }
  const parsed = METHODS[method].params.safeParse(payload);
  if (!parsed.success) {
    return reject({
      code: "PROTO_INVALID_PAYLOAD",
      detail: parsed.error.message,
      origin: "analyzer",
      params: { method },
    });
  }
  const asRecord = (parsed.data ?? {}) as Record<string, unknown>;
  const override = findOverride(method, asRecord);
  if (override !== undefined) {
    return Promise.resolve(override.result);
  }
  const transcripts = METHOD_ROUTES.get(method) ?? [];
  const chosen = transcripts.find((transcript) =>
    matchesDiscriminants(method, transcript.request, asRecord)
  );
  if (chosen === undefined) {
    return reject({
      code: "INTERNAL_UNEXPECTED",
      detail: `o bridge falso não tem fixture para o método ${method}`,
      origin: "frontend",
      params: { method },
    });
  }
  return Promise.resolve(chosen.result);
};

export const fakeSubscribe = (listener: FakeEventListener): (() => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

export const fakeEmit = (
  event: ProtocolEventName,
  caseName?: string
): boolean => {
  const transcripts = EVENT_ROUTES.get(event) ?? [];
  const chosen =
    caseName === undefined
      ? transcripts[0]
      : transcripts.find((transcript) => transcript.caseName === caseName);
  if (chosen === undefined) {
    return false;
  }
  for (const listener of listeners) {
    listener({ event, payload: chosen.payload });
  }
  return true;
};
