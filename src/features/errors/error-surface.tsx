import { type Icon, IconAlertCircle, IconCircleX } from "@tabler/icons-react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { type ErrorSeverity, formatProtocolError } from "@/i18n/errors";
import { interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import { isKnownErrorCode, type ProtocolError } from "@/protocol/errors";
import { MessageSegments } from "./message-segment";

export interface ErrorSurfaceProps {
  readonly actions?: ReactNode;
  readonly announce?: boolean;
  readonly error: ProtocolError;
  readonly headingLevel?: 1 | 2;
  readonly onRetry?: () => void;
  readonly variant?: "inline" | "page";
}

interface SeverityStyle {
  readonly icon: Icon;
  readonly stroke: string;
  readonly tile: string;
}

const SEVERITY_STYLES = {
  error: {
    icon: IconCircleX,
    stroke: "text-destructive",
    tile: "bg-destructive-soft",
  },
  warning: {
    icon: IconAlertCircle,
    stroke: "text-warning",
    tile: "bg-warning-soft",
  },
} as const satisfies Record<ErrorSeverity, SeverityStyle>;

const ICON_STROKE = 1.5;

const SECONDARY_BUTTON_CLASS =
  "px-3.5 transition-[scale,background-color,box-shadow] active:scale-[0.98] motion-reduce:active:scale-100";

const codeLine = (error: ProtocolError): string | null =>
  isKnownErrorCode(error.code)
    ? interpolate(t.error.code, { code: error.code })
    : null;

interface RetryButtonProps {
  readonly onRetry: () => void;
}

function RetryButton({ onRetry }: RetryButtonProps) {
  return (
    <Button
      className={SECONDARY_BUTTON_CLASS}
      onClick={onRetry}
      size="sm"
      variant="outline"
    >
      {t.graph.retry}
    </Button>
  );
}

export function ErrorSurface({
  actions,
  announce = true,
  error,
  headingLevel = 1,
  onRetry,
  variant = "page",
}: ErrorSurfaceProps) {
  const message = formatProtocolError(error);
  const style = SEVERITY_STYLES[message.severity];
  const SeverityIcon = style.icon;
  const code = codeLine(error);
  const retry =
    message.retryable && onRetry !== undefined ? (
      <RetryButton onRetry={onRetry} />
    ) : null;
  const hasActions = retry !== null || actions !== undefined;

  if (variant === "inline") {
    return (
      <div
        className="flex select-text items-start gap-2.5"
        role={announce ? "alert" : undefined}
      >
        <span className="flex h-5 w-4 shrink-0 items-center">
          <SeverityIcon
            aria-hidden="true"
            className={style.stroke}
            size={16}
            stroke={ICON_STROKE}
          />
        </span>
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="font-medium text-foreground text-sm/5">
            {message.title}
          </p>
          <p className="text-muted-foreground text-sm/5">
            <MessageSegments segments={message.body} />
          </p>
          {message.hint === undefined ? null : (
            <p className="text-foreground text-sm/5">{message.hint}</p>
          )}
          {hasActions ? (
            <div className="mt-1.5 flex flex-wrap items-center gap-2">
              {retry}
              {actions}
            </div>
          ) : null}
          {code === null ? null : (
            <p className="mt-1 font-mono text-muted-foreground text-xs">
              {code}
            </p>
          )}
        </div>
      </div>
    );
  }

  const Title = headingLevel === 1 ? "h1" : "h2";
  const titleSize = headingLevel === 1 ? "text-xl" : "text-lg";
  return (
    <div className="flex w-full max-w-[520px] select-text flex-col gap-5">
      <div
        aria-hidden="true"
        className={cn(
          "flex size-9 items-center justify-center rounded-lg",
          style.tile
        )}
      >
        <SeverityIcon className={style.stroke} size={18} stroke={ICON_STROKE} />
      </div>
      <div className="flex flex-col gap-2">
        <Title
          className={cn("font-medium text-foreground", titleSize)}
          tabIndex={-1}
        >
          {message.title}
        </Title>
        <p className="text-muted-foreground text-sm/5">
          <MessageSegments segments={message.body} />
        </p>
        {message.hint === undefined ? null : (
          <p className="text-foreground text-sm/5">{message.hint}</p>
        )}
      </div>
      {hasActions ? (
        <div className="flex flex-wrap items-center gap-2.5">
          {retry}
          {actions}
        </div>
      ) : null}
      {code === null ? null : (
        <p className="border-border border-t pt-2 font-mono text-muted-foreground text-xs">
          {code}
        </p>
      )}
    </div>
  );
}
