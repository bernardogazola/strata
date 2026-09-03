import { z } from "zod";

export const analysisStageSchema = z.enum([
  "REPOSITORY_VALIDATION",
  "LAYOUT_DISCOVERY",
  "SOURCE_ANALYSIS",
  "GIT_HISTORY_CHEAP",
  "STRUCTURAL_GRAPH",
  "GIT_HISTORY_DETAILED",
  "EVOLUTIONARY_COUPLING",
  "HOTSPOT_PROFILES",
  "GUIDED_TOUR",
  "PERSISTENCE",
]);
export type AnalysisStage = z.infer<typeof analysisStageSchema>;

export const progressUnitSchema = z.enum([
  "JAVA_FILES",
  "COMMITS",
  "COMMIT_PAIRS",
  "TYPES",
  "PACKAGES",
]);
export type ProgressUnit = z.infer<typeof progressUnitSchema>;

export const analysisStatusSchema = z.enum([
  "RUNNING",
  "COMPLETED",
  "CANCELLED",
  "FAILED",
]);
export type AnalysisStatus = z.infer<typeof analysisStatusSchema>;

export const progressTickSchema = z.strictObject({
  completed: z.number().int().nonnegative(),
  stage: analysisStageSchema,
  total: z.number().int().nonnegative().nullable(),
  unit: progressUnitSchema,
});
export type ProgressTick = z.infer<typeof progressTickSchema>;

export const analysisCompletenessSchema = z.strictObject({
  historyTruncatedAtCommits: z.number().int().nonnegative().nullable(),
  parseFailures: z.number().int().nonnegative(),
  shallowClone: z.boolean(),
  skippedFiles: z.number().int().nonnegative(),
  totalFiles: z.number().int().nonnegative(),
});
export type AnalysisCompleteness = z.infer<typeof analysisCompletenessSchema>;

export const isAnalysisPartial = (
  completeness: AnalysisCompleteness
): boolean =>
  completeness.skippedFiles > 0 ||
  completeness.parseFailures > 0 ||
  completeness.shallowClone ||
  completeness.historyTruncatedAtCommits !== null;
