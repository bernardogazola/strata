import { z } from "zod";
import {
  dependencyTypeSchema,
  elementKindSchema,
  type GraphLevel,
  graphLevelSchema,
  graphNodeKindSchema,
  resolutionQualitySchema,
  typeKindSchema,
} from "./enums";
import {
  edgeIdSchema,
  elementIdSchema,
  elementLabelSchema,
  filePathSchema,
  graphNodeIdSchema,
  methodIdSchema,
  pageOf,
  qualifiedNameSchema,
} from "./primitives";

export const resolutionMixSchema = z.strictObject({
  INFERRED: z.number().int().nonnegative(),
  RESOLVED: z.number().int().nonnegative(),
  UNRESOLVED_EXTERNAL: z.number().int().nonnegative(),
});

export const graphNodeSchema = z.strictObject({
  fanIn: z.number().int().nonnegative(),
  fanOut: z.number().int().nonnegative(),
  id: graphNodeIdSchema,
  kind: graphNodeKindSchema,
  label: elementLabelSchema,
  loc: z.number().int().nonnegative(),
  pagerank: z.number().min(0).max(1),
  resolutionMix: resolutionMixSchema,
  typeCount: z.number().int().nonnegative(),
});
export type GraphNode = z.infer<typeof graphNodeSchema>;

export const graphEdgeSchema = z.strictObject({
  id: edgeIdSchema,
  resolution: resolutionQualitySchema,
  source: graphNodeIdSchema,
  target: graphNodeIdSchema,
  types: z.partialRecord(dependencyTypeSchema, z.number().int().positive()),
  weight: z.number().int().nonnegative(),
});
export type GraphEdge = z.infer<typeof graphEdgeSchema>;

export const graphSliceSchema = z.strictObject({
  aggregated: z.boolean(),
  budget: z.strictObject({
    edges: z.number().int().positive(),
    nodes: z.number().int().positive(),
  }),
  edges: z.array(graphEdgeSchema),
  level: graphLevelSchema,
  nodes: z.array(graphNodeSchema),
  parentId: elementIdSchema.nullable(),
  totals: z.strictObject({
    edges: z.number().int().nonnegative(),
    nodes: z.number().int().nonnegative(),
  }),
  truncated: z.boolean(),
});
export type GraphSlice = z.infer<typeof graphSliceSchema>;

export interface SliceBudget {
  readonly edges: number;
  readonly nodes: number;
}

export const GRAPH_SLICE_BUDGETS = {
  PACKAGE: { edges: 1200, nodes: 300 },
  TYPE: { edges: 5000, nodes: 1000 },
  TYPE_NEIGHBORHOOD: { edges: 2500, nodes: 500 },
} as const satisfies Record<GraphLevel, SliceBudget>;

export const outlineNodeSchema = z.strictObject({
  childCount: z.number().int().nonnegative(),
  id: elementIdSchema,
  kind: elementKindSchema,
  label: elementLabelSchema,
});
export type OutlineNode = z.infer<typeof outlineNodeSchema>;

export const outlinePageSchema = pageOf(outlineNodeSchema);
export type OutlinePage = z.infer<typeof outlinePageSchema>;

export const searchHitSchema = z.strictObject({
  elementId: elementIdSchema,
  kind: elementKindSchema,
  label: elementLabelSchema,
  packageId: elementIdSchema.nullable(),
  qualifiedName: qualifiedNameSchema,
});
export type SearchHit = z.infer<typeof searchHitSchema>;

export const searchResultSchema = z.strictObject({
  results: z.array(searchHitSchema).max(50),
});
export type SearchResult = z.infer<typeof searchResultSchema>;

const ELEMENT_METHODS_MAX = 1000;

export const DEPENDENCY_PAGE_MAX = 2000;

export const DEPENDENCY_EVIDENCE_RESULTS_MAX = 50;

export const lineRangeSchema = z
  .strictObject({
    end: z.number().int().positive(),
    start: z.number().int().positive(),
  })
  .refine(({ end, start }) => start <= end, {
    error: "um intervalo de linhas não pode terminar antes de começar",
  });
export type LineRange = z.infer<typeof lineRangeSchema>;

export const methodSummarySchema = z.strictObject({
  cc: z.number().int().positive().nullable(),
  elementId: methodIdSchema,
  lines: lineRangeSchema.nullable(),
  signature: elementLabelSchema,
});
export type MethodSummary = z.infer<typeof methodSummarySchema>;

export const dependencyCycleSchema = z.strictObject({
  id: z.number().int().positive(),
  size: z.number().int().min(2),
});
export type DependencyCycle = z.infer<typeof dependencyCycleSchema>;

export const elementDetailSchema = z.strictObject({
  elementId: elementIdSchema,
  filePath: filePathSchema.nullable(),
  graph: z.strictObject({
    cycle: dependencyCycleSchema.nullable(),
    fanIn: z.number().int().nonnegative(),
    fanOut: z.number().int().nonnegative(),
    pagerank: z.number().positive().max(1),
    resolutionMix: resolutionMixSchema,
  }),
  isTest: z.boolean(),
  kind: elementKindSchema,
  label: elementLabelSchema,
  lines: lineRangeSchema.nullable(),
  methods: z.array(methodSummarySchema).max(ELEMENT_METHODS_MAX),
  methodTotal: z.number().int().nonnegative(),
  metrics: z.strictObject({
    ccMax: z.number().int().nonnegative(),
    ccMean: z.number().nonnegative(),
    ccP90: z.number().int().nonnegative().nullable(),
    ccSum: z.number().int().nonnegative(),
    loc: z.number().int().nonnegative(),
    unitCount: z.number().int().nonnegative(),
  }),
  modifiers: z.array(z.string().max(32)),
  packageId: elementIdSchema.nullable(),
  qualifiedName: qualifiedNameSchema,
  typeKind: typeKindSchema.nullable(),
});
export type ElementDetail = z.infer<typeof elementDetailSchema>;

export const evidenceMethodSchema = z.strictObject({
  elementId: methodIdSchema,
  ownerId: elementIdSchema,
  ownerLabel: elementLabelSchema,
  signature: elementLabelSchema,
});
export type EvidenceMethod = z.infer<typeof evidenceMethodSchema>;

export const relationshipEvidenceSchema = z.strictObject({
  filePath: filePathSchema.nullable(),
  fromMethod: evidenceMethodSchema.nullable(),
  line: z.number().int().positive().nullable(),
  resolution: resolutionQualitySchema,
  toMethod: evidenceMethodSchema.nullable(),
  type: dependencyTypeSchema,
});
export type RelationshipEvidence = z.infer<typeof relationshipEvidenceSchema>;

export const dependencyEvidenceResultSchema = z.strictObject({
  evidence: z
    .array(relationshipEvidenceSchema)
    .max(DEPENDENCY_EVIDENCE_RESULTS_MAX),
});
export type DependencyEvidenceResult = z.infer<
  typeof dependencyEvidenceResultSchema
>;

export const dependencyEntrySchema = z.strictObject({
  ambiguous: z.boolean(),
  edgeId: edgeIdSchema,
  elementId: elementIdSchema,
  isExternal: z.boolean(),
  kind: elementKindSchema,
  label: elementLabelSchema,
  packageId: elementIdSchema.nullable(),
  qualifiedName: qualifiedNameSchema,
  resolution: resolutionQualitySchema,
  types: z.partialRecord(dependencyTypeSchema, z.number().int().positive()),
  weight: z.number().int().positive(),
});
export type DependencyEntry = z.infer<typeof dependencyEntrySchema>;

export const dependencyPageSchema = pageOf(dependencyEntrySchema).extend({
  items: z.array(dependencyEntrySchema).max(DEPENDENCY_PAGE_MAX),
  totals: z.strictObject({
    in: z.number().int().nonnegative(),
    out: z.number().int().nonnegative(),
    outExternal: z.number().int().nonnegative().nullable(),
  }),
});
export type DependencyPage = z.infer<typeof dependencyPageSchema>;
