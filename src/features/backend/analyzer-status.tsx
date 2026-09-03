import { useCallback, useState } from "react";
import { restartAnalyzer } from "@/bridge/analyzer";
import { applyResolvedBackend } from "@/bridge/events";
import { Button } from "@/components/ui/button";
import { t } from "@/i18n/messages";
import { selectBackendRecoverable, useAppStore } from "@/state/app-store";

export function AnalyzerStatus() {
  const backend = useAppStore((state) => state.backend);
  const recoverable = useAppStore(selectBackendRecoverable);
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

  return (
    <div className="flex items-center gap-3">
      <p aria-live="polite" className="text-sm">
        {t.backend[backend.phase]}
        {backend.phase === "CRASHED" && backend.exitCode !== null
          ? ` (${backend.exitCode})`
          : ""}
      </p>
      {recoverable ? (
        <Button
          disabled={restarting}
          onClick={onRestart}
          size="sm"
          variant="secondary"
        >
          {t.repository.restart}
        </Button>
      ) : null}
    </div>
  );
}
