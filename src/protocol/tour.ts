import { z } from "zod";
import {
  elementKindSchema,
  entryPointKindSchema,
  tourReasonTagSchema,
  tourTierSchema,
} from "./enums";
import { hotspotEvidenceSchema } from "./hotspot";
import {
  analysisIdSchema,
  elementIdSchema,
  elementLabelSchema,
  percentileSchema,
} from "./primitives";

export const metricSnapshotSchema = z.strictObject({
  ccSum: z.number().int().nonnegative(),
  commitCount: z.number().int().nonnegative(),
  fanIn: z.number().int().nonnegative(),
  fanOut: z.number().int().nonnegative(),
  loc: z.number().int().nonnegative(),
  pagerankPercentile: percentileSchema,
});
export type MetricSnapshot = z.infer<typeof metricSnapshotSchema>;

export const tourStepSchema = z.strictObject({
  element: elementIdSchema,
  entryPointKind: entryPointKindSchema.nullable(),
  evidence: z.array(hotspotEvidenceSchema),
  granularity: elementKindSchema,
  label: elementLabelSchema,
  metrics: metricSnapshotSchema,
  order: z.number().int().positive(),
  reasonTags: z.array(tourReasonTagSchema).max(4),
  tier: tourTierSchema,
});
export type TourStep = z.infer<typeof tourStepSchema>;

export const guidedTourSchema = z.strictObject({
  analysisId: analysisIdSchema,
  emptyTiers: z.array(tourTierSchema),
  steps: z.array(tourStepSchema).max(25),
});
export type GuidedTour = z.infer<typeof guidedTourSchema>;
