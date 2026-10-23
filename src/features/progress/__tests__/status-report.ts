import {
  type AnalysisCompleteness,
  type AnalysisStage,
  type AnalysisStatus,
  analysisStageSchema,
} from "@/protocol/analysis";
import type { ProtocolError } from "@/protocol/errors";
import {
  type AnalysisStageReport,
  type AnalysisStatusReport,
  analysisStatusReportSchema,
} from "@/protocol/repository";

export const ANALYSIS_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";
export const STARTED_AT = "2024-01-02T03:04:05Z";
export const FINISHED_AT = "2024-01-02T03:04:09Z";

export type StageOverrides = Partial<
  Record<AnalysisStage, Partial<Omit<AnalysisStageReport, "stage">>>
>;

export interface StatusReportInput {
  readonly completeness?: Partial<AnalysisCompleteness>;
  readonly error?: ProtocolError | null;
  readonly stages?: StageOverrides;
  readonly status?: AnalysisStatus;
}

const EMPTY_COMPLETENESS: AnalysisCompleteness = {
  historyTruncatedAtCommits: null,
  parseFailures: 0,
  shallowClone: false,
  skippedFiles: 0,
  totalFiles: 0,
};

export const statusReport = ({
  completeness = {},
  error = null,
  stages = {},
  status = "RUNNING",
}: StatusReportInput = {}): AnalysisStatusReport =>
  analysisStatusReportSchema.parse({
    analysisId: ANALYSIS_ID,
    completeness: { ...EMPTY_COMPLETENESS, ...completeness },
    error,
    finishedAt: status === "RUNNING" ? null : FINISHED_AT,
    headCommitId: null,
    stages: analysisStageSchema.options.map((stage) => ({
      counters: {},
      durationMs: null,
      stage,
      startedAt: null,
      status: "PENDING",
      ...stages[stage],
    })),
    startedAt: STARTED_AT,
    status,
  });

export const COMPLETED_STAGES: StageOverrides = {
  EVOLUTIONARY_COUPLING: {
    durationMs: 0,
    startedAt: STARTED_AT,
    status: "SKIPPED",
  },
  GIT_HISTORY_CHEAP: {
    durationMs: 0,
    startedAt: STARTED_AT,
    status: "SKIPPED",
  },
  GIT_HISTORY_DETAILED: {
    durationMs: 0,
    startedAt: STARTED_AT,
    status: "SKIPPED",
  },
  GUIDED_TOUR: { durationMs: 0, startedAt: STARTED_AT, status: "SKIPPED" },
  HOTSPOT_PROFILES: { durationMs: 0, startedAt: STARTED_AT, status: "SKIPPED" },
  LAYOUT_DISCOVERY: {
    counters: { selectedRoots: 3, sourceRoots: 4 },
    durationMs: 2,
    startedAt: STARTED_AT,
    status: "COMPLETED",
  },
  PERSISTENCE: { durationMs: 4, startedAt: STARTED_AT, status: "COMPLETED" },
  REPOSITORY_VALIDATION: {
    counters: { files: 25, javaFiles: 10, warnings: 0 },
    durationMs: 24,
    startedAt: STARTED_AT,
    status: "COMPLETED",
  },
  SOURCE_ANALYSIS: {
    counters: { files: 9, types: 12 },
    durationMs: 305,
    startedAt: STARTED_AT,
    status: "COMPLETED",
  },
  STRUCTURAL_GRAPH: { durationMs: 0, startedAt: STARTED_AT, status: "SKIPPED" },
};
