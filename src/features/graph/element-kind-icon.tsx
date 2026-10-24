import { cn } from "@/lib/utils";
import type { ElementKind, GraphNodeKind } from "@/protocol/enums";
import { NODE_SHAPES } from "./stylesheet";

type NodeShape = (typeof NODE_SHAPES)[ElementKind | GraphNodeKind];

type BoxShape = Exclude<NodeShape, "round-hexagon">;

const SHAPE_CLASS = {
  ellipse: "size-[9px] rounded-full",
  "round-rectangle": "h-[9px] w-[11px] rounded-[2px]",
} as const satisfies Record<BoxShape, string>;

const HEXAGON_POINTS = "4,0.5 1,6 4,11.5 10,11.5 13,6 10,0.5";

export interface ElementKindIconProps {
  readonly kind: ElementKind | GraphNodeKind;

  readonly selected?: boolean;
}

function ShapeMark({
  selected,
  shape,
}: {
  readonly selected: boolean;
  readonly shape: NodeShape;
}) {
  if (shape === "round-hexagon") {
    return (
      <svg
        aria-hidden="true"
        className={cn(
          "block shrink-0 fill-none",
          selected ? "stroke-accent-strong" : "stroke-subtle-foreground"
        )}
        height="12"
        strokeLinejoin="miter"
        strokeWidth="1"
        viewBox="0 0 14 12"
        width="14"
      >
        <polygon points={HEXAGON_POINTS} />
      </svg>
    );
  }
  return (
    <span
      className={cn(
        "block shrink-0 border-[1.5px]",
        SHAPE_CLASS[shape],
        selected ? "border-accent-strong" : "border-subtle-foreground"
      )}
    />
  );
}

export function ElementKindIcon({
  kind,
  selected = false,
}: ElementKindIconProps) {
  const shape = NODE_SHAPES[kind];
  return (
    <span
      aria-hidden="true"
      className={cn(
        "flex shrink-0 items-center justify-center",
        shape === "round-hexagon" ? "w-3.5" : "w-3"
      )}
    >
      <ShapeMark selected={selected} shape={shape} />
    </span>
  );
}
