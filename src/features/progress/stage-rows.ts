import { typesInFiles } from "@/features/repository/count-phrase";
import {
  formatDuration,
  formatInteger,
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
  runEnded: boolean,
  interrupted: boolean
): RowKind => {
  switch (status) {
    case "RUNNING":
      return interrupted ? "failed" : "active";
    case "PENDING":
      if (hasTick && interrupted) {
        return "failed";
      }
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
  ticks: StageTicks,
  interrupted = false
): readonly StageRow[] => {
  const byStage = new Map(
    (report?.stages ?? []).map((stageReport) => [
      stageReport.stage,
      stageReport,
    ])
  );
  const runEnded =
    interrupted || (report !== undefined && report.status !== "RUNNING");
  return analysisStageSchema.options.map((stage) => {
    const stageReport = byStage.get(stage);
    const tick = ticks[stage];
    return {
      kind: rowKind(
        stageReport?.status ?? "PENDING",
        tick !== undefined,
        runEnded,
        interrupted
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

export interface UnitsParts {
  readonly after: string;
  readonly before: string;
  readonly completed: string;
}

const COMPLETED_TOKEN = "{completed}";

export const unitsParts = (
  stage: AnalysisStage,
  tick: ProgressTick
): UnitsParts => {
  const counted = tick.total === null || stage === "REPOSITORY_VALIDATION";
  const template = counted ? t.analysis.stage.found : t.analysis.stage.units;
  const params = {
    unit: progressUnitLabels[tick.unit],
    ...(tick.total === null ? {} : { total: tick.total }),
  };
  const split = template.indexOf(COMPLETED_TOKEN);
  return {
    after: interpolate(template.slice(split + COMPLETED_TOKEN.length), params),
    before: interpolate(template.slice(0, split), params),
    completed: formatInteger(tick.completed),
  };
};

export const unitsText = (stage: AnalysisStage, tick: ProgressTick): string => {
  const { after, before, completed } = unitsParts(stage, tick);
  return `${before}${completed}${after}`;
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
  const { files, javaFiles, selectedRoots, types } = counters;
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
  if (
    row.stage === "SOURCE_ANALYSIS" &&
    types !== undefined &&
    files !== undefined
  ) {
    return typesInFiles(t.analysis.stage.extracted, types, files);
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
