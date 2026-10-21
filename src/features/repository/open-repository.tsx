import { IconAlertCircle, IconFolder } from "@tabler/icons-react";
import { useNavigate } from "@tanstack/react-router";
import { useCallback, useId, useState } from "react";
import {
  exportDiagnostics,
  openRepository,
  protocolErrorOf,
  restartAnalyzer,
} from "@/bridge/analyzer";
import { applyResolvedBackend } from "@/bridge/events";
import { Button } from "@/components/ui/button";
import { ErrorSurface } from "@/features/errors/error-surface";
import { interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import type { ProtocolError } from "@/protocol/errors";
import { type BackendState, useAppStore } from "@/state/app-store";
import { PRIMARY_ACTION_CLASS } from "./action-styles";

const CRASH_TEXT =
  "text-[color:color-mix(in_oklch,var(--destructive),var(--foreground)_35%)]";

const useCrashRestart = () => {
  const recoverBackend = useAppStore((state) => state.recoverBackend);
  const [restarting, setRestarting] = useState(false);

  const restart = useCallback(async () => {
    setRestarting(true);
    recoverBackend();
    try {
      applyResolvedBackend(await restartAnalyzer());
    } finally {
      setRestarting(false);
    }
  }, [recoverBackend]);

  const onRestart = useCallback(() => {
    restart().catch(() => undefined);
  }, [restart]);

  return { onRestart, restarting };
};

const useExportDiagnostics = () => {
  const [exporting, setExporting] = useState(false);
  const [failure, setFailure] = useState<ProtocolError | null>(null);

  const exportReport = useCallback(async () => {
    setExporting(true);
    setFailure(null);
    try {
      await exportDiagnostics({ redactAuthors: true, redactPaths: true });
    } catch (error) {
      setFailure(protocolErrorOf(error));
    } finally {
      setExporting(false);
    }
  }, []);

  const onExport = useCallback(() => {
    exportReport().catch(() => undefined);
  }, [exportReport]);

  return { exporting, failure, onExport };
};

interface AnalyzerCrashedProps {
  readonly exitCode: number | null;
}

function AnalyzerCrashed({ exitCode }: AnalyzerCrashedProps) {
  const { onRestart, restarting } = useCrashRestart();
  const { exporting, failure, onExport } = useExportDiagnostics();

  return (
    <div className="flex gap-3 rounded-lg bg-destructive-soft px-4 py-3.5">
      <IconAlertCircle
        aria-hidden="true"
        className="mt-0.5 size-4 shrink-0 text-destructive"
        stroke={1.5}
      />
      <div className="flex min-w-0 grow flex-col gap-3">
        <div className="flex select-text flex-col gap-0.5">
          <h2 className={cn("font-medium text-sm/5", CRASH_TEXT)}>
            {t.analyzer.crashed.title}
          </h2>
          <p className={cn("text-sm/5", CRASH_TEXT)}>
            {exitCode === null
              ? t.analyzer.crashed.bodyNoCode
              : interpolate(t.analyzer.crashed.body, { code: exitCode })}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            className="h-[30px] border-destructive/25 bg-background px-3 transition-[scale,background-color] active:scale-[0.98] motion-reduce:active:scale-100"
            disabled={restarting}
            onClick={onRestart}
            size="sm"
            variant="outline"
          >
            {t.repository.restart}
          </Button>
          <Button
            className={cn(
              "h-[30px] px-2.5 transition-[scale,background-color] hover:bg-destructive/10 active:scale-[0.98] motion-reduce:active:scale-100",
              CRASH_TEXT
            )}
            disabled={exporting}
            onClick={onExport}
            size="sm"
            variant="ghost"
          >
            {t.analyzer.exportDiagnostics}
          </Button>
        </div>
        {failure === null ? null : (
          <ErrorSurface error={failure} variant="inline" />
        )}
      </div>
    </div>
  );
}

const versionMismatchOf = (
  backend: Extract<BackendState, { phase: "INCOMPATIBLE" }>
): ProtocolError => ({
  code: "PROTO_VERSION_MISMATCH",
  origin: "shell",
  params: { expected: backend.expected, received: backend.received },
});

const reasonOf = (phase: BackendState["phase"]): string =>
  phase === "READY" ? t.start.pickerHint : t.start.unavailable[phase];

export function OpenRepository() {
  const backend = useAppStore((state) => state.backend);
  const navigate = useNavigate();
  const reasonId = useId();
  const [picking, setPicking] = useState(false);
  const [failure, setFailure] = useState<ProtocolError | null>(null);

  const open = useCallback(async () => {
    setPicking(true);
    setFailure(null);
    try {
      const opened = await openRepository();
      if (opened !== null) {
        await navigate({
          params: { handle: opened.handle },
          to: "/repository/$handle",
        });
      }
    } catch (error) {
      setFailure(protocolErrorOf(error));
    } finally {
      setPicking(false);
    }
  }, [navigate]);

  const onOpen = useCallback(() => {
    open().catch(() => undefined);
  }, [open]);

  return (
    <>
      <div className="flex flex-wrap items-center gap-x-3.5 gap-y-2">
        <Button
          aria-describedby={reasonId}
          className={cn(PRIMARY_ACTION_CLASS, "pr-4 pl-3.5")}
          disabled={backend.phase !== "READY" || picking}
          focusableWhenDisabled
          onClick={onOpen}
        >
          <IconFolder aria-hidden="true" stroke={1.5} />
          {t.repository.open}
        </Button>
        <p className="text-muted-foreground text-xs" id={reasonId}>
          {reasonOf(backend.phase)}
        </p>
      </div>

      {backend.phase === "CRASHED" ? (
        <AnalyzerCrashed exitCode={backend.exitCode} />
      ) : null}

      {backend.phase === "INCOMPATIBLE" ? (
        <ErrorSurface error={versionMismatchOf(backend)} variant="page" />
      ) : null}

      {failure === null ? null : (
        <ErrorSurface error={failure} variant="inline" />
      )}
    </>
  );
}
