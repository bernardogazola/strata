import { z } from "zod";
import { changeTypeSchema, relationshipConfidenceSchema } from "./enums";
import {
  authorDisplayNameSchema,
  commitIdSchema,
  commitSubjectSchema,
  elementIdSchema,
  filePathSchema,
  pageOf,
  shortCommitIdSchema,
  timestampSchema,
} from "./primitives";

export const authorIdentitySchema = z.strictObject({
  displayName: authorDisplayNameSchema,
  identityKey: z.string().max(64),
});
export type AuthorIdentity = z.infer<typeof authorIdentitySchema>;

export const relationshipLinkSchema = z.strictObject({
  confidence: relationshipConfidenceSchema,
  provider: z.string().max(64).nullable(),
  reference: z.string().max(128),
});
export type RelationshipLink = z.infer<typeof relationshipLinkSchema>;

export const timelineEventSchema = z.strictObject({
  additions: z.number().int().nonnegative().nullable(),
  at: timestampSchema,
  author: authorIdentitySchema,
  changeType: changeTypeSchema,
  commitId: commitIdSchema,
  deletions: z.number().int().nonnegative().nullable(),
  isMerge: z.boolean(),
  links: z.array(relationshipLinkSchema),
  pathAtCommit: filePathSchema,
  shortCommitId: shortCommitIdSchema,
  subject: commitSubjectSchema,
});
export type TimelineEvent = z.infer<typeof timelineEventSchema>;

export const timelinePageSchema = pageOf(timelineEventSchema);
export type TimelinePage = z.infer<typeof timelinePageSchema>;

export const coChangeEntrySchema = z.strictObject({
  changesOther: z.number().int().nonnegative(),
  changesSelf: z.number().int().nonnegative(),
  confidenceFromSelf: z.number().min(0).max(1),
  confidenceToSelf: z.number().min(0).max(1),
  elementId: elementIdSchema,
  jaccard: z.number().min(0).max(1),
  lastCoChangedAt: timestampSchema,
  support: z.number().int().nonnegative(),
});
export type CoChangeEntry = z.infer<typeof coChangeEntrySchema>;

export const coChangePageSchema = pageOf(coChangeEntrySchema);
export type CoChangePage = z.infer<typeof coChangePageSchema>;

export const changeMetricsSchema = z.strictObject({
  additions: z.number().int().nonnegative(),
  churn: z.number().int().nonnegative(),
  commitCount: z.number().int().nonnegative(),
  commitCountExcludingBulk: z.number().int().nonnegative(),
  deletions: z.number().int().nonnegative(),
  distinctAuthors: z.number().int().nonnegative(),
  elementId: elementIdSchema,
  firstChangedAt: timestampSchema.nullable(),
  lastChangedAt: timestampSchema.nullable(),
});
export type ChangeMetrics = z.infer<typeof changeMetricsSchema>;
