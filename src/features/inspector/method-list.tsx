import { useId } from "react";
import { formatInteger, interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";
import type { MethodSummary } from "@/protocol/graph";
import { lineRangeText } from "./inspector-labels";

const SIGNATURE_MAX_CHARS = 512;

function MethodRow({ method }: { readonly method: MethodSummary }) {
  const { cc, lines, signature } = method;
  return (
    <li className="flex items-start justify-between gap-3 border-b py-2">
      <span className="flex min-w-0 flex-col gap-0.5">
        <bdi
          className="break-words text-foreground text-sm"
          lang=""
          translate="no"
        >
          {clampText(sanitizeUntrusted(signature), SIGNATURE_MAX_CHARS)}
        </bdi>
        <span className="text-muted-foreground text-xs">
          {lineRangeText(lines)}
        </span>
      </span>
      {cc === null ? (
        <span className="shrink-0 whitespace-nowrap text-muted-foreground text-xs leading-5">
          {t.inspector.methods.ccNotApplicable}
        </span>
      ) : (
        <span className="shrink-0 whitespace-nowrap font-medium text-foreground text-sm tabular-nums">
          {formatInteger(cc)}
        </span>
      )}
    </li>
  );
}

export interface MethodListProps {
  readonly methods: readonly MethodSummary[];

  readonly methodTotal: number;
}

export function MethodList({ methodTotal, methods }: MethodListProps) {
  const titleId = useId();
  const headerId = useId();
  const noteId = useId();
  const cut = methodTotal > methods.length;
  const explains = methods.some((method) => method.cc === null);

  return (
    <section aria-labelledby={titleId} className="flex flex-col">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 pb-1 text-muted-foreground text-xs">
        <h3 className="font-medium" id={titleId}>
          {interpolate(t.inspector.methods.title, { count: methodTotal })}
        </h3>
        {methods.length === 0 ? null : (
          <span className="ml-auto text-right" id={headerId}>
            {t.inspector.methods.ccHeader}
          </span>
        )}
      </div>
      {cut ? (
        <p className="pt-1 pb-1.5 text-muted-foreground text-xs">
          {interpolate(t.inspector.methods.cut, {
            shown: methods.length,
            total: methodTotal,
          })}
        </p>
      ) : null}
      {methods.length === 0 ? (
        <p className="py-2 text-muted-foreground text-sm">
          {t.inspector.methods.none}
        </p>
      ) : (
        <ol
          aria-describedby={explains ? `${headerId} ${noteId}` : headerId}
          aria-labelledby={titleId}
        >
          {methods.map((method) => (
            <MethodRow key={method.elementId} method={method} />
          ))}
        </ol>
      )}
      {explains ? (
        <p className="pt-2 text-muted-foreground text-xs" id={noteId}>
          {t.inspector.methods.ccNote}
        </p>
      ) : null}
    </section>
  );
}
