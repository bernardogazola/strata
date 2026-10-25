import { notFound } from "@tanstack/react-router";
import { z } from "zod";
import {
  type DependencyDirection,
  dependencyDirectionSchema,
  type ElementKind,
  graphLevelSchema,
  resolutionQualitySchema,
} from "@/protocol/enums";
import type { SearchHit } from "@/protocol/graph";
import {
  analysisIdSchema,
  elementIdSchema,
  graphNodeIdSchema,
  type RepositoryHandle,
  repositoryHandleSchema,
} from "@/protocol/primitives";
import type { GraphViewParams } from "@/queries/options";

export const inspectorTabSchema = z.enum([
  "overview",
  "dependencies",
  "history",
]);
export type InspectorTab = z.infer<typeof inspectorTabSchema>;

const PERCENTILE_MIN = 50;
const PERCENTILE_MAX = 99;

export const REPOSITORY_SEARCH_DEFAULTS = {
  direction: "OUT",
  minQuality: "INFERRED",
  percentile: 75,
  tab: "overview",
} as const;

export const repositorySearchSchema = z.object({
  analysis: analysisIdSchema.optional().catch(undefined),

  direction: dependencyDirectionSchema
    .default(REPOSITORY_SEARCH_DEFAULTS.direction)
    .catch(REPOSITORY_SEARCH_DEFAULTS.direction),

  focus: graphNodeIdSchema.optional().catch(undefined),

  level: graphLevelSchema.default("PACKAGE").catch("PACKAGE"),

  minQuality: resolutionQualitySchema
    .default(REPOSITORY_SEARCH_DEFAULTS.minQuality)
    .catch(REPOSITORY_SEARCH_DEFAULTS.minQuality),

  parent: elementIdSchema.optional().catch(undefined),

  percentile: z
    .number()
    .int()
    .min(PERCENTILE_MIN)
    .max(PERCENTILE_MAX)
    .default(REPOSITORY_SEARCH_DEFAULTS.percentile)
    .catch(REPOSITORY_SEARCH_DEFAULTS.percentile),

  tab: inspectorTabSchema
    .default(REPOSITORY_SEARCH_DEFAULTS.tab)
    .catch(REPOSITORY_SEARCH_DEFAULTS.tab),
});

export type RepositorySearch = z.infer<typeof repositorySearchSchema>;

export const resolveGraphView = ({
  level,
  parent,
}: Pick<RepositorySearch, "level" | "parent">): GraphViewParams => {
  if (level !== "TYPE" || parent === undefined) {
    return { level: "PACKAGE" };
  }
  return { level, parentId: parent };
};

export type SearchTransition = (prev: RepositorySearch) => RepositorySearch;

export const changesView = (
  prev: RepositorySearch,
  next: RepositorySearch
): boolean => {
  if (prev.analysis !== next.analysis) {
    return true;
  }
  const before = resolveGraphView(prev);
  const after = resolveGraphView(next);
  return before.level !== after.level || before.parentId !== after.parentId;
};

export const startAnalysis =
  (analysisId: string): SearchTransition =>
  () => ({
    ...REPOSITORY_SEARCH_DEFAULTS,
    analysis: analysisId,
    level: "PACKAGE",
  });

export const leaveAnalysis: SearchTransition = () => ({
  ...REPOSITORY_SEARCH_DEFAULTS,
  level: "PACKAGE",
});

export const selectElement =
  (elementId: string | null): SearchTransition =>
  (prev) => ({ ...prev, focus: elementId ?? undefined });

export const expandPackage =
  (packageId: string): SearchTransition =>
  (prev) => ({ ...prev, focus: undefined, level: "TYPE", parent: packageId });

export const backToLevelOne: SearchTransition = (prev) => ({
  ...prev,
  focus: prev.parent,
  level: "PACKAGE",
  parent: undefined,
});

export const selectWhereDrawn =
  (elementId: string, kind: ElementKind): SearchTransition =>
  (prev) =>
    kind === "PACKAGE" && resolveGraphView(prev).level !== "PACKAGE"
      ? { ...prev, focus: elementId, level: "PACKAGE", parent: undefined }
      : { ...prev, focus: elementId };

export type RevealTarget = Pick<SearchHit, "elementId" | "kind" | "packageId">;

export const revealElement =
  (target: RevealTarget): SearchTransition =>
  (prev) => {
    if (target.kind === "PACKAGE") {
      return selectWhereDrawn(target.elementId, target.kind)(prev);
    }
    if (target.kind !== "TYPE" || target.packageId === null) {
      return prev;
    }
    return {
      ...prev,
      focus: target.elementId,
      level: "TYPE",
      parent: target.packageId,
    };
  };

export const revealSearchHit = (hit: SearchHit): SearchTransition =>
  revealElement(hit);

export const selectTab =
  (tab: InspectorTab): SearchTransition =>
  (prev) => ({ ...prev, tab });

export const selectDirection =
  (direction: DependencyDirection): SearchTransition =>
  (prev) => ({ ...prev, direction });

export const isRevealable = (target: RevealTarget): boolean =>
  target.kind === "PACKAGE" ||
  (target.kind === "TYPE" && target.packageId !== null);

export const parseRepositoryParams = ({
  handle,
}: {
  readonly handle: string;
}): { readonly handle: RepositoryHandle } => {
  const parsed = repositoryHandleSchema.safeParse(handle);
  if (!parsed.success) {
    throw notFound();
  }
  return { handle: parsed.data };
};
