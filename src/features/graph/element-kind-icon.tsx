import { cn } from "@/lib/utils";
import type { ElementKind, GraphNodeKind } from "@/protocol/enums";
import { NODE_SHAPES } from "./stylesheet";

type NodeShape = (typeof NODE_SHAPES)[ElementKind | GraphNodeKind];

const SHAPE_CLASS = {
  ellipse: "size-[9px] rounded-full",
  "round-rectangle": "h-[9px] w-[11px] rounded-[2px]",
} as const satisfies Record<NodeShape, string>;

export interface ElementKindIconProps {
  readonly kind: ElementKind | GraphNodeKind;

  readonly selected?: boolean;
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
      <span
        className={cn(
          "block shrink-0 border-[1.5px]",
          SHAPE_CLASS[NODE_SHAPES[kind]],
          selected ? "border-accent-strong" : "border-subtle-foreground"
        )}
      />
    </span>
  );
}
