import { buttonVariants } from "@/components/ui/button";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";

export interface SkipToOutlineProps {
  readonly onSkip: () => void;
}

const SKIP_CLASS =
  "sr-only focus-visible:not-sr-only focus-visible:absolute focus-visible:top-2 focus-visible:left-2 focus-visible:z-10";

export function SkipToOutline({ onSkip }: SkipToOutlineProps) {
  return (
    <button
      className={cn(buttonVariants({ size: "sm" }), SKIP_CLASS)}
      onClick={onSkip}
      type="button"
    >
      {t.shell.skipToOutline}
    </button>
  );
}
