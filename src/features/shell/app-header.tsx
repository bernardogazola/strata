import type { ReactNode } from "react";
import { ModeToggle } from "@/components/mode-toggle";
import { Button } from "@/components/ui/button";
import { AnalyzerStatus } from "@/features/backend/analyzer-status";
import { t } from "@/i18n/messages";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";

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

export function AppHeader({ repository, status, trail }: AppHeaderProps) {
  return (
    <header className="flex h-12 shrink-0 items-center justify-between gap-4 border-b px-4">
      <div className="flex min-w-0 items-center gap-2.5">
        <BrandMark />
        <span className="font-medium">{t.app.name}</span>
        {repository?.displayName === undefined ? null : (
          <bdi className="truncate font-medium" lang="" translate="no">
            {clampText(sanitizeUntrusted(repository.displayName))}
          </bdi>
        )}
        {trail}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {status}
        <AnalyzerStatus />
        {repository === undefined ? null : (
          <Button onClick={repository.onClose} size="sm" variant="ghost">
            {t.header.close}
          </Button>
        )}
        <ModeToggle />
      </div>
    </header>
  );
}
