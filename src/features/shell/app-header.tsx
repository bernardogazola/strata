import type { ReactNode } from "react";
import { ModeToggle } from "@/components/mode-toggle";
import { Button } from "@/components/ui/button";
import { AnalyzerStatus } from "@/features/backend/analyzer-status";
import { t } from "@/i18n/messages";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";
import { cn } from "@/lib/utils";

export interface AppHeaderRepository {
  readonly displayName: string | undefined;
  readonly onClose: () => void;
}

export interface AppHeaderProps {
  readonly repository?: AppHeaderRepository;
  readonly status?: ReactNode;
  readonly trail?: ReactNode;
}

export interface BrandMarkProps {
  readonly size?: number;
}

export function BrandMark({ size = 18 }: BrandMarkProps) {
  return (
    <svg
      aria-hidden="true"
      className="shrink-0"
      fill="none"
      height={size}
      viewBox="0 0 20 20"
      width={size}
    >
      <path
        className="fill-foreground"
        d="M6 2H14C16.2 2 18 3.8 18 6V6.2L2 8.6V6C2 3.8 3.8 2 6 2Z"
      />
      <path
        className="fill-subtle-foreground"
        d="M2 9.8L18 7.4V11.4L2 13.8V9.8Z"
      />
      <path
        className="fill-ring"
        d="M2 15L18 12.6V14C18 16.2 16.2 18 14 18H6C3.8 18 2 16.2 2 14V15Z"
      />
    </svg>
  );
}

export type AnalysisChipState = keyof typeof t.header.analysis;

const CHIP_TONES = {
  CANCELLED: {
    chip: "bg-muted text-muted-foreground",
    dot: "bg-subtle-foreground",
  },
  FAILED: {
    chip: "bg-destructive-soft text-destructive",
    dot: "bg-destructive",
  },
  PARTIAL: {
    chip: "bg-warning-soft text-warning-soft-muted-foreground",
    dot: "bg-warning",
  },
  RUNNING: {
    chip: "bg-accent-strong/10 text-accent-strong",
    dot: "bg-accent-strong",
  },
} as const satisfies Record<
  AnalysisChipState,
  { readonly chip: string; readonly dot: string }
>;

export interface AnalysisChipProps {
  readonly state: AnalysisChipState;
}

export function AnalysisChip({ state }: AnalysisChipProps) {
  const tone = CHIP_TONES[state];
  return (
    <span
      className={cn(
        "flex h-6 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-2 text-xs",
        tone.chip
      )}
    >
      <span
        aria-hidden="true"
        className={cn("size-1.5 shrink-0 rounded-full", tone.dot)}
      />
      {t.header.analysis[state]}
    </span>
  );
}

function PathSeparator() {
  return (
    <span aria-hidden="true" className="shrink-0 text-subtle-foreground/50">
      /
    </span>
  );
}

function RepositoryName({ displayName }: { readonly displayName: string }) {
  const name = clampText(sanitizeUntrusted(displayName));
  return (
    <bdi
      className="min-w-0 max-w-70 truncate font-medium text-foreground"
      lang=""
      title={name}
      translate="no"
    >
      {name}
    </bdi>
  );
}

export function AppHeader({ repository, status, trail }: AppHeaderProps) {
  return (
    <header className="flex h-12 shrink-0 items-center justify-between gap-4 border-b pr-4 pl-5">
      <div className="flex min-w-0 items-center gap-2.5">
        <BrandMark />
        <span className="shrink-0 font-medium text-base text-foreground">
          {t.app.name}
        </span>
        {repository?.displayName === undefined ? null : (
          <>
            <PathSeparator />
            <RepositoryName displayName={repository.displayName} />
          </>
        )}
        {trail === undefined || trail === null ? null : (
          <>
            <PathSeparator />
            {trail}
          </>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {status}
        <AnalyzerStatus offersRestart={repository !== undefined} />
        {repository === undefined ? null : (
          <Button
            className="h-7 px-2.5 text-muted-foreground text-xs hover:text-foreground"
            onClick={repository.onClose}
            size="sm"
            variant="ghost"
          >
            {t.header.close}
          </Button>
        )}
        <ModeToggle />
      </div>
    </header>
  );
}
