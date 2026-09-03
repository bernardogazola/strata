import { z } from "zod";
import {
  analysisCompletenessSchema,
  analysisStageSchema,
  progressTickSchema,
} from "./analysis";
import {
  cacheInvalidationReasonSchema,
  dependencyDirectionSchema,
  dependencyTypeSchema,
  elementKindSchema,
  graphLevelSchema,
  resolutionQualitySchema,
  stageStatusSchema,
} from "./enums";
import {
  type ProtocolEventName,
  type ProtocolMethod,
  protocolEventSchema,
  protocolVersionSchema,
} from "./envelope";
import { protocolErrorSchema } from "./errors";
import {
  dependencyEvidenceResultSchema,
  dependencyPageSchema,
  elementDetailSchema,
  graphSliceSchema,
  outlinePageSchema,
  searchResultSchema,
} from "./graph";
import {
  hotspotIdsSchema,
  hotspotPageSchema,
  hotspotProfileSchema,
} from "./hotspot";
import {
  analysisIdSchema,
  cursorSchema,
  edgeIdSchema,
  elementIdSchema,
  percentileSchema,
  searchQuerySchema,
} from "./primitives";
import {
  analysisConfigurationSchema,
  analysisStatusReportSchema,
  repositoryDescriptionSchema,
  stageCountersSchema,
} from "./repository";
import {
  changeMetricsSchema,
  coChangePageSchema,
  timelinePageSchema,
} from "./timeline";
import { guidedTourSchema } from "./tour";

export interface MethodContract {
  readonly params: z.ZodType;
  readonly requiresRepository: boolean;
  readonly result: z.ZodType | null;
}

const analysisRefSchema = z.strictObject({ analysisId: analysisIdSchema });
const elementRefSchema = z.strictObject({
  analysisId: analysisIdSchema,
  elementId: elementIdSchema,
});

export const METHODS = {
  "analysis.cancel": {
    params: analysisRefSchema,
    requiresRepository: false,
    result: z.strictObject({ cancelled: z.boolean() }),
  },
  "analysis.start": {
    params: analysisConfigurationSchema,
    requiresRepository: true,
    result: z.strictObject({
      analysisId: analysisIdSchema,
      reusedCache: z.boolean(),
    }),
  },
  "analysis.status": {
    params: analysisRefSchema,
    requiresRepository: false,
    result: analysisStatusReportSchema,
  },
  "archaeology.timeline": {
    params: z.strictObject({
      analysisId: analysisIdSchema,
      cursor: cursorSchema.optional(),
      elementId: elementIdSchema,
      limit: z.number().int().positive().max(200).optional(),
    }),
    requiresRepository: false,
    result: timelinePageSchema,
  },
  "diagnostics.report": {
    params: z.strictObject({ analysisId: analysisIdSchema.optional() }),
    requiresRepository: false,
    result: null,
  },
  "element.dependencies": {
    params: z.strictObject({
      analysisId: analysisIdSchema,
      cursor: cursorSchema.optional(),
      direction: dependencyDirectionSchema,
      elementId: elementIdSchema,
      limit: z.number().int().positive().max(2000).optional(),
    }),
    requiresRepository: false,
    result: dependencyPageSchema,
  },
  "element.dependencyEvidence": {
    params: z.strictObject({
      analysisId: analysisIdSchema,
      edgeId: edgeIdSchema,
      limit: z.number().int().positive().max(50).optional(),
    }),
    requiresRepository: false,
    result: dependencyEvidenceResultSchema,
  },
  "element.describe": {
    params: elementRefSchema,
    requiresRepository: false,
    result: elementDetailSchema,
  },
  "evolution.changeMetrics": {
    params: elementRefSchema,
    requiresRepository: false,
    result: changeMetricsSchema,
  },
  "evolution.cochange": {
    params: z.strictObject({
      analysisId: analysisIdSchema,
      cursor: cursorSchema.optional(),
      elementId: elementIdSchema,
      limit: z.number().int().positive().max(500).optional(),
      minSupport: z.number().int().nonnegative().optional(),
    }),
    requiresRepository: false,
    result: coChangePageSchema,
  },
  "graph.search": {
    params: z.strictObject({
      analysisId: analysisIdSchema,
      kinds: z.array(elementKindSchema).optional(),
      limit: z.number().int().positive().max(50).optional(),
      query: searchQuerySchema,
    }),
    requiresRepository: false,
    result: searchResultSchema,
  },
  "graph.slice": {
    params: z.strictObject({
      analysisId: analysisIdSchema,
      edgeTypes: z.array(dependencyTypeSchema).optional(),
      level: graphLevelSchema,
      limit: z.number().int().positive().max(5000).optional(),
      minResolution: resolutionQualitySchema.optional(),
      parentId: elementIdSchema.optional(),
    }),
    requiresRepository: false,
    result: graphSliceSchema,
  },
  "hotspot.profile": {
    params: elementRefSchema,
    requiresRepository: false,
    result: hotspotProfileSchema,
  },
  "hotspots.ids": {
    params: z.strictObject({
      analysisId: analysisIdSchema,
      granularity: elementKindSchema,
      percentile: percentileSchema.optional(),
    }),
    requiresRepository: false,
    result: hotspotIdsSchema,
  },
  "hotspots.list": {
    params: z.strictObject({
      analysisId: analysisIdSchema,
      cursor: cursorSchema.optional(),
      granularity: elementKindSchema,
      limit: z.number().int().positive().max(500).optional(),
      percentile: percentileSchema.optional(),
    }),
    requiresRepository: false,
    result: hotspotPageSchema,
  },
  "outline.children": {
    params: z.strictObject({
      analysisId: analysisIdSchema,
      cursor: cursorSchema.optional(),
      limit: z.number().int().positive().max(500).optional(),
      parentId: elementIdSchema.nullable(),
    }),
    requiresRepository: false,
    result: outlinePageSchema,
  },
  "repository.describe": {
    params: z.strictObject({}),
    requiresRepository: true,
    result: repositoryDescriptionSchema,
  },
  "system.ping": {
    params: z.strictObject({}),
    requiresRepository: false,
    result: z.strictObject({ uptimeMillis: z.number().int().nonnegative() }),
  },
  "system.shutdown": {
    params: z.strictObject({}),
    requiresRepository: false,
    result: z.strictObject({}),
  },
  tour: {
    params: analysisRefSchema,
    requiresRepository: false,
    result: guidedTourSchema,
  },
} satisfies Record<ProtocolMethod, MethodContract>;

export const PROTOCOL_METHODS = Object.keys(METHODS) as ProtocolMethod[];

export const analysisProgressPayloadSchema = progressTickSchema.extend({
  analysisId: analysisIdSchema,
});

export const protocolReadyPayloadSchema = z.strictObject({
  analyzerVersion: z.string().max(64),
  capabilities: z.array(z.string().max(64)),
  protocol: protocolVersionSchema,
});

export const analysisStageCompletedPayloadSchema = z.strictObject({
  analysisId: analysisIdSchema,
  counters: stageCountersSchema,
  durationMs: z.number().int().nonnegative(),
  stage: analysisStageSchema,
  status: stageStatusSchema,
});

const analysisErrorPayloadSchema = z.strictObject({
  analysisId: analysisIdSchema,
  error: protocolErrorSchema,
});

const analysisCompletenessPayloadSchema = z.strictObject({
  analysisId: analysisIdSchema,
  completeness: analysisCompletenessSchema,
});

export const EVENTS = {
  "analysis.cancelled": analysisCompletenessPayloadSchema,
  "analysis.completed": analysisCompletenessPayloadSchema,
  "analysis.failed": analysisErrorPayloadSchema,
  "analysis.progress": analysisProgressPayloadSchema,
  "analysis.stage.completed": analysisStageCompletedPayloadSchema,
  "analysis.started": z.strictObject({ analysisId: analysisIdSchema }),
  "analysis.warning": analysisErrorPayloadSchema,
  "cache.invalidated": z.strictObject({
    reason: cacheInvalidationReasonSchema,
  }),
  "protocol.ready": protocolReadyPayloadSchema,
} satisfies Record<ProtocolEventName, z.ZodType>;

export const PROTOCOL_EVENTS = protocolEventSchema.options;
