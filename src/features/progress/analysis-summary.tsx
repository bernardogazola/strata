import {
  IconCheck,
  IconChevronRight,
  IconPlayerStopFilled,
} from "@tabler/icons-react";
import { type ReactNode, useId } from "react";
import { SECONDARY_ACTION_CLASS } from "@/components/action-styles";
import { Button } from "@/components/ui/button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { ErrorSurface } from "@/features/errors/error-surface";
import { formatPreciseDuration, interpolate, plural } from "@/i18n/format";
import { t } from "@/i18n/messages";
import type {
  AnalysisStageReport,
  AnalysisStatusReport,
} from "@/protocol/repository";
import type { AnalysisWarning } from "@/state/app-store";
import { CompletenessCaveat } from "./completeness-caveat";
import { StageProgress } from "./stage-progress";
import { runDurationMs, type StageTicks } from "./stage-rows";

export interface AnalysisSummaryProps {
  readonly children?: ReactNode;
  readonly onAnalyzeAgain: () => void;
  readonly report: AnalysisStatusReport;
  readonly warnings: readonly AnalysisWarning[];
}

const NO_TICKS: StageTicks = {};

const headlineText = (report: AnalysisStatusReport): string => {
  switch (report.status) {
    case "COMPLETED": {
      const { skippedFiles, totalFiles } = report.completeness;
      if (skippedFiles > 0) {
        return interpolate(t.analysis.completed.headlinePartial, {
          analyzed: totalFiles - skippedFiles,
          total: totalFiles,
        });
      }
      return interpolate(plural(totalFiles, t.analysis.completed.headline), {
        total: totalFiles,
      });
    }
    case "CANCELLED":
      return t.analysis.cancelled.headline;
    case "FAILED":
      return t.analysis.failed.headline;
    case "RUNNING":
      return t.header.analysis.RUNNING;
    default:
      return report.status satisfies never;
  }
};

interface MetaProps {
  readonly report: AnalysisStatusReport;
}

function Meta({ report }: MetaProps) {
  const duration = formatPreciseDuration(runDurationMs(report));
  if (report.status === "COMPLETED") {
    return (
      <p className="flex items-center gap-1.5 text-success text-xs">
        <span
          aria-hidden="true"
          className="flex size-3.5 items-center justify-center rounded-full bg-success-soft"
        >
          <IconCheck size={9} stroke={2.5} />
        </span>
        {interpolate(t.analysis.completed.meta, { duration })}
      </p>
    );
  }
  if (report.status === "CANCELLED") {
    return (
      <p className="flex items-center gap-1.5 text-muted-foreground text-xs">
        <span
          aria-hidden="true"
          className="flex size-3.5 items-center justify-center rounded-full bg-muted"
        >
          <IconPlayerStopFilled size={7} />
        </span>
        {interpolate(t.analysis.cancelled.meta, { duration })}
      </p>
    );
  }
  return null;
}

interface KeyedWarning {
  readonly key: string;
  readonly warning: AnalysisWarning;
}

const withKeys = (warnings: readonly AnalysisWarning[]): KeyedWarning[] => {
  const seen = new Map<string, number>();
  return warnings.map((warning) => {
    const base = `${warning.error.code}:${JSON.stringify(warning.error.params ?? {})}`;
    const occurrence = seen.get(base) ?? 0;
    seen.set(base, occurrence + 1);
    return { key: `${base}#${occurrence}`, warning };
  });
};

interface WarningsProps {
  readonly report: AnalysisStatusReport;
  readonly warnings: readonly AnalysisWarning[];
}

function Warnings({ report, warnings }: WarningsProps) {
  const headingId = useId();
  const skipped =
    report.status === "COMPLETED" ? report.completeness.skippedFiles : 0;
  if (warnings.length === 0 && skipped === 0) {
    return null;
  }

  const shown = warnings.length;
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-2.5 pt-1">
      <div className="flex items-baseline justify-between gap-4">
        <h2 className="font-medium text-base text-foreground" id={headingId}>
          {skipped > 0
            ? interpolate(plural(skipped, t.analysis.warnings.heading), {
                count: skipped,
              })
            : t.analysis.warnings.label}
        </h2>
        {skipped > 0 ? (
          <span className="text-muted-foreground text-xs">
            {t.analysis.warnings.label}
          </span>
        ) : null}
      </div>
      {shown === 0 ? null : (
        <ul className="flex flex-col divide-y divide-border rounded-lg border border-card-border bg-card shadow-card">
          {withKeys(warnings).map(({ key, warning }) => (
            <li className="px-3.5 py-3" key={key}>
              <ErrorSurface
                announce={false}
                error={warning.error}
                variant="inline"
              />
            </li>
          ))}
        </ul>
      )}
      {shown < skipped ? (
        <p className="text-muted-foreground text-xs">
          {interpolate(t.analysis.warnings.sample, {
            shown,
            total: skipped,
          })}
        </p>
      ) : null}
    </section>
  );
}

const countStages = (
  stages: readonly AnalysisStageReport[],
  status: AnalysisStageReport["status"]
): number => stages.filter((stage) => stage.status === status).length;

interface StagesProps {
  readonly report: AnalysisStatusReport;
}

function Stages({ report }: StagesProps) {
  const completed = countStages(report.stages, "COMPLETED");
  const skipped = countStages(report.stages, "SKIPPED");
  const { summary } = t.analysis.stages;
  return (
    <Collapsible className="flex flex-col gap-2">
      <CollapsibleTrigger className="group flex h-10 w-full items-center justify-between gap-4 rounded-lg border border-card-border bg-card px-3.5 shadow-card transition-colors duration-150 ease-out-quart hover:bg-muted/60">
        <span className="flex items-center gap-2.5">
          <IconChevronRight
            aria-hidden="true"
            className="text-muted-foreground transition-transform duration-150 ease-out-quart group-data-panel-open:rotate-90 motion-reduce:transition-none"
            size={14}
            stroke={1.5}
          />
          <span className="font-medium text-foreground text-sm">
            {t.analysis.stages.toggle}
          </span>
        </span>
        <span className="text-muted-foreground text-xs tabular-nums">
          {interpolate(summary.template, {
            completed: interpolate(plural(completed, summary.completed), {
              count: completed,
            }),
            skipped: interpolate(plural(skipped, summary.skipped), {
              count: skipped,
            }),
          })}
        </span>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <StageProgress report={report} ticks={NO_TICKS} />
      </CollapsibleContent>
    </Collapsible>
  );
}

export function AnalysisSummary({
  children,
  onAnalyzeAgain,
  report,
  warnings,
}: AnalysisSummaryProps) {
  const runWarnings = warnings.filter(
    (warning) => warning.analysisId === report.analysisId
  );
  return (
    <div className="flex w-full flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
        <div className="flex flex-col gap-1.5">
          <h1 className="font-medium text-foreground text-xl" tabIndex={-1}>
            {headlineText(report)}
          </h1>
          <Meta report={report} />
        </div>
        <Button
          className={SECONDARY_ACTION_CLASS}
          onClick={onAnalyzeAgain}
          size="sm"
          variant="outline"
        >
          {t.analysis.again}
        </Button>
      </div>
      {report.status === "FAILED" && report.error !== null ? (
        <ErrorSurface error={report.error} headingLevel={2} />
      ) : null}
      <CompletenessCaveat report={report} />
      {children}
      <Warnings report={report} warnings={runWarnings} />
      <Stages report={report} />
    </div>
  );
}

const MAP_SKETCH = (
  <svg
    aria-hidden="true"
    fill="none"
    height="56"
    viewBox="0 0 80 56"
    width="80"
  >
    <path
      className="stroke-subtle-foreground/50"
      d="M20 16L40 28M40 28L60 14M40 28L58 42M20 16L22 42M22 42L40 28"
      strokeWidth="1.2"
    />
    <rect
      className="fill-card stroke-subtle-foreground/50"
      height="12"
      rx="3"
      width="16"
      x="12"
      y="9"
    />
    <circle
      className="fill-card stroke-subtle-foreground/50"
      cx="40"
      cy="28"
      r="6"
    />
    <circle
      className="fill-card stroke-subtle-foreground/50"
      cx="60"
      cy="14"
      r="5"
    />
    <circle
      className="fill-card stroke-subtle-foreground/50"
      cx="58"
      cy="42"
      r="5"
    />
    <circle
      className="fill-card stroke-subtle-foreground/50"
      cx="22"
      cy="42"
      r="4.5"
    />
  </svg>
);

export interface MapUnavailableCardProps {
  readonly report: AnalysisStatusReport;
}

const mapBody = (report: AnalysisStatusReport): string => {
  const byStage = new Map(report.stages.map((stage) => [stage.stage, stage]));
  const graph = byStage.get("STRUCTURAL_GRAPH")?.status ?? "PENDING";
  if (graph === "PENDING" || graph === "RUNNING") {
    return t.analysis.map.pending;
  }
  const { files, types } = byStage.get("SOURCE_ANALYSIS")?.counters ?? {};
  if (files === undefined || types === undefined) {
    return t.analysis.map.bodyNoCounts;
  }
  const { body } = t.analysis.map;
  return interpolate(body.template, {
    files: interpolate(plural(files, body.files), { count: files }),
    types: interpolate(plural(types, body.types), { count: types }),
  });
};

export function MapUnavailableCard({ report }: MapUnavailableCardProps) {
  const titleId = useId();
  return (
    <section
      aria-labelledby={titleId}
      className="flex items-center gap-5 rounded-lg border border-card-border bg-card p-5 shadow-card"
    >
      <div
        aria-hidden="true"
        className="flex h-20 w-28 shrink-0 items-center justify-center rounded-md bg-muted"
      >
        {MAP_SKETCH}
      </div>
      <div className="flex min-w-0 flex-col gap-1">
        <h2 className="font-medium text-base text-foreground" id={titleId}>
          {t.analysis.map.title}
        </h2>
        <p className="text-muted-foreground text-sm/5">{mapBody(report)}</p>
      </div>
    </section>
  );
}
