import { z } from "zod";

export const errorOriginSchema = z.enum(["analyzer", "shell", "frontend"]);
export type ErrorOrigin = z.infer<typeof errorOriginSchema>;

export interface ErrorParamsByCode {
  ANALYSIS_ALREADY_RUNNING: Record<string, never>;
  ANALYSIS_CANCELLED: Record<string, never>;
  ANALYSIS_NOT_FOUND: Record<string, never>;
  CACHE_CORRUPT: Record<string, never>;
  CACHE_MIGRATION_FAILED: { fromVersion: number; toVersion: number };
  INTERNAL_UNEXPECTED: Record<string, never>;
  PROC_ANALYZER_CRASHED: { exitCode: number | null };
  PROC_ANALYZER_NOT_FOUND: Record<string, never>;
  PROC_ANALYZER_NOT_READY: Record<string, never>;
  PROC_ANALYZER_START_FAILED: Record<string, never>;
  PROC_REQUEST_TIMEOUT: { method: string; timeoutMs: number };
  PROC_SHUTTING_DOWN: Record<string, never>;
  PROTO_INVALID_PAYLOAD: { method: string };
  PROTO_MALFORMED_MESSAGE: Record<string, never>;
  PROTO_MESSAGE_TOO_LARGE: { limitBytes: number; receivedBytes: number };
  PROTO_UNKNOWN_METHOD: { method: string };
  PROTO_VERSION_MISMATCH: { expected: number; received: number };
  REPO_HANDLE_UNKNOWN: Record<string, never>;
  REPO_IS_BARE: Record<string, never>;
  REPO_NO_HISTORY: Record<string, never>;
  REPO_NO_JAVA_SOURCES: Record<string, never>;
  REPO_NOT_A_GIT_REPOSITORY: Record<string, never>;
  REPO_PATH_CHANGED: Record<string, never>;
  REPO_PATH_ESCAPES_ROOT: { filePath: string };
  REPO_TOO_MANY_FILES: { limit: number; found: number };
  REPO_UNREADABLE: { filePath: string };
  SOURCE_FILE_TOO_LARGE: { filePath: string; limitBytes: number };
  SOURCE_PARSE_FAILED: { filePath: string };
}

export type ErrorCode = keyof ErrorParamsByCode;

export const errorParamsSchemaByCode = {
  ANALYSIS_ALREADY_RUNNING: z.object({}),
  ANALYSIS_CANCELLED: z.object({}),
  ANALYSIS_NOT_FOUND: z.object({}),
  CACHE_CORRUPT: z.object({}),
  CACHE_MIGRATION_FAILED: z.object({
    fromVersion: z.number().int().nonnegative(),
    toVersion: z.number().int().nonnegative(),
  }),
  INTERNAL_UNEXPECTED: z.object({}),
  PROC_ANALYZER_CRASHED: z.object({ exitCode: z.number().int().nullable() }),
  PROC_ANALYZER_NOT_FOUND: z.object({}),
  PROC_ANALYZER_NOT_READY: z.object({}),
  PROC_ANALYZER_START_FAILED: z.object({}),
  PROC_REQUEST_TIMEOUT: z.object({
    method: z.string().max(128),
    timeoutMs: z.number().int().positive(),
  }),
  PROC_SHUTTING_DOWN: z.object({}),
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
  REPO_NO_HISTORY: z.object({}),
  REPO_NO_JAVA_SOURCES: z.object({}),
  REPO_NOT_A_GIT_REPOSITORY: z.object({}),
  REPO_PATH_CHANGED: z.object({}),
  REPO_PATH_ESCAPES_ROOT: z.object({ filePath: z.string().max(1024) }),
  REPO_TOO_MANY_FILES: z.object({
    found: z.number().int().nonnegative(),
    limit: z.number().int().positive(),
  }),
  REPO_UNREADABLE: z.object({ filePath: z.string().max(1024) }),
  SOURCE_FILE_TOO_LARGE: z.object({
    filePath: z.string().max(1024),
    limitBytes: z.number().int().positive(),
  }),
  SOURCE_PARSE_FAILED: z.object({ filePath: z.string().max(1024) }),
} satisfies { [K in ErrorCode]: z.ZodType };

export const ERROR_CODES = Object.keys(errorParamsSchemaByCode) as ErrorCode[];

export const isKnownErrorCode = (code: string): code is ErrorCode =>
  Object.hasOwn(errorParamsSchemaByCode, code);

export const protocolErrorSchema = z.strictObject({
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
