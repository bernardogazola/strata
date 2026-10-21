import { useCallback, useState } from "react";
import { restartAnalyzer } from "@/bridge/analyzer";
import { applyResolvedBackend } from "@/bridge/events";
import { Button } from "@/components/ui/button";
import { interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import {
  type BackendState,
  selectBackendRecoverable,
  useAppStore,
} from "@/state/app-store";

export interface AnalyzerRestart {
  readonly restart: () => void;

  readonly restarting: boolean;
}

export function useAnalyzerRestart(): AnalyzerRestart {
  const recoverBackend = useAppStore((state) => state.recoverBackend);
  const [restarting, setRestarting] = useState(false);

  const run = useCallback(async () => {
    setRestarting(true);
    recoverBackend();
    try {
      applyResolvedBackend(await restartAnalyzer());
    } finally {
      setRestarting(false);
    }
  }, [recoverBackend]);

  const restart = useCallback(() => {
    run().catch(() => undefined);
  }, [run]);

  return { restart, restarting };
}

const DOT_CLASS = {
  CRASHED: "bg-destructive",
  INCOMPATIBLE: "bg-destructive",
  READY: "bg-success",
  STARTING: "bg-subtle-foreground",
} as const satisfies Record<BackendState["phase"], string>;

export interface AnalyzerStatusProps {
  readonly offersRestart?: boolean;
}

export function AnalyzerStatus({ offersRestart = true }: AnalyzerStatusProps) {
  const backend = useAppStore((state) => state.backend);
  const recoverable = useAppStore(selectBackendRecoverable);
  const { restart, restarting } = useAnalyzerRestart();
  const exitCode = backend.phase === "CRASHED" ? backend.exitCode : null;

  return (
    <div className="flex items-center gap-1">
      <p
        aria-live="polite"
        className="flex h-7 items-center gap-2 whitespace-nowrap px-2.5 text-muted-foreground text-xs"
      >
        <span
          aria-hidden="true"
          className={cn(
            "size-1.5 shrink-0 rounded-full",
            DOT_CLASS[backend.phase]
          )}
        />
        {t.backend[backend.phase]}
        {exitCode === null ? null : (
          <span className="tabular-nums">
            {interpolate(t.analyzer.exitCode, { code: exitCode })}
          </span>
        )}
      </p>
      {offersRestart && recoverable ? (
        <Button
          className="h-7 px-2.5 text-xs shadow-control"
          disabled={restarting}
          onClick={restart}
          size="sm"
          variant="outline"
        >
          {t.repository.restart}
        </Button>
      ) : null}
    </div>
  );
}
