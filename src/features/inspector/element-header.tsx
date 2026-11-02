import { type RefObject, useLayoutEffect, useRef } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { ElementKindIcon } from "@/features/graph/element-kind-icon";
import { sentenceCase } from "@/i18n/format";
import { elementKindLabels, typeKindLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";
import type { ElementDetail } from "@/protocol/graph";
import { BreakableName } from "./breakable-name";
import { lineRangeText } from "./inspector-labels";
import type { InspectedKind } from "./inspector-model";

export interface FocusHandoff {
  readonly from: string;
  readonly to: string;
}

const isFocusUnclaimed = (inspector: HTMLElement | null): boolean => {
  const active = document.activeElement;
  if (active === null || active === document.body) {
    return true;
  }
  if (inspector?.contains(active) === true) {
    return true;
  }
  return (
    active.closest("nav")?.getAttribute("aria-label") ===
    t.shell.breadcrumb.label
  );
};

const headerTermClass = "sr-only";

const kindTextOf = (
  kind: InspectedKind,
  detail: ElementDetail | undefined
): string | null => {
  if (kind === "PACKAGE") {
    return sentenceCase(elementKindLabels.PACKAGE);
  }
  return detail?.typeKind === null || detail?.typeKind === undefined
    ? null
    : sentenceCase(typeKindLabels[detail.typeKind]);
};

const TITLE_CLASS =
  "rounded-sm font-medium text-foreground text-xl tracking-[-0.01em]";

export function ElementHeader({
  detail,
  elementId,
  handoff,
  inspectorRef,
  kind,
  label,
  settled,
}: {
  readonly detail: ElementDetail | undefined;
  readonly elementId: string;
  readonly handoff: RefObject<FocusHandoff | null>;

  readonly inspectorRef: RefObject<HTMLElement | null>;
  readonly kind: InspectedKind;
  readonly label: string;

  readonly settled: boolean;
}) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const kindText = kindTextOf(kind, detail);

  useLayoutEffect(() => {
    const pending = handoff.current;
    if (pending === null) {
      return;
    }
    if (pending.to !== elementId) {
      if (pending.from !== elementId) {
        handoff.current = null;
      }
      return;
    }
    if (!isFocusUnclaimed(inspectorRef.current)) {
      handoff.current = null;
      return;
    }
    const heading = headingRef.current;
    if (heading !== null) {
      heading.focus();
      handoff.current = null;
    }
  });

  return (
    <div className="flex select-text flex-col gap-1.5">
      <dl className="flex items-center gap-1.5 text-muted-foreground text-xs">
        <dt className={headerTermClass}>{t.inspector.kind}</dt>
        <dd className="flex items-center gap-1.5">
          <ElementKindIcon kind={kind} />
          {kindText === null ? (
            <Skeleton className="h-3 w-12 rounded-sm" />
          ) : (
            <span>{kindText}</span>
          )}
        </dd>
        {detail === undefined || detail.modifiers.length === 0 ? null : (
          <>
            <dt className={headerTermClass}>{t.inspector.header.modifiers}</dt>
            <dd className="ml-auto text-right">
              <bdi lang="" translate="no">
                {clampText(sanitizeUntrusted(detail.modifiers.join(" ")))}
              </bdi>
            </dd>
          </>
        )}
      </dl>
      {settled ? (
        <h2 className={TITLE_CLASS} ref={headingRef} tabIndex={-1}>
          <BreakableName kind={kind} text={label} />
        </h2>
      ) : (
        <p className={TITLE_CLASS}>
          <BreakableName kind={kind} text={label} />
        </p>
      )}
      {detail === undefined ? (
        <Skeleton className="h-3 w-40 rounded-sm" />
      ) : (
        <HeaderFacts detail={detail} />
      )}
    </div>
  );
}

function HeaderFacts({ detail }: { readonly detail: ElementDetail }) {
  const { filePath, isTest, kind, lines, qualifiedName } = detail;
  return (
    <dl className="flex flex-col text-muted-foreground text-xs">
      <dt className={headerTermClass}>{t.inspector.header.qualifiedName}</dt>
      <dd>
        <BreakableName text={qualifiedName} />
      </dd>
      {filePath === null ? null : (
        <>
          <dt className={headerTermClass}>{t.inspector.header.file}</dt>
          <dd className="pt-3">
            <BreakableName text={filePath} />
          </dd>
        </>
      )}
      {kind === "TYPE" ? (
        <dd className="pt-0.5">{lineRangeText(lines)}</dd>
      ) : null}
      {isTest ? <dd className="pt-0.5">{t.inspector.header.test}</dd> : null}
    </dl>
  );
}
