import { z } from "zod";

const ULID_LENGTH = 26;

export const analysisIdSchema = z
  .string()
  .max(3 + ULID_LENGTH)
  .regex(/^an_[0-9A-HJKMNP-TV-Z]{26}$/);
export type AnalysisId = z.infer<typeof analysisIdSchema>;

export const requestIdSchema = z
  .string()
  .max(4 + ULID_LENGTH)
  .regex(/^req_[0-9A-HJKMNP-TV-Z]{26}$/);
export type RequestId = z.infer<typeof requestIdSchema>;

export const repositoryHandleSchema = z
  .string()
  .max(27)
  .regex(/^repo_[A-Za-z0-9_-]{22}$/);
export type RepositoryHandle = z.infer<typeof repositoryHandleSchema>;

export const commitIdSchema = z
  .string()
  .max(64)
  .regex(/^[0-9a-f]{40}(?:[0-9a-f]{24})?$/);
export type CommitId = z.infer<typeof commitIdSchema>;

export const shortCommitIdSchema = z
  .string()
  .max(12)
  .regex(/^[0-9a-f]{7,12}$/);
export type ShortCommitId = z.infer<typeof shortCommitIdSchema>;

export const sourceRootIdSchema = z
  .string()
  .max(11)
  .regex(/^sr_[0-9a-hjkmnp-tv-z]{8}$/);
export type SourceRootId = z.infer<typeof sourceRootIdSchema>;

export const edgeIdSchema = z
  .string()
  .max(18)
  .regex(/^e_[0-9a-f]{16}$/);
export type EdgeId = z.infer<typeof edgeIdSchema>;

export const configHashSchema = z
  .string()
  .max(64)
  .regex(/^[0-9a-f]{64}$/);
export type ConfigHash = z.infer<typeof configHashSchema>;

export const elementIdSchema = z
  .string()
  .max(4096)
  .regex(/^(?:pkg:|type:|method:|file:)/);
export type ElementId = z.infer<typeof elementIdSchema>;

export const prefixIdSchema = z
  .string()
  .max(4096)
  .regex(/^pfx:[^.]+(?:\.[^.]+)*$/);
export type PrefixId = z.infer<typeof prefixIdSchema>;

export const graphNodeIdSchema = z.union([elementIdSchema, prefixIdSchema]);
export type GraphNodeId = z.infer<typeof graphNodeIdSchema>;

export const cursorSchema = z.base64url().min(1).max(256);
export type Cursor = z.infer<typeof cursorSchema>;

export const timestampSchema = z.iso.datetime({ precision: 0 }).max(32);
export type Timestamp = z.infer<typeof timestampSchema>;

export const filePathSchema = z.string().max(1024);
export const qualifiedNameSchema = z.string().max(4096);
export const elementLabelSchema = z.string().max(512);
export const commitSubjectSchema = z.string().max(512);
export const commitBodySchema = z.string().max(4000);
export const authorDisplayNameSchema = z.string().max(128);
export const SEARCH_QUERY_MAX_CHARS = 128;
export const searchQuerySchema = z.string().max(SEARCH_QUERY_MAX_CHARS);

export const percentileSchema = z.number().min(0).max(100);

export const opaquePayloadSchema = z.unknown();

export const pageOf = <T extends z.ZodType>(itemSchema: T) =>
  z.strictObject({
    items: z.array(itemSchema),
    nextCursor: cursorSchema.nullable(),
  });
