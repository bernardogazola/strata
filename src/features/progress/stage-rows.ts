import {
  formatDuration,
  formatPreciseDuration,
  interpolate,
  plural,
} from "@/i18n/format";
import {
  analysisStageLabels,
  progressUnitLabels,
  stageStatusLabels,
} from "@/i18n/labels";
import { t } from "@/i18n/messages";
import {
  type AnalysisStage,
  analysisStageSchema,
  type ProgressTick,
} from "@/protocol/analysis";
import type { StageStatus } from "@/protocol/enums";
import type {
  AnalysisStageReport,
  AnalysisStatusReport,
} from "@/protocol/repository";

export type StageTicks = Readonly<Partial<Record<AnalysisStage, ProgressTick>>>;

export type RowKind =
  | "active"
  | "cancelled"
  | "completed"
  | "failed"
  | "notRun"
  | "pending"
  | "skipped";

export type SettledKind = Exclude<RowKind, "active">;

type TerminalKind = Exclude<SettledKind, "notRun" | "pending">;

export interface StageRow {
  readonly kind: RowKind;
  readonly stage: AnalysisStage;
  readonly stageReport: AnalysisStageReport | undefined;
  readonly tick: ProgressTick | undefined;
}

export interface Announcement {
  readonly stage: AnalysisStage;
  readonly text: string;
}

const TERMINAL_KINDS = {
  CANCELLED: "cancelled",
  COMPLETED: "completed",
  FAILED: "failed",
  SKIPPED: "skipped",
} as const satisfies Partial<Record<StageStatus, TerminalKind>>;

const TERMINAL_STATUS = {
  cancelled: "CANCELLED",
  completed: "COMPLETED",
  failed: "FAILED",
  skipped: "SKIPPED",
} as const satisfies Record<TerminalKind, StageStatus>;

const isTerminalKind = (kind: RowKind): kind is TerminalKind =>
  Object.hasOwn(TERMINAL_STATUS, kind);

const rowKind = (
  status: StageStatus,
  hasTick: boolean,
  runEnded: boolean
): RowKind => {
  switch (status) {
    case "RUNNING":
      return "active";
    case "PENDING":
      if (runEnded) {
        return "notRun";
      }
      return hasTick ? "active" : "pending";
    default:
      return TERMINAL_KINDS[status];
  }
};

export const deriveRows = (
  report: AnalysisStatusReport | undefined,
  ticks: StageTicks
): readonly StageRow[] => {
  const byStage = new Map(
    (report?.stages ?? []).map((stageReport) => [
      stageReport.stage,
      stageReport,
    ])
  );
  const runEnded = report !== undefined && report.status !== "RUNNING";
  return analysisStageSchema.options.map((stage) => {
    const stageReport = byStage.get(stage);
    const tick = ticks[stage];
    return {
      kind: rowKind(
        stageReport?.status ?? "PENDING",
        tick !== undefined,
        runEnded
      ),
      stage,
      stageReport,
      tick,
    };
  });
};

export const announcementsBetween = (
  before: readonly RowKind[],
  after: readonly RowKind[]
): readonly Announcement[] =>
  analysisStageSchema.options.flatMap((stage, position) => {
    const previous = before[position];
    const current = after[position];
    const label = analysisStageLabels[stage];
    if (current === "active" && previous === "pending") {
      return [
        {
          stage,
          text: interpolate(t.analysis.stage.announceStart, { stage: label }),
        },
      ];
    }
    const wasOpen = previous === "pending" || previous === "active";
    if (current !== undefined && isTerminalKind(current) && wasOpen) {
      return [
        {
          stage,
          text: interpolate(t.analysis.stage.announceEnd, {
            stage: label,
            status: stageStatusLabels[TERMINAL_STATUS[current]],
          }),
        },
      ];
    }
    return [];
  });

export const unitsText = (stage: AnalysisStage, tick: ProgressTick): string => {
  const unit = progressUnitLabels[tick.unit];
  if (tick.total === null || stage === "REPOSITORY_VALIDATION") {
    return interpolate(t.analysis.stage.found, {
      completed: tick.completed,
      unit,
    });
  }
  return interpolate(t.analysis.stage.units, {
    completed: tick.completed,
    total: tick.total,
    unit,
  });
};

export const barFraction = (
  stage: AnalysisStage,
  tick: ProgressTick | undefined
): number | null => {
  if (
    tick === undefined ||
    tick.total === null ||
    tick.total === 0 ||
    stage === "REPOSITORY_VALIDATION"
  ) {
    return null;
  }
  return Math.min(1, tick.completed / tick.total);
};

export const elapsedText = (
  stageReport: AnalysisStageReport | undefined,
  now: number
): string | null => {
  if (stageReport === undefined || stageReport.startedAt === null) {
    return null;
  }
  return formatDuration(Math.max(0, now - Date.parse(stageReport.startedAt)));
};

export const completedSummary = (row: StageRow): string => {
  const counters = row.stageReport?.counters ?? {};
  const { javaFiles, selectedRoots } = counters;
  if (row.stage === "REPOSITORY_VALIDATION" && javaFiles !== undefined) {
    return interpolate(plural(javaFiles, t.analysis.stage.validated), {
      count: javaFiles,
    });
  }
  if (row.stage === "LAYOUT_DISCOVERY" && selectedRoots !== undefined) {
    return interpolate(plural(selectedRoots, t.analysis.stage.layout), {
      count: selectedRoots,
    });
  }
  return stageStatusLabels.COMPLETED;
};

export const stageDurationText = (row: StageRow): string | null => {
  const durationMs = row.stageReport?.durationMs ?? null;
  return durationMs === null
    ? null
    : interpolate(t.analysis.stage.duration, {
        duration: formatPreciseDuration(durationMs),
      });
};

export const SETTLED_TEXT = {
  cancelled: stageStatusLabels.CANCELLED,
  failed: stageStatusLabels.FAILED,
  notRun: t.analysis.stage.notRun,
  pending: stageStatusLabels.PENDING,
  skipped: stageStatusLabels.SKIPPED,
} as const satisfies Record<Exclude<SettledKind, "completed">, string>;

const CONCURRENT_STAGES: ReadonlySet<AnalysisStage> = new Set([
  "SOURCE_ANALYSIS",
  "GIT_HISTORY_CHEAP",
]);

export const runDurationMs = (report: AnalysisStatusReport): number => {
  let sequential = 0;
  let concurrent = 0;
  for (const { durationMs, stage } of report.stages) {
    if (durationMs === null) {
      continue;
    }
    if (CONCURRENT_STAGES.has(stage)) {
      concurrent = Math.max(concurrent, durationMs);
    } else {
      sequential += durationMs;
    }
  }
  return sequential + concurrent;
};
