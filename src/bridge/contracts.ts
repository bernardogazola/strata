import { z } from "zod";
import { protocolEventSchema } from "@/protocol/envelope";
import { filePathSchema, repositoryHandleSchema } from "@/protocol/primitives";

export const ANALYZER_STATE = "strata://analyzer-state";
export const ANALYSIS_PROGRESS = "strata://analysis-progress";
export const ANALYSIS_STAGE = "strata://analysis-stage";
export const ANALYSIS_LIFECYCLE = "strata://analysis-lifecycle";
export const ANALYSIS_WARNING = "strata://analysis-warning";
export const CACHE_INVALIDATED = "strata://cache-invalidated";

export const FORWARDED_CHANNELS = [
  ANALYSIS_PROGRESS,
  ANALYSIS_STAGE,
  ANALYSIS_LIFECYCLE,
  ANALYSIS_WARNING,
  CACHE_INVALIDATED,
] as const;

export const forwardedFrameSchema = z.strictObject({
  event: protocolEventSchema,
  payload: z.unknown(),
});
export type ForwardedFrame = z.infer<typeof forwardedFrameSchema>;

export const openedRepositorySchema = z.strictObject({
  displayName: filePathSchema,
  handle: repositoryHandleSchema,
});
export type OpenedRepository = z.infer<typeof openedRepositorySchema>;

export const stderrTailSchema = z.strictObject({
  lines: z.array(z.string()),
  truncated: z.boolean(),
});
export type StderrTail = z.infer<typeof stderrTailSchema>;

export const exportResultSchema = z.strictObject({ written: z.boolean() });
export type ExportResult = z.infer<typeof exportResultSchema>;

export interface DiagnosticsOptions {
  readonly analysisId?: string;
  readonly redactAuthors: boolean;
  readonly redactPaths: boolean;
}
