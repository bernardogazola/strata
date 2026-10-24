import { type ReactElement, useId } from "react";
import { sentenceCase } from "@/i18n/format";
import { resolutionQualityLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import type { ResolutionQuality } from "@/protocol/enums";
import {
  EDGE_LINES,
  type EdgeLine,
  GRAPH_STYLE,
  NODE_SHAPES,
} from "./stylesheet";

const LEGEND_KINDS = [
  "PACKAGE",
  "PACKAGE_PREFIX",
  "TYPE",
] as const satisfies readonly (keyof typeof t.graph.legend.kindShapes)[];

type LegendKind = (typeof LEGEND_KINDS)[number];
type LegendShape = (typeof NODE_SHAPES)[LegendKind];

const WIRE_EDGE_QUALITIES = [
  "RESOLVED",
  "INFERRED",
] as const satisfies readonly ResolutionQuality[];

const SAMPLE_EDGE_WIDTH = 1.5;

const SHAPE_GLYPHS = {
  ellipse: <circle cx="8" cy="8" r="6" />,
  "round-hexagon": (
    <polygon
      points="3.75,0.5 -0.5,8 3.75,15.5 12.25,15.5 16.5,8 12.25,0.5"
      strokeLinejoin="miter"
    />
  ),
  "round-rectangle": <rect height="12" rx="3" width="14" x="1" y="2" />,
} as const satisfies Record<LegendShape, ReactElement>;

const KIND_FILLS = {
  PACKAGE: "fill-(--graph-node-package)",
  PACKAGE_PREFIX: "fill-(--graph-node-package)",
  TYPE: "fill-(--graph-node)",
} as const satisfies Record<LegendKind, string>;

function EdgeSample({ line }: { readonly line: EdgeLine }) {
  return (
    <svg
      aria-hidden="true"
      className="shrink-0 stroke-(--graph-edge-structural)"
      height="10"
      viewBox="0 0 20 10"
      width="20"
    >
      <line
        strokeDasharray={line.dashPattern?.join(" ")}
        strokeLinecap={line.cap}
        strokeWidth={SAMPLE_EDGE_WIDTH}
        x1="1"
        x2="19"
        y1="5"
        y2="5"
      />
    </svg>
  );
}

function NodeSample({
  kind,
  selected,
}: {
  readonly kind: LegendKind;
  readonly selected: boolean;
}) {
  return (
    <svg
      aria-hidden="true"
      className={cn(
        "shrink-0",
        KIND_FILLS[kind],
        selected
          ? "stroke-(--graph-node-selected)"
          : "stroke-(--graph-node-border)"
      )}
      height="14"
      strokeWidth={
        selected
          ? GRAPH_STYLE.node.selectedBorderWidth
          : GRAPH_STYLE.node.borderWidth
      }
      viewBox="-2 -2 20 20"
      width="14"
    >
      {SHAPE_GLYPHS[NODE_SHAPES[kind]]}
    </svg>
  );
}

function ItemName({
  shown,
  spoken,
}: {
  readonly shown: string;
  readonly spoken: string;
}) {
  return (
    <>
      <span aria-hidden="true">{shown}</span>
      <span className="sr-only">{spoken}</span>
    </>
  );
}

const GROUP_CLASS = "flex items-center";
const LIST_CLASS = "flex flex-wrap items-center gap-x-5 gap-y-1";
const ITEM_CLASS = "flex items-center gap-1.5 whitespace-nowrap";

export interface GraphLegendProps {
  readonly drawsGroups?: boolean;
}

export function GraphLegend({ drawsGroups = false }: GraphLegendProps) {
  const titleId = useId();
  const kinds = LEGEND_KINDS.filter(
    (kind) => drawsGroups || kind !== "PACKAGE_PREFIX"
  );

  return (
    <section
      aria-labelledby={titleId}
      className="flex min-h-10 flex-wrap items-center gap-x-5 gap-y-1 border-t px-4 py-2 text-muted-foreground text-xs"
    >
      <h2 className="font-medium text-foreground" id={titleId}>
        {t.graph.legend.title}
      </h2>

      <div className={GROUP_CLASS}>
        <h3 className="sr-only">{t.graph.legend.kind}</h3>
        <ul className={LIST_CLASS}>
          {kinds.map((kind) => (
            <li className={ITEM_CLASS} key={kind}>
              <NodeSample kind={kind} selected={false} />
              <ItemName
                shown={t.graph.legend.kindNames[kind]}
                spoken={t.graph.legend.kindShapes[kind]}
              />
            </li>
          ))}
          <li className={ITEM_CLASS}>
            <NodeSample kind="TYPE" selected />
            <ItemName
              shown={t.graph.legend.selectionName}
              spoken={t.graph.legend.selection}
            />
          </li>
        </ul>
      </div>

      <div className={GROUP_CLASS}>
        <h3 className="sr-only">{t.graph.legend.resolution}</h3>
        <ul className={LIST_CLASS}>
          {WIRE_EDGE_QUALITIES.map((quality) => (
            <li className={ITEM_CLASS} key={quality}>
              <EdgeSample line={EDGE_LINES[quality]} />
              {sentenceCase(resolutionQualityLabels[quality])}
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
