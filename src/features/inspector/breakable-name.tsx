import { Fragment } from "react";
import { elementDisplayLabel } from "@/features/graph/adapter";
import { DEFAULT_UNTRUSTED_MAX_CHARS } from "@/lib/untrusted";
import { cn } from "@/lib/utils";
import type { ElementKind } from "@/protocol/enums";

const AFTER_SEPARATOR = /(?<=[./])/u;

const segmentsOf = (
  text: string,
  breakAfter: RegExp
): readonly { readonly offset: number; readonly text: string }[] => {
  let offset = 0;
  return text.split(breakAfter).map((segment) => {
    const start = offset;
    offset += segment.length;
    return { offset: start, text: segment };
  });
};

export interface BreakableNameProps {
  readonly breakAfter?: RegExp;

  readonly className?: string;

  readonly kind?: ElementKind;

  readonly maxChars?: number;

  readonly text: string;
}

export function BreakableName({
  breakAfter = AFTER_SEPARATOR,
  className,
  kind = "TYPE",
  maxChars = DEFAULT_UNTRUSTED_MAX_CHARS,
  text,
}: BreakableNameProps) {
  const shown = elementDisplayLabel(kind, text, maxChars);
  if (kind === "PACKAGE" && text === "") {
    return <span className={className}>{shown}</span>;
  }
  const segments = segmentsOf(shown, breakAfter);
  return (
    <bdi className={cn("break-words", className)} lang="" translate="no">
      {segments.map((segment, position) => (
        <Fragment key={segment.offset}>
          {segment.text}
          {position < segments.length - 1 ? <wbr /> : null}
        </Fragment>
      ))}
    </bdi>
  );
}
