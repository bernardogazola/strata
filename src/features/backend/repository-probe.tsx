import { useCallback, useState } from "react";
import {
  BridgeError,
  closeRepository,
  openRepository,
} from "@/bridge/analyzer";
import type { OpenedRepository } from "@/bridge/contracts";
import { Button } from "@/components/ui/button";
import { formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";
import { selectAnalyzerUsable, useAppStore } from "@/state/app-store";

export function RepositoryProbe() {
  const usable = useAppStore(selectAnalyzerUsable);
  const [opened, setOpened] = useState<OpenedRepository | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const open = useCallback(async () => {
    setBusy(true);
    setFailure(null);
    try {
      setOpened(await openRepository());
    } catch (error) {
      setFailure(
        formatProtocolError(
          error instanceof BridgeError
            ? error.protocolError
            : { code: "INTERNAL_UNEXPECTED", origin: "frontend" }
        ).title
      );
    } finally {
      setBusy(false);
    }
  }, []);

  const onOpen = useCallback(() => {
    open().catch(() => undefined);
  }, [open]);

  const close = useCallback(async () => {
    if (opened === null) {
      return;
    }
    await closeRepository(opened.handle);
    setOpened(null);
  }, [opened]);

  const onClose = useCallback(() => {
    close().catch(() => undefined);
  }, [close]);

  return (
    <div className="flex flex-col items-center gap-3">
      <Button disabled={!usable || busy} onClick={onOpen}>
        {t.repository.open}
      </Button>

      {opened === null ? null : (
        <div className="flex items-center gap-3">
          <bdi
            className="font-mono text-muted-foreground text-sm"
            lang=""
            translate="no"
          >
            {clampText(sanitizeUntrusted(opened.displayName))}
          </bdi>
          <Button onClick={onClose} size="sm" variant="ghost">
            Fechar
          </Button>
        </div>
      )}

      {failure === null ? null : (
        <p className="text-destructive text-sm" role="alert">
          {failure}
        </p>
      )}
    </div>
  );
}
