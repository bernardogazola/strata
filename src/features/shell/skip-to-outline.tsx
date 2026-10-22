import { buttonVariants } from "@/components/ui/button";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";

export interface SkipToOutlineProps {
  readonly onSkip: () => void;
}

const SKIP_CLASS =
  "sr-only focus-visible:not-sr-only focus-visible:absolute focus-visible:top-3 focus-visible:left-3 focus-visible:z-10 focus-visible:bg-raised focus-visible:px-3.5 focus-visible:shadow-raised dark:border-border dark:bg-raised";

export function SkipToOutline({ onSkip }: SkipToOutlineProps) {
  return (
    <button
      className={cn(
        buttonVariants({ size: "sm", variant: "outline" }),
        SKIP_CLASS
      )}
      onClick={onSkip}
      type="button"
    >
      {t.shell.skipToOutline}
    </button>
  );
}
