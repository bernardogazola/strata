import { useId } from "react";
import { formatInteger, interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import type { MethodSummary } from "@/protocol/graph";
import { BreakableName } from "./breakable-name";
import { lineRangeText } from "./inspector-labels";

const SIGNATURE_MAX_CHARS = 512;

const AFTER_SIGNATURE_SEPARATOR = /(?<=[(,.])/u;

function MethodCc({ cc }: { readonly cc: number | null }) {
  const shown =
    cc === null ? t.inspector.methods.ccNotApplicable : formatInteger(cc);
  return (
    <span
      className={
        cc === null
          ? "shrink-0 whitespace-nowrap text-muted-foreground text-xs leading-5"
          : "shrink-0 whitespace-nowrap font-medium text-foreground text-sm tabular-nums"
      }
    >
      <span className="sr-only">{`${t.inspector.methods.ccHeader} ${shown}`}</span>
      <span aria-hidden="true">{shown}</span>
    </span>
  );
}

function MethodRow({ method }: { readonly method: MethodSummary }) {
  const { cc, lines, signature } = method;
  return (
    <li className="flex items-start justify-between gap-3 border-b py-2">
      <span className="flex min-w-0 flex-col gap-0.5">
        <BreakableName
          breakAfter={AFTER_SIGNATURE_SEPARATOR}
          className="text-foreground text-sm"
          maxChars={SIGNATURE_MAX_CHARS}
          text={signature}
        />
        <span className="text-muted-foreground text-xs">
          {lineRangeText(lines)}
        </span>
      </span>
      <MethodCc cc={cc} />
    </li>
  );
}

export interface MethodListProps {
  readonly methods: readonly MethodSummary[];

  readonly methodTotal: number;
}

export function MethodList({ methodTotal, methods }: MethodListProps) {
  const titleId = useId();
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
          <span className="ml-auto text-right">
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
          aria-describedby={explains ? noteId : undefined}
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
