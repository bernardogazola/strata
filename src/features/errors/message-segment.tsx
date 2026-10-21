import type { MessageSegment } from "@/i18n/errors";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";

export interface MessageSegmentsProps {
  readonly segments: readonly MessageSegment[];
}

interface KeyedSegment {
  readonly key: string;
  readonly segment: MessageSegment;
}

const withKeys = (segments: readonly MessageSegment[]): KeyedSegment[] => {
  const seen = new Map<string, number>();
  return segments.map((segment) => {
    const base =
      segment.kind === "chrome" ? `c:${segment.text}` : `u:${segment.value}`;
    const occurrence = seen.get(base) ?? 0;
    seen.set(base, occurrence + 1);
    return { key: `${base}#${occurrence}`, segment };
  });
};

export function MessageSegments({ segments }: MessageSegmentsProps) {
  return (
    <>
      {withKeys(segments).map(({ key, segment }) =>
        segment.kind === "chrome" ? (
          <span key={key}>{segment.text}</span>
        ) : (
          <bdi
            className="rounded-sm bg-muted px-1 font-mono text-muted-foreground text-xs [overflow-wrap:anywhere]"
            key={key}
            lang=""
            translate="no"
          >
            {clampText(sanitizeUntrusted(segment.value))}
          </bdi>
        )
      )}
    </>
  );
}
