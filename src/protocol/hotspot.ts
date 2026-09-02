import { z } from "zod";
import { elementKindSchema, hotspotMetricSchema } from "./enums";
import {
  elementIdSchema,
  elementLabelSchema,
  pageOf,
  percentileSchema,
} from "./primitives";

export const metricComponentSchema = z.strictObject({
  metric: hotspotMetricSchema,
  percentile: percentileSchema,
  raw: z.number(),
});
export type MetricComponent = z.infer<typeof metricComponentSchema>;

export const axisScoreSchema = z.strictObject({
  components: z.array(metricComponentSchema),
  percentile: percentileSchema,
});
export type AxisScore = z.infer<typeof axisScoreSchema>;

export const axisScoresSchema = z.strictObject({
  ARCHITECTURAL_EXPOSURE: axisScoreSchema,
  CHANGE_PRESSURE: axisScoreSchema,
  STRUCTURAL_DIFFICULTY: axisScoreSchema,
});
export type AxisScores = z.infer<typeof axisScoresSchema>;

export const hotspotContextSchema = z.strictObject({
  distinctAuthors: z.number().int().nonnegative(),
  noHistory: z.boolean(),
  recencyDays: z.number().int().nonnegative().nullable(),
});
export type HotspotContext = z.infer<typeof hotspotContextSchema>;

export const completenessCaveatSchema = z.strictObject({
  partial: z.boolean(),
  reason: z.string().max(64).nullable(),
});
export type CompletenessCaveat = z.infer<typeof completenessCaveatSchema>;

export const hotspotEvidenceSchema = z.strictObject({
  code: z.string().max(64),
  params: z.record(
    z.string().max(64),
    z.union([z.string().max(256), z.number(), z.boolean(), z.null()])
  ),
});
export type HotspotEvidence = z.infer<typeof hotspotEvidenceSchema>;

export const hotspotProfileSchema = z.strictObject({
  axes: axisScoresSchema,
  candidateAtPercentile: percentileSchema,
  cohortSize: z.number().int().nonnegative(),
  completenessCaveat: completenessCaveatSchema,
  context: hotspotContextSchema,
  elementId: elementIdSchema,
  evidence: z.array(hotspotEvidenceSchema),
  granularity: elementKindSchema,
  rank: z.number().int().positive(),
  smallCohort: z.boolean(),
});
export type HotspotProfile = z.infer<typeof hotspotProfileSchema>;

export const hotspotSummarySchema = z.strictObject({
  axisPercentiles: z.strictObject({
    ARCHITECTURAL_EXPOSURE: percentileSchema,
    CHANGE_PRESSURE: percentileSchema,
    STRUCTURAL_DIFFICULTY: percentileSchema,
  }),
  elementId: elementIdSchema,
  granularity: elementKindSchema,
  label: elementLabelSchema,
  rank: z.number().int().positive(),
});
export type HotspotSummary = z.infer<typeof hotspotSummarySchema>;

export const hotspotPageSchema = pageOf(hotspotSummarySchema).extend({
  cohortSize: z.number().int().nonnegative(),
  smallCohort: z.boolean(),
});
export type HotspotPage = z.infer<typeof hotspotPageSchema>;

export const hotspotIdsSchema = z.strictObject({
  elementIds: z.array(elementIdSchema).max(50_000),
});
export type HotspotIds = z.infer<typeof hotspotIdsSchema>;
