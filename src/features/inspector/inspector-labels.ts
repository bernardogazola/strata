import type { InspectorTab } from "@/features/shell/repository-search";
import { interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import type { LineRange } from "@/protocol/graph";

export const INSPECTOR_TAB_LABELS = {
  dependencies: t.inspector.tabs.dependencies,
  history: t.inspector.tabs.history,
  overview: t.inspector.tabs.overview,
} as const satisfies Record<InspectorTab, string>;

export const SHOWN_TABS = [
  "overview",
  "dependencies",
] as const satisfies readonly InspectorTab[];

export type ShownTab = (typeof SHOWN_TABS)[number];

export const shownTabOf = (tab: InspectorTab): ShownTab =>
  tab === "dependencies" ? "dependencies" : "overview";

export const lineRangeText = (lines: LineRange | null): string => {
  if (lines === null) {
    return t.inspector.header.noLines;
  }
  return lines.start === lines.end
    ? interpolate(t.inspector.header.line, { line: lines.start })
    : interpolate(t.inspector.header.lines, {
        end: lines.end,
        start: lines.start,
      });
};
