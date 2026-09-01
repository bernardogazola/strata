import { z } from "zod";

export const graphLevelSchema = z.enum([
  "PACKAGE",
  "TYPE",
  "TYPE_NEIGHBORHOOD",
]);
export type GraphLevel = z.infer<typeof graphLevelSchema>;

export const dependencyTypeSchema = z.enum([
  "IMPORTS",
  "EXTENDS",
  "IMPLEMENTS",
  "CALLS",
  "FIELD_TYPE",
  "PARAMETER_TYPE",
  "RETURN_TYPE",
  "ANNOTATION_REFERENCE",
  "MODULE_DEPENDENCY",
]);
export type DependencyType = z.infer<typeof dependencyTypeSchema>;

export const resolutionQualitySchema = z.enum([
  "RESOLVED",
  "INFERRED",
  "UNRESOLVED_EXTERNAL",
]);
export type ResolutionQuality = z.infer<typeof resolutionQualitySchema>;

export const relationshipConfidenceSchema = z.enum([
  "EXPLICIT",
  "PROVIDER_CONFIRMED",
  "INFERRED_STRONG",
  "INFERRED_WEAK",
  "UNKNOWN",
]);
export type RelationshipConfidence = z.infer<
  typeof relationshipConfidenceSchema
>;

export const typeKindSchema = z.enum([
  "CLASS",
  "INTERFACE",
  "ENUM",
  "RECORD",
  "ANNOTATION",
]);
export type TypeKind = z.infer<typeof typeKindSchema>;

export const elementKindSchema = z.enum(["PACKAGE", "TYPE", "METHOD", "FILE"]);
export type ElementKind = z.infer<typeof elementKindSchema>;

export const stageStatusSchema = z.enum([
  "PENDING",
  "RUNNING",
  "COMPLETED",
  "SKIPPED",
  "FAILED",
  "CANCELLED",
]);
export type StageStatus = z.infer<typeof stageStatusSchema>;

export const changeTypeSchema = z.enum([
  "ADD",
  "MODIFY",
  "DELETE",
  "RENAME",
  "COPY",
]);
export type ChangeType = z.infer<typeof changeTypeSchema>;

export const commitClassSchema = z.enum(["NORMAL", "MERGE", "BULK", "INITIAL"]);
export type CommitClass = z.infer<typeof commitClassSchema>;

export const dependencyDirectionSchema = z.enum(["IN", "OUT"]);
export type DependencyDirection = z.infer<typeof dependencyDirectionSchema>;

export const hotspotDimensionSchema = z.enum([
  "CHANGE_PRESSURE",
  "STRUCTURAL_DIFFICULTY",
  "ARCHITECTURAL_EXPOSURE",
  "CONTEXT",
]);
export type HotspotDimension = z.infer<typeof hotspotDimensionSchema>;

export const hotspotMetricSchema = z.enum([
  "COMMIT_COUNT",
  "CHURN",
  "LOC",
  "CC_SUM",
  "FAN_IN",
  "FAN_OUT",
  "PAGERANK",
  "RECENCY",
  "DISTINCT_AUTHORS",
]);
export type HotspotMetric = z.infer<typeof hotspotMetricSchema>;

export const tourTierSchema = z.enum([
  "ENTRY_POINT",
  "ARCHITECTURAL_CORE",
  "CONNECTOR",
  "HOTSPOT",
  "PERSISTENCE_BOUNDARY",
  "EVOLUTIONARY_CLUSTER",
]);
export type TourTier = z.infer<typeof tourTierSchema>;

export const entryPointKindSchema = z.enum([
  "MAIN",
  "FRAMEWORK_MAIN",
  "HTTP_ENDPOINT",
  "MESSAGING_ENDPOINT",
  "SCHEDULED",
  "CLI",
  "TEST_MAIN",
]);
export type EntryPointKind = z.infer<typeof entryPointKindSchema>;

export const tourReasonTagSchema = z.enum([
  "ENTRY_POINT",
  "API_BOUNDARY",
  "PERSISTENCE_BOUNDARY",
  "INTEGRATION_BOUNDARY",
  "CONFIGURATION",
  "CENTRAL",
  "HIGH_FAN_IN",
  "HIGH_FAN_OUT",
  "CONNECTOR",
  "CROSSES_PACKAGES",
  "IN_DEPENDENCY_CYCLE",
  "HOTSPOT",
  "FREQUENTLY_CHANGED",
  "HIGH_COMPLEXITY",
  "LARGE_TYPE",
  "RECENTLY_CHANGED",
  "COCHANGE_CLUSTER",
  "MANY_AUTHORS",
]);
export type TourReasonTag = z.infer<typeof tourReasonTagSchema>;

export const hotspotEvidenceCodeSchema = z.enum([
  "MODIFIED_IN_N_COMMITS",
  "HIGH_CHURN",
  "MANY_AUTHORS",
  "RECENTLY_CHANGED",
  "LARGE_TYPE",
  "HIGH_COMPLEXITY",
  "COMPLEX_METHOD",
  "HIGH_FAN_IN",
  "HIGH_FAN_OUT",
  "HIGH_CENTRALITY",
  "IN_DEPENDENCY_CYCLE",
  "IN_COCHANGE_CLUSTER",
  "COCHANGES_ACROSS_PACKAGES",
]);
export type HotspotEvidenceCode = z.infer<typeof hotspotEvidenceCodeSchema>;
