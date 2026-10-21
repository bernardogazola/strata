import { IconAlertTriangle } from "@tabler/icons-react";
import { interpolate, plural } from "@/i18n/format";
import { t } from "@/i18n/messages";
import {
  type AnalysisCompleteness,
  isAnalysisPartial,
} from "@/protocol/analysis";
import type { AnalysisStatusReport } from "@/protocol/repository";

export interface CompletenessCaveatProps {
  readonly compact?: boolean;
  readonly percentiles?: boolean;
  readonly report: AnalysisStatusReport | undefined;
}

interface CaveatLine {
  readonly key: "files" | "history" | "percentiles" | "shallow";
  readonly text: string;
}

const caveatLines = (
  completeness: AnalysisCompleteness,
  percentiles: boolean
): readonly CaveatLine[] => {
  const {
    historyTruncatedAtCommits: truncatedAt,
    shallowClone,
    skippedFiles,
    totalFiles,
  } = completeness;
  const candidates: readonly (CaveatLine | null)[] = [
    skippedFiles > 0
      ? {
          key: "files",
          text: interpolate(plural(skippedFiles, t.analysis.partial.files), {
            skipped: skippedFiles,
            total: totalFiles,
          }),
        }
      : null,
    shallowClone
      ? { key: "shallow", text: t.analysis.partial.shallowClone }
      : null,
    truncatedAt === null
      ? null
      : {
          key: "history",
          text: interpolate(t.analysis.partial.truncatedHistory, {
            commits: truncatedAt,
          }),
        },
    percentiles
      ? { key: "percentiles", text: t.analysis.partial.percentiles }
      : null,
  ];
  return candidates.filter((line): line is CaveatLine => line !== null);
};

export function CompletenessCaveat({
  compact = false,
  percentiles = false,
  report,
}: CompletenessCaveatProps) {
  if (
    report === undefined ||
    report.status !== "COMPLETED" ||
    !isAnalysisPartial(report.completeness)
  ) {
    return null;
  }

  const lines = caveatLines(report.completeness, percentiles);
  if (lines.length === 0) {
    return null;
  }

  if (compact) {
    return (
      <div className="flex gap-2 rounded-lg bg-warning-soft px-3 py-2.5">
        <span
          aria-hidden="true"
          className="flex h-[18px] w-3.5 shrink-0 items-center"
        >
          <IconAlertTriangle className="text-warning" size={14} stroke={1.5} />
        </span>
        <p className="text-warning-soft-muted-foreground text-xs/[18px]">
          {lines.map((line, position) => (
            <span key={line.key}>
              {position === 0 ? null : " "}
              {line.text}
            </span>
          ))}
        </p>
      </div>
    );
  }

  return (
    <div className="flex gap-2.5 rounded-lg bg-warning-soft px-3.5 py-3">
      <span
        aria-hidden="true"
        className="flex h-[18px] w-4 shrink-0 items-center"
      >
        <IconAlertTriangle className="text-warning" size={16} stroke={1.5} />
      </span>
      <div className="flex flex-col gap-0.5 text-sm">
        {lines.map((line, position) => (
          <p
            className={
              position === 0
                ? "font-medium text-warning-soft-foreground"
                : "text-warning-soft-muted-foreground"
            }
            key={line.key}
          >
            {line.text}
          </p>
        ))}
      </div>
    </div>
  );
}
