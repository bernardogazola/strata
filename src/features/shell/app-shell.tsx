import type { ReactNode } from "react";
import { t } from "@/i18n/messages";

export interface AppShellProps {
  readonly header: ReactNode;

  readonly inspector: ReactNode;

  readonly main: ReactNode;

  readonly sidebar: ReactNode;
}

export function AppShell({ header, inspector, main, sidebar }: AppShellProps) {
  return (
    <div className="grid h-svh grid-cols-[18rem_minmax(0,1fr)_20rem] grid-rows-[auto_minmax(0,1fr)] bg-background text-foreground">
      <div className="col-span-3 min-w-0">{header}</div>
      <aside
        aria-label={t.shell.regions.sidebar}
        className="flex min-h-0 flex-col border-r bg-sidebar px-2 py-3"
      >
        {sidebar}
      </aside>
      <main
        aria-label={t.shell.regions.canvas}
        className="relative min-h-0 min-w-0"
      >
        {main}
      </main>
      <aside
        aria-label={t.shell.regions.inspector}
        className="flex min-h-0 flex-col overflow-y-auto border-l p-5"
      >
        {inspector}
      </aside>
    </div>
  );
}
