import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { formatProtocolError } from "@/i18n/errors";
import { interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import type { ProtocolError } from "@/protocol/errors";
import { MessageSegments } from "./message-segment";

export interface ErrorSurfaceProps {
  readonly actions?: ReactNode;
  readonly error: ProtocolError;
  readonly onRetry?: () => void;
  readonly variant?: "inline" | "page";
}

export function ErrorSurface({
  actions,
  error,
  onRetry,
  variant = "page",
}: ErrorSurfaceProps) {
  const message = formatProtocolError(error);
  const Title = variant === "page" ? "h1" : "p";
  return (
    <div className="flex select-text flex-col gap-2">
      <Title className="font-medium">{message.title}</Title>
      <p>
        <MessageSegments segments={message.body} />
      </p>
      {message.hint === undefined ? null : <p>{message.hint}</p>}
      <div className="flex items-center gap-2">
        {message.retryable && onRetry !== undefined ? (
          <Button onClick={onRetry} variant="outline">
            {t.graph.retry}
          </Button>
        ) : null}
        {actions}
      </div>
      <p className="font-mono text-muted-foreground text-xs">
        {interpolate(t.error.code, { code: error.code })}
      </p>
    </div>
  );
}
