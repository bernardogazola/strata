import type { ReactNode } from "react";
import type { WindowPlatform } from "@/bridge/environment";
import { nativeWindowPlatform } from "@/bridge/window";
import { ModeToggle } from "@/components/mode-toggle";
import { Button } from "@/components/ui/button";
import { AnalyzerStatus } from "@/features/backend/analyzer-status";
import {
  useWindowFocused,
  useWindowFrame,
  WindowControls,
} from "@/features/shell/window-controls";
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
      viewBox="0 0 24 24"
      width={size}
    >
      <path
        className="fill-foreground"
        d="M2 9.17V7A5 5 0 0 1 7 2H17A5 5 0 0 1 21.93 6.18Z"
      />
      <path
        className="fill-subtle-foreground"
        d="M2 10.67L22 7.67V13.33L2 16.33Z"
      />
      <path
        className="fill-ring"
        d="M2.07 17.82L22 14.83V17A5 5 0 0 1 17 22H7A5 5 0 0 1 2.07 17.82Z"
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
      className="min-w-0 max-w-70 truncate font-medium text-foreground group-data-inactive/titlebar:text-muted-foreground"
      lang=""
      title={name}
      translate="no"
    >
      {name}
    </bdi>
  );
}

const TRAILING_INSET = {
  browser: "pr-4",
  linux: "pr-3",
  macos: "pr-4",
  windows: "pr-0",
} as const satisfies Record<WindowPlatform | "browser", string>;

function TrafficLightSpace() {
  return (
    <div aria-hidden="true" className="w-[70px] shrink-0" data-traffic-lights />
  );
}

export function AppHeader({ repository, status, trail }: AppHeaderProps) {
  const platform = nativeWindowPlatform();
  const frame = useWindowFrame();
  const focused = useWindowFocused();
  const reservesTrafficLights = platform === "macos" && !frame.fullscreen;
  const drawsControls = platform === "linux" || platform === "windows";

  return (
    <header
      className={cn(
        "group/titlebar flex h-12 shrink-0 select-none items-center justify-between gap-4 border-b pl-5",
        TRAILING_INSET[platform ?? "browser"]
      )}
      data-inactive={platform !== null && !focused ? "" : undefined}
      data-tauri-drag-region="deep"
    >
      <div className="flex min-w-0 items-center gap-2.5">
        {reservesTrafficLights ? <TrafficLightSpace /> : null}
        <BrandMark />
        <span className="shrink-0 font-medium text-base text-foreground group-data-inactive/titlebar:text-muted-foreground">
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
      <div className="flex shrink-0 items-center gap-1 self-stretch">
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
        {drawsControls ? (
          <WindowControls maximized={frame.maximized} platform={platform} />
        ) : null}
      </div>
    </header>
  );
}
