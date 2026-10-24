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

export const methodSummarySchema = z.strictObject({
  cc: z.number().int().nonnegative(),
  elementId: elementIdSchema,
  endLine: z.number().int().nonnegative(),
  signature: elementLabelSchema,
  startLine: z.number().int().nonnegative(),
});

export const elementDetailSchema = z.strictObject({
  elementId: elementIdSchema,
  endLine: z.number().int().nonnegative(),
  filePath: filePathSchema.nullable(),
  graph: z.strictObject({
    fanIn: z.number().int().nonnegative(),
    fanOut: z.number().int().nonnegative(),
    isEntryPoint: z.boolean(),
    pagerank: z.number().min(0).max(1),
    sccId: z.number().int().nonnegative().nullable(),
    sccSize: z.number().int().nonnegative().nullable(),
  }),
  isTest: z.boolean(),
  kind: elementKindSchema,
  methods: z.array(methodSummarySchema),
  metrics: z.strictObject({
    ccMax: z.number().int().nonnegative(),
    ccMean: z.number().nonnegative(),
    ccP90: z.number().nonnegative(),
    ccSum: z.number().int().nonnegative(),
    commentLines: z.number().int().nonnegative(),
    fieldCount: z.number().int().nonnegative(),
    loc: z.number().int().nonnegative(),
    methodCount: z.number().int().nonnegative(),
    nestingMax: z.number().int().nonnegative(),
    sourceLines: z.number().int().nonnegative(),
  }),
  modifiers: z.array(z.string().max(32)),
  packageId: elementIdSchema.nullable(),
  qualifiedName: qualifiedNameSchema,
  simpleName: elementLabelSchema,
  startLine: z.number().int().nonnegative(),
  typeKind: typeKindSchema.nullable(),
});
export type ElementDetail = z.infer<typeof elementDetailSchema>;

export const relationshipEvidenceSchema = z.strictObject({
  filePath: filePathSchema.nullable(),
  fromMethod: elementIdSchema.nullable(),
  line: z.number().int().positive().nullable(),
  resolution: resolutionQualitySchema,
  toMethod: elementIdSchema.nullable(),
});
export type RelationshipEvidence = z.infer<typeof relationshipEvidenceSchema>;

export const dependencyEvidenceResultSchema = z.strictObject({
  evidence: z.array(relationshipEvidenceSchema).max(50),
});

export const dependencyEntrySchema = z.strictObject({
  edgeId: edgeIdSchema,
  elementId: elementIdSchema,
  kind: elementKindSchema,
  label: elementLabelSchema,
  resolution: resolutionQualitySchema,
  types: z.partialRecord(dependencyTypeSchema, z.number().int().positive()),
  weight: z.number().int().nonnegative(),
});
export type DependencyEntry = z.infer<typeof dependencyEntrySchema>;

export const dependencyPageSchema = pageOf(dependencyEntrySchema).extend({
  totals: z.strictObject({
    in: z.number().int().nonnegative(),
    out: z.number().int().nonnegative(),
  }),
  truncated: z.boolean(),
});
export type DependencyPage = z.infer<typeof dependencyPageSchema>;
