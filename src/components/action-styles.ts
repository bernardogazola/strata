const PRESS_CLASS =
  "transition-[scale,background-color,box-shadow] active:scale-[0.98] motion-reduce:active:scale-100";

const ARIA_DISABLED_CLASS =
  "aria-disabled:cursor-not-allowed aria-disabled:opacity-50 aria-disabled:active:scale-100";

export const PRIMARY_ACTION_CLASS = `h-9 gap-2 shadow-primary ${PRESS_CLASS} ${ARIA_DISABLED_CLASS} aria-disabled:hover:bg-primary`;

export const SECONDARY_ACTION_CLASS = `px-3.5 ${PRESS_CLASS} ${ARIA_DISABLED_CLASS} aria-disabled:hover:bg-background dark:border-border dark:bg-background dark:hover:bg-muted dark:aria-disabled:hover:bg-background`;
