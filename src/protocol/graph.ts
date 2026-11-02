import { z } from "zod";
import {
  type DependencyType,
  dependencyTypeSchema,
  type ElementKind,
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
  graphNodeIdSchema,
  methodIdSchema,
  packageIdSchema,
  packageOrTypeIdSchema,
  pageOf,
  qualifiedNameSchema,
  rootRelativePathSchema,
  typeIdSchema,
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

const ID_PREFIX_OF_KIND = {
  FILE: "file:",
  METHOD: "method:",
  PACKAGE: "pkg:",
  TYPE: "type:",
} as const satisfies Record<ElementKind, string>;

const kindMatchesId = (kind: ElementKind, id: string): boolean =>
  id.startsWith(ID_PREFIX_OF_KIND[kind]);

const isTypeEdge = (type: DependencyType): boolean =>
  type !== "MODULE_DEPENDENCY";

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

export const elementDetailSchema = z
  .strictObject({
    elementId: packageOrTypeIdSchema,
    filePath: rootRelativePathSchema.nullable(),
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
    modifiers: z.array(z.string().min(1).max(32)),
    packageId: packageIdSchema.nullable(),
    qualifiedName: qualifiedNameSchema,
    typeKind: typeKindSchema.nullable(),
  })
  .refine(({ elementId, kind }) => kindMatchesId(kind, elementId), {
    error: "o kind de um elemento descrito é o kind do seu id",
    path: ["kind"],
  })
  .refine(
    (detail) =>
      detail.kind === "PACKAGE"
        ? detail.typeKind === null &&
          detail.packageId === null &&
          detail.filePath === null &&
          detail.lines === null &&
          detail.metrics.ccP90 === null &&
          detail.methodTotal === 0
        : detail.typeKind !== null &&
          detail.packageId !== null &&
          detail.filePath !== null &&
          detail.metrics.ccP90 !== null,
    {
      error:
        "um PACKAGE não tem typeKind, packageId, filePath, lines, ccP90 nem method; um TYPE tem typeKind, packageId, filePath e ccP90",
      path: ["kind"],
    }
  )
  .refine(
    ({ methods, methodTotal }) =>
      methods.length === Math.min(methodTotal, ELEMENT_METHODS_MAX),
    {
      error: "methods tem min(methodTotal, ELEMENT_METHODS_MAX) linhas",
      path: ["methods"],
    }
  );
export type ElementDetail = z.infer<typeof elementDetailSchema>;

export const evidenceMethodSchema = z.strictObject({
  elementId: methodIdSchema,
  ownerId: typeIdSchema,
  ownerLabel: elementLabelSchema,
  signature: elementLabelSchema,
});
export type EvidenceMethod = z.infer<typeof evidenceMethodSchema>;

export const relationshipEvidenceSchema = z
  .strictObject({
    filePath: rootRelativePathSchema.nullable(),
    fromMethod: evidenceMethodSchema.nullable(),
    line: z.number().int().positive().nullable(),
    resolution: resolutionQualitySchema,
    toMethod: evidenceMethodSchema.nullable(),
    type: dependencyTypeSchema,
  })
  .refine(({ type }) => isTypeEdge(type), {
    error: "uma ocorrência é uma dependência do grafo de tipos",
    path: ["type"],
  })
  .refine(({ toMethod, type }) => toMethod === null || type === "CALLS", {
    error: "só uma ocorrência CALLS nomeia o método chamado",
    path: ["toMethod"],
  });
export type RelationshipEvidence = z.infer<typeof relationshipEvidenceSchema>;

export const dependencyEvidenceResultSchema = z.strictObject({
  evidence: z
    .array(relationshipEvidenceSchema)
    .min(1)
    .max(DEPENDENCY_EVIDENCE_RESULTS_MAX),
});
export type DependencyEvidenceResult = z.infer<
  typeof dependencyEvidenceResultSchema
>;

export const dependencyEntrySchema = z
  .strictObject({
    ambiguous: z.boolean(),
    edgeId: edgeIdSchema,
    elementId: packageOrTypeIdSchema,
    isExternal: z.boolean(),
    kind: elementKindSchema,
    label: elementLabelSchema,
    packageId: packageIdSchema.nullable(),
    qualifiedName: qualifiedNameSchema,
    resolution: resolutionQualitySchema,
    types: z.partialRecord(dependencyTypeSchema, z.number().int().positive()),
    weight: z.number().int().positive(),
  })
  .refine(({ elementId, kind }) => kindMatchesId(kind, elementId), {
    error: "o kind de uma ponta de dependência é o kind do seu id",
    path: ["kind"],
  })
  .refine(
    ({ ambiguous, isExternal, resolution }) =>
      !ambiguous || (isExternal && resolution === "UNRESOLVED_EXTERNAL"),
    {
      error: "só uma ponta externa UNRESOLVED_EXTERNAL é ambígua",
      path: ["ambiguous"],
    }
  )
  .refine(
    ({ isExternal, kind, packageId, resolution }) =>
      isExternal
        ? kind === "TYPE" && packageId === null
        : resolution !== "UNRESOLVED_EXTERNAL" &&
          (kind === "PACKAGE") === (packageId === null),
    {
      error:
        "uma ponta externa é um TYPE sem pacote; uma ponta interna nunca é UNRESOLVED_EXTERNAL e tem pacote se e somente se for um TYPE",
      path: ["isExternal"],
    }
  )
  .refine(
    ({ types }) => {
      const present = Object.keys(types) as DependencyType[];
      return present.length > 0 && present.every(isTypeEdge);
    },
    {
      error:
        "uma dependência tem pelo menos um tipo de dependência do grafo de tipos",
      path: ["types"],
    }
  );
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
