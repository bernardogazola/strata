import { IconCheck, IconPlayerStopFilled, IconX } from "@tabler/icons-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { analysisStageLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import type { AnalysisStage, ProgressTick } from "@/protocol/analysis";
import type { AnalysisStatusReport } from "@/protocol/repository";
import { PopDigits } from "./pop-digits";
import {
  type Announcement,
  announcementsBetween,
  barFraction,
  completedSummary,
  deriveRows,
  elapsedText,
  type RowKind,
  SETTLED_TEXT,
  type SettledKind,
  type StageRow,
  type StageTicks,
  stageDurationText,
  unitsParts,
} from "./stage-rows";

export interface StageProgressProps {
  readonly interrupted?: boolean;
  readonly now?: number;
  readonly report: AnalysisStatusReport | undefined;
  readonly ticks: StageTicks;
}

const ELAPSED_REFRESH_MS = 1000;

interface Snapshot {
  readonly hasReport: boolean;
  readonly kinds: readonly RowKind[];
}

const useStageAnnouncements = (
  rows: readonly StageRow[],
  hasReport: boolean
): readonly Announcement[] => {
  const kindsKey = rows.map((row) => row.kind).join(",");
  const previous = useRef<Snapshot | null>(null);
  const [announcements, setAnnouncements] = useState<readonly Announcement[]>(
    []
  );

  useEffect(() => {
    const kinds = kindsKey.split(",") as RowKind[];
    const before = previous.current;
    previous.current = { hasReport, kinds };
    if (before === null || !before.hasReport) {
      return;
    }
    const next = announcementsBetween(before.kinds, kinds);
    if (next.length > 0) {
      setAnnouncements(next);
    }
  }, [kindsKey, hasReport]);

  return announcements;
};

const NO_STAGES: ReadonlySet<AnalysisStage> = new Set();

const useCompletedLive = (
  rows: readonly StageRow[],
  hasReport: boolean
): ReadonlySet<AnalysisStage> => {
  const [baseline, setBaseline] = useState<ReadonlySet<AnalysisStage> | null>(
    null
  );
  const completed = rows
    .filter((row) => row.kind === "completed")
    .map((row) => row.stage);
  if (baseline === null) {
    if (hasReport) {
      setBaseline(new Set(completed));
    }
    return NO_STAGES;
  }
  return new Set(completed.filter((stage) => !baseline.has(stage)));
};

const useClock = (active: boolean, injected: number | undefined): number => {
  const [clock, setClock] = useState(() => Date.now());

  useEffect(() => {
    if (!active || injected !== undefined) {
      return;
    }
    setClock(Date.now());
    const interval = setInterval(
      () => setClock(Date.now()),
      ELAPSED_REFRESH_MS
    );
    return () => clearInterval(interval);
  }, [active, injected]);

  return injected ?? clock;
};

const SPINNER = (
  <svg
    aria-hidden="true"
    className="size-4 motion-safe:animate-spin"
    fill="none"
    viewBox="0 0 16 16"
  >
    <circle
      className="stroke-accent-strong/15 motion-reduce:stroke-accent-strong"
      cx="8"
      cy="8"
      r="6"
      strokeWidth="2"
    />
    <path
      className="stroke-accent-strong motion-reduce:hidden"
      d="M8 2A6 6 0 0 1 14 8"
      strokeLinecap="round"
      strokeWidth="2"
    />
  </svg>
);

const DOT = <span className="size-1.5 rounded-full bg-subtle-foreground/50" />;

const STATUS_ICONS = {
  active: SPINNER,
  cancelled: (
    <span className="flex size-4 items-center justify-center rounded-full bg-muted text-muted-foreground">
      <IconPlayerStopFilled size={8} />
    </span>
  ),
  completed: (
    <span className="flex size-4 items-center justify-center rounded-full bg-success-soft text-success">
      <IconCheck size={11} stroke={2} />
    </span>
  ),
  failed: (
    <span className="flex size-4 items-center justify-center rounded-full bg-destructive-soft text-destructive">
      <IconX size={10} stroke={2} />
    </span>
  ),
  notRun: DOT,
  pending: DOT,
  skipped: (
    <svg aria-hidden="true" className="size-4" fill="none" viewBox="0 0 16 16">
      <circle
        className="stroke-subtle-foreground"
        cx="8"
        cy="8"
        r="6.25"
        strokeDasharray="2 2"
        strokeWidth="1.5"
      />
    </svg>
  ),
} as const satisfies Record<RowKind, ReactNode>;

interface IconSlotProps {
  readonly entering?: boolean;
  readonly kind: RowKind;
}

function IconSlot({ entering = false, kind }: IconSlotProps) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "flex h-5 w-4 shrink-0 items-center justify-center",
        entering && "animate-check-in"
      )}
    >
      {STATUS_ICONS[kind]}
    </span>
  );
}

interface UnitsCountProps {
  readonly stage: AnalysisStage;
  readonly tick: ProgressTick;
}

function UnitsCount({ stage, tick }: UnitsCountProps) {
  const { after, before, completed } = unitsParts(stage, tick);
  return (
    <span className="text-muted-foreground text-xs tabular-nums">
      {before}
      <PopDigits value={completed} />
      {after}
    </span>
  );
}

interface ActiveRowProps {
  readonly now: number;
  readonly row: StageRow;
}

function ActiveRow({ now, row }: ActiveRowProps) {
  const fraction = barFraction(row.stage, row.tick);
  return (
    <li
      className="flex items-start gap-3 bg-accent-strong/3 px-4 py-3.5"
      data-stage={row.stage}
    >
      <IconSlot kind="active" />
      <div className="flex min-w-0 grow flex-col gap-2">
        <div className="flex items-baseline gap-4">
          <span className="grow font-medium text-foreground text-sm/5">
            <span className="text-shimmer">
              {analysisStageLabels[row.stage]}
            </span>
          </span>
          <span className="flex shrink-0 items-baseline gap-3">
            {row.tick === undefined ? null : (
              <UnitsCount stage={row.stage} tick={row.tick} />
            )}
            <span className="whitespace-nowrap text-muted-foreground text-xs tabular-nums">
              {elapsedText(row.stageReport, now)}
            </span>
          </span>
        </div>
        {fraction === null ? null : (
          <div
            aria-hidden="true"
            className="h-1 overflow-hidden rounded-[2px] bg-accent-strong/10"
          >
            <div
              className="h-full origin-left bg-accent-strong transition-transform duration-200 ease-out-quart motion-reduce:transition-none"
              style={{ transform: `scaleX(${fraction})` }}
            />
          </div>
        )}
      </div>
    </li>
  );
}

interface SettledRowProps {
  readonly completedLive: boolean;
  readonly kind: SettledKind;
  readonly row: StageRow;
}

function SettledRow({ completedLive, kind, row }: SettledRowProps) {
  const duration = kind === "completed" ? stageDurationText(row) : null;
  return (
    <li className="flex items-center gap-3 px-4 py-2.5" data-stage={row.stage}>
      <IconSlot entering={completedLive} kind={kind} />
      <span className="grow text-muted-foreground text-sm/5">
        {analysisStageLabels[row.stage]}
      </span>
      {kind === "completed" ? (
        <span className="text-right text-muted-foreground text-xs tabular-nums">
          <span>{completedSummary(row)}</span>
          {duration === null ? null : (
            <>
              {" "}
              <span>{duration}</span>
            </>
          )}
        </span>
      ) : (
        <span
          className={
            kind === "failed"
              ? "text-destructive text-xs"
              : "text-muted-foreground text-xs"
          }
        >
          {SETTLED_TEXT[kind]}
        </span>
      )}
    </li>
  );
}

export function StageProgress({
  interrupted = false,
  now,
  report,
  ticks,
}: StageProgressProps) {
  const rows = deriveRows(report, ticks, interrupted);
  const anyActive = rows.some((row) => row.kind === "active");
  const anySkipped = rows.some((row) => row.kind === "skipped");
  const currentTime = useClock(anyActive, now);
  const announcements = useStageAnnouncements(rows, report !== undefined);
  const completedLive = useCompletedLive(rows, report !== undefined);

  return (
    <div className="flex flex-col gap-3">
      <ol
        aria-label={t.analysis.stages.toggle}
        className="flex flex-col divide-y divide-border overflow-hidden rounded-lg border border-card-border bg-card py-1 shadow-card"
      >
        {rows.map((row) =>
          row.kind === "active" ? (
            <ActiveRow key={row.stage} now={currentTime} row={row} />
          ) : (
            <SettledRow
              completedLive={completedLive.has(row.stage)}
              key={row.stage}
              kind={row.kind}
              row={row}
            />
          )
        )}
      </ol>
      {anySkipped ? (
        <p className="text-muted-foreground text-xs">
          {t.analysis.stage.skippedNote}
        </p>
      ) : null}
      <div aria-live="polite" className="sr-only">
        {announcements.map((announcement) => (
          <p key={announcement.stage}>{announcement.text}</p>
        ))}
      </div>
    </div>
  );
}
