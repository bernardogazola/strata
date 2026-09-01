import { z } from "zod";

export const errorOriginSchema = z.enum(["analyzer", "shell", "frontend"]);
export type ErrorOrigin = z.infer<typeof errorOriginSchema>;

export interface ErrorParamsByCode {
  ANALYSIS_ALREADY_RUNNING: Record<string, never>;
  ANALYSIS_CANCELLED: Record<string, never>;
  ANALYSIS_NOT_FOUND: Record<string, never>;

  INTERNAL_UNEXPECTED: Record<string, never>;
  PROC_ANALYZER_CRASHED: { exitCode: number | null };
  PROC_ANALYZER_NOT_FOUND: Record<string, never>;
  PROC_ANALYZER_START_FAILED: { detail: string };
  PROTO_INVALID_PAYLOAD: { method: string };
  PROTO_MALFORMED_MESSAGE: Record<string, never>;
  PROTO_MESSAGE_TOO_LARGE: { limitBytes: number; receivedBytes: number };
  PROTO_UNKNOWN_METHOD: { method: string };

  PROTO_VERSION_MISMATCH: { expected: number; received: number };

  REPO_HANDLE_UNKNOWN: Record<string, never>;
  REPO_IS_BARE: Record<string, never>;
  REPO_NO_JAVA_SOURCES: Record<string, never>;
  REPO_NOT_A_GIT_REPOSITORY: Record<string, never>;
  REPO_TOO_MANY_FILES: { limit: number; found: number };
  SOURCE_FILE_TOO_LARGE: { filePath: string; limitBytes: number };
  SOURCE_PARSE_FAILED: { filePath: string };
}

export type ErrorCode = keyof ErrorParamsByCode;

export const errorParamsSchemaByCode = {
  ANALYSIS_ALREADY_RUNNING: z.object({}),
  ANALYSIS_CANCELLED: z.object({}),
  ANALYSIS_NOT_FOUND: z.object({}),
  INTERNAL_UNEXPECTED: z.object({}),
  PROC_ANALYZER_CRASHED: z.object({ exitCode: z.number().int().nullable() }),
  PROC_ANALYZER_NOT_FOUND: z.object({}),
  PROC_ANALYZER_START_FAILED: z.object({ detail: z.string().max(2048) }),
  PROTO_INVALID_PAYLOAD: z.object({ method: z.string().max(128) }),
  PROTO_MALFORMED_MESSAGE: z.object({}),
  PROTO_MESSAGE_TOO_LARGE: z.object({
    limitBytes: z.number().int().nonnegative(),
    receivedBytes: z.number().int().nonnegative(),
  }),
  PROTO_UNKNOWN_METHOD: z.object({ method: z.string().max(128) }),
  PROTO_VERSION_MISMATCH: z.object({
    expected: z.number().int(),
    received: z.number().int(),
  }),
  REPO_HANDLE_UNKNOWN: z.object({}),
  REPO_IS_BARE: z.object({}),
  REPO_NO_JAVA_SOURCES: z.object({}),
  REPO_NOT_A_GIT_REPOSITORY: z.object({}),
  REPO_TOO_MANY_FILES: z.object({
    found: z.number().int().nonnegative(),
    limit: z.number().int().positive(),
  }),
  SOURCE_FILE_TOO_LARGE: z.object({
    filePath: z.string().max(1024),
    limitBytes: z.number().int().positive(),
  }),
  SOURCE_PARSE_FAILED: z.object({ filePath: z.string().max(1024) }),
} satisfies { [K in ErrorCode]: z.ZodType };

export const ERROR_CODES = Object.keys(errorParamsSchemaByCode) as ErrorCode[];

export const isKnownErrorCode = (code: string): code is ErrorCode =>
  Object.hasOwn(errorParamsSchemaByCode, code);

export const protocolErrorSchema = z.object({
  code: z.string().max(128),
  detail: z.string().max(2048).optional(),
  origin: errorOriginSchema.optional(),
  params: z
    .record(
      z.string(),
      z.union([z.string(), z.number(), z.boolean(), z.null()])
    )
    .optional(),
});

export type ProtocolError = z.infer<typeof protocolErrorSchema>;
