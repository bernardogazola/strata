import { cn } from "@/lib/utils";
import type { ElementKind, GraphNodeKind } from "@/protocol/enums";
import { NODE_SHAPES } from "./stylesheet";

type NodeShape = (typeof NODE_SHAPES)[ElementKind | GraphNodeKind];

type BoxShape = Exclude<NodeShape, "round-hexagon">;

const SHAPE_CLASS = {
  ellipse: "size-[9px] rounded-full",
  "round-rectangle": "h-[9px] w-[11px] rounded-[2px]",
} as const satisfies Record<BoxShape, string>;

const HEXAGON_POINTS =
  "3.25,0.75 0.75,4.5 3.25,8.25 7.75,8.25 10.25,4.5 7.75,0.75";

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
        height="9"
        strokeLinejoin="round"
        strokeWidth="1.5"
        viewBox="0 0 11 9"
        width="11"
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
  return (
    <span
      aria-hidden="true"
      className="flex w-3 shrink-0 items-center justify-center"
    >
      <ShapeMark selected={selected} shape={NODE_SHAPES[kind]} />
    </span>
  );
}
