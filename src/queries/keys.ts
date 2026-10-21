import type { DependencyDirection } from "@/protocol/enums";

export const queryKeys = {
  analysis: (analysisId: string) => ["analysis", analysisId] as const,

  analysisStatus: (analysisId: string) =>
    ["analysis", analysisId, "status"] as const,

  diagnostics: (analysisId: string) =>
    ["analysis", analysisId, "diagnostics"] as const,

  evolution: {
    all: (analysisId: string) => ["analysis", analysisId, "evolution"] as const,
    changeMetrics: (analysisId: string, elementId: string) =>
      [
        "analysis",
        analysisId,
        "evolution",
        "changeMetrics",
        elementId,
      ] as const,
    coChange: (analysisId: string, elementId: string, minSupport: number) =>
      [
        "analysis",
        analysisId,
        "evolution",
        "cochange",
        elementId,
        minSupport,
      ] as const,
    timeline: (analysisId: string, elementId: string) =>
      ["analysis", analysisId, "evolution", "timeline", elementId] as const,
  },

  hotspots: {
    all: (analysisId: string) => ["analysis", analysisId, "hotspots"] as const,
    ids: (analysisId: string) =>
      ["analysis", analysisId, "hotspots", "ids"] as const,
    list: (analysisId: string, percentile: number) =>
      ["analysis", analysisId, "hotspots", "list", percentile] as const,
    profile: (analysisId: string, elementId: string) =>
      ["analysis", analysisId, "hotspots", "profile", elementId] as const,
  },

  repository: {
    all: ["repository"] as const,
    currentAnalysis: (handle: string) =>
      ["repository", handle, "analysis", "current"] as const,
    describe: (handle: string) => ["repository", handle, "describe"] as const,
  },

  structure: {
    all: (analysisId: string) => ["analysis", analysisId, "structure"] as const,
    dependencies: (
      analysisId: string,
      elementId: string,
      direction: DependencyDirection
    ) =>
      [
        "analysis",
        analysisId,
        "structure",
        "element",
        elementId,
        "deps",
        direction,
      ] as const,
    dependencyEvidence: (analysisId: string, edgeId: string) =>
      [
        "analysis",
        analysisId,
        "structure",
        "edge",
        edgeId,
        "evidence",
      ] as const,
    element: (analysisId: string, elementId: string) =>
      ["analysis", analysisId, "structure", "element", elementId] as const,
    outline: (analysisId: string, parentId: string | null) =>
      ["analysis", analysisId, "structure", "outline", parentId] as const,
    search: (analysisId: string, query: string) =>
      ["analysis", analysisId, "structure", "search", query] as const,
    slice: (analysisId: string, params: Readonly<Record<string, unknown>>) =>
      ["analysis", analysisId, "structure", "slice", params] as const,
  },

  tour: (analysisId: string) => ["analysis", analysisId, "tour"] as const,
} as const;
