import { type ReactNode, useCallback, useId } from "react";
import { Button } from "@/components/ui/button";
import { formatInteger } from "@/i18n/format";
import {
  type EnglishTerm,
  elementKindLabels,
  englishTermAccessibleNames,
} from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";
import type { GraphNode } from "@/protocol/graph";

export interface NodeSummaryProps {
  readonly node: GraphNode | null;

  readonly onExpand: (elementId: string) => void;
}

function TermName({ term }: { readonly term: EnglishTerm }) {
  const name = englishTermAccessibleNames[term];
  return (
    <>
      <span aria-hidden="true" title={name}>
        {term}
      </span>
      <span className="sr-only">{name}</span>
    </>
  );
}

function Metric({
  term,
  value,
}: {
  readonly term: ReactNode;
  readonly value: string;
}) {
  return (
    <>
      <dt className="text-muted-foreground">{term}</dt>
      <dd className="tabular-nums">{value}</dd>
    </>
  );
}

function ExpandButton({
  elementId,
  onExpand,
}: {
  readonly elementId: string;
  readonly onExpand: (elementId: string) => void;
}) {
  const expand = useCallback(() => onExpand(elementId), [elementId, onExpand]);
  return (
    <Button className="self-start" onClick={expand} size="sm" variant="outline">
      {t.inspector.expand}
    </Button>
  );
}

export function NodeSummary({ node, onExpand }: NodeSummaryProps) {
  const titleId = useId();

  if (node === null) {
    return <p className="text-muted-foreground text-sm">{t.inspector.empty}</p>;
  }

  const isPackage = node.kind === "PACKAGE";

  return (
    <section
      aria-labelledby={titleId}
      className="flex select-text flex-col gap-3 text-sm"
    >
      <h2 className="break-words font-medium font-mono" id={titleId}>
        <bdi lang="" translate="no">
          {clampText(sanitizeUntrusted(node.label))}
        </bdi>
      </h2>

      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
        <Metric term={t.inspector.kind} value={elementKindLabels[node.kind]} />
        <Metric
          term={<TermName term="LOC" />}
          value={formatInteger(node.loc)}
        />
        <Metric
          term={<TermName term="fan-in" />}
          value={formatInteger(node.fanIn)}
        />
        <Metric
          term={<TermName term="fan-out" />}
          value={formatInteger(node.fanOut)}
        />
        {isPackage ? (
          <Metric
            term={t.inspector.typeCount}
            value={formatInteger(node.typeCount)}
          />
        ) : null}
      </dl>

      {isPackage ? (
        <ExpandButton elementId={node.id} onExpand={onExpand} />
      ) : null}
    </section>
  );
}
