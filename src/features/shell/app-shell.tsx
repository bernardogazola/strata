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
      <header className="col-span-3 flex items-center gap-4 border-b px-4 py-2">
        {header}
      </header>
      <aside
        aria-label={t.shell.regions.sidebar}
        className="min-h-0 overflow-y-auto border-r p-4"
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
        className="min-h-0 overflow-y-auto border-l p-4"
      >
        {inspector}
      </aside>
    </div>
  );
}
