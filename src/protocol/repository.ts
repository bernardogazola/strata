import { z } from "zod";
import {
  analysisCompletenessSchema,
  analysisStageSchema,
  analysisStatusSchema,
} from "./analysis";
import {
  buildSystemSchema,
  historyScopeSchema,
  sourceRootKindSchema,
  stageStatusSchema,
} from "./enums";
import {
  analysisIdSchema,
  commitIdSchema,
  filePathSchema,
  sourceRootIdSchema,
  timestampSchema,
} from "./primitives";

export const sourceRootSchema = z.strictObject({
  id: sourceRootIdSchema,
  javaFileCount: z.number().int().nonnegative(),
  kind: sourceRootKindSchema,
  label: filePathSchema,
});
export type SourceRoot = z.infer<typeof sourceRootSchema>;

export const repositoryDescriptionSchema = z.strictObject({
  buildSystem: buildSystemSchema,
  cachedAnalysisId: analysisIdSchema.nullable(),
  defaultBranch: z.string().max(255).nullable(),
  displayName: z.string().max(255),
  headCommitId: commitIdSchema.nullable(),
  isBare: z.boolean(),
  isGitRepository: z.boolean(),
  isShallow: z.boolean(),
  sourceRoots: z.array(sourceRootSchema),
  totalFileCount: z.number().int().nonnegative(),
});
export type RepositoryDescription = z.infer<typeof repositoryDescriptionSchema>;

export const analysisConfigurationSchema = z
  .strictObject({
    historyScope: historyScopeSchema,
    historyScopeCommits: z.number().int().positive().nullable(),
    includeTests: z.boolean(),
    rebuildCache: z.boolean(),
    sourceRootIds: z.array(sourceRootIdSchema).nullable(),
  })
  .refine(
    (config) =>
      config.historyScope !== "LAST_N_COMMITS" ||
      config.historyScopeCommits !== null,
    {
      error:
        "historyScopeCommits é obrigatório quando historyScope é LAST_N_COMMITS",
    }
  );
export type AnalysisConfiguration = z.infer<typeof analysisConfigurationSchema>;

export const stageCountersSchema = z.record(
  z.string().max(64),
  z.number().int().nonnegative()
);

export const analysisStageReportSchema = z.strictObject({
  counters: stageCountersSchema,
  durationMs: z.number().int().nonnegative().nullable(),
  stage: analysisStageSchema,
  startedAt: timestampSchema.nullable(),
  status: stageStatusSchema,
});
export type AnalysisStageReport = z.infer<typeof analysisStageReportSchema>;

export const analysisStatusReportSchema = z.strictObject({
  analysisId: analysisIdSchema,
  completeness: analysisCompletenessSchema,
  finishedAt: timestampSchema.nullable(),
  headCommitId: commitIdSchema.nullable(),
  stages: z.array(analysisStageReportSchema),
  startedAt: timestampSchema,
  status: analysisStatusSchema,
});
export type AnalysisStatusReport = z.infer<typeof analysisStatusReportSchema>;
