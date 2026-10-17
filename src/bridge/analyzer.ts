import type { z } from "zod";
import type { ProtocolMethod } from "@/protocol/envelope";
import type { ProtocolError } from "@/protocol/errors";
import { protocolErrorSchema } from "@/protocol/errors";
import { METHODS } from "@/protocol/registry";
import type { BackendState } from "@/state/app-store";
import { backendStateSchema } from "@/state/app-store";
import {
  type DiagnosticsOptions,
  type ExportResult,
  exportResultSchema,
  type OpenedRepository,
  openedRepositorySchema,
  type StderrTail,
  stderrTailSchema,
} from "./contracts";
import { FakeBridgeError } from "./fake";
import { transport } from "./transport";

type Contracts = typeof METHODS;

export type MethodInput<M extends ProtocolMethod> = z.input<
  Contracts[M]["params"]
>;

export type MethodResult<M extends ProtocolMethod> =
  Contracts[M]["result"] extends z.ZodType
    ? z.infer<Contracts[M]["result"]>
    : unknown;

export const REPOSITORY_METHODS = [
  "repository.describe",
  "analysis.start",
] as const satisfies readonly ProtocolMethod[];

export type RepositoryMethod = (typeof REPOSITORY_METHODS)[number];
export type PlainMethod = Exclude<ProtocolMethod, RepositoryMethod>;

export class BridgeError extends Error {
  readonly protocolError: ProtocolError;

  constructor(protocolError: ProtocolError, options?: ErrorOptions) {
    super(protocolError.detail ?? protocolError.code, options);
    this.name = "BridgeError";
    this.protocolError = protocolError;
  }
}

export const protocolErrorOf = (error: unknown): ProtocolError =>
  error instanceof BridgeError
    ? error.protocolError
    : { code: "INTERNAL_UNEXPECTED", origin: "frontend" };

export const toProtocolError = (rejection: unknown): ProtocolError => {
  if (rejection instanceof FakeBridgeError) {
    return rejection.protocolError;
  }
  if (rejection instanceof BridgeError) {
    return rejection.protocolError;
  }
  const parsed = protocolErrorSchema.safeParse(rejection);
  if (parsed.success) {
    return parsed.data;
  }
  return {
    code: "INTERNAL_UNEXPECTED",
    detail: typeof rejection === "string" ? rejection : String(rejection),
    origin: "frontend",
  };
};

const failing = async <T>(work: () => Promise<T>): Promise<T> => {
  try {
    return await work();
  } catch (rejection) {
    throw new BridgeError(toProtocolError(rejection), { cause: rejection });
  }
};

const parseResult = <M extends ProtocolMethod>(
  method: M,
  raw: unknown
): MethodResult<M> => {
  const contract = METHODS[method];
  if (contract.result === null) {
    return raw as MethodResult<M>;
  }
  const parsed = contract.result.safeParse(raw);
  if (!parsed.success) {
    throw new BridgeError({
      code: "PROTO_INVALID_PAYLOAD",
      detail: parsed.error.message.slice(0, 2048),
      origin: "frontend",
      params: { method },
    });
  }
  return parsed.data as MethodResult<M>;
};

export const analyzerRequest = async <M extends PlainMethod>(
  method: M,
  payload: MethodInput<M>
): Promise<MethodResult<M>> =>
  parseResult(method, await failing(() => transport.request(method, payload)));

export const repositoryRequest = async <M extends RepositoryMethod>(
  method: M,
  payload: MethodInput<M>,
  handle: string
): Promise<MethodResult<M>> =>
  parseResult(
    method,
    await failing(() => transport.request(method, payload, handle))
  );

export const openRepository = async (): Promise<OpenedRepository | null> => {
  const raw = await failing(() => transport.openRepository());
  return raw === null ? null : openedRepositorySchema.parse(raw);
};

export const closeRepository = (handle: string): Promise<void> =>
  failing(() => transport.closeRepository(handle));

export const analyzerState = async (): Promise<BackendState> =>
  backendStateSchema.parse(await failing(() => transport.analyzerState()));

export const restartAnalyzer = async (): Promise<BackendState> =>
  backendStateSchema.parse(await failing(() => transport.analyzerRestart()));

export const analyzerStderrTail = async (): Promise<StderrTail> =>
  stderrTailSchema.parse(await failing(() => transport.analyzerStderrTail()));

export const cancelAnalysis = async (analysisId: string): Promise<boolean> => {
  const raw = await failing(() => transport.analyzerCancel(analysisId));
  return METHODS["analysis.cancel"].result.parse(raw).cancelled;
};

export const exportDiagnostics = async (
  options: DiagnosticsOptions
): Promise<ExportResult> =>
  exportResultSchema.parse(
    await failing(() => transport.exportDiagnostics(options))
  );
