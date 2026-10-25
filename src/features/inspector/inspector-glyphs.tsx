import { EDGE_LINES } from "@/features/graph/stylesheet";
import { cn } from "@/lib/utils";
import type { ResolutionQuality } from "@/protocol/enums";

const RESOLUTION_GLYPH_CLASS = {
  INFERRED: "stroke-muted-foreground",
  RESOLVED: "stroke-subtle-foreground",
  UNRESOLVED_EXTERNAL: "stroke-muted-foreground",
} as const satisfies Record<ResolutionQuality, string>;

const GLYPH_STROKE_WIDTH = 1.5;

export function ResolutionGlyph({
  className,
  quality,
  width,
}: {
  readonly className?: string;
  readonly quality: ResolutionQuality;
  readonly width: number;
}) {
  const line = EDGE_LINES[quality];
  return (
    <svg
      aria-hidden="true"
      className={cn("shrink-0", className ?? RESOLUTION_GLYPH_CLASS[quality])}
      height="6"
      viewBox={`0 0 ${width} 6`}
      width={width}
    >
      <line
        strokeDasharray={line.dashPattern?.join(" ")}
        strokeLinecap={line.cap}
        strokeWidth={GLYPH_STROKE_WIDTH}
        x1="0.75"
        x2={width - 0.75}
        y1="3"
        y2="3"
      />
    </svg>
  );
}

export function InfoGlyph() {
  return (
    <svg
      aria-hidden="true"
      className="mt-0.5 shrink-0 fill-none stroke-subtle-foreground"
      height="12"
      viewBox="0 0 16 16"
      width="12"
    >
      <circle cx="8" cy="8" r="6.2" strokeWidth="1.4" />
      <path d="M8 7v4" strokeLinecap="round" strokeWidth="1.5" />
      <circle
        className="fill-subtle-foreground stroke-none"
        cx="8"
        cy="4.8"
        r="0.9"
      />
    </svg>
  );
}

export function ChevronGlyph() {
  return (
    <svg
      aria-hidden="true"
      className="shrink-0 fill-none stroke-subtle-foreground"
      height="14"
      viewBox="0 0 16 16"
      width="14"
    >
      <path
        d="M6 4l4 4-4 4"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1.4"
      />
    </svg>
  );
}
