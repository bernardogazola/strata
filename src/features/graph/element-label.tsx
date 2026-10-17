import { t } from "@/i18n/messages";
import type { ElementKind } from "@/protocol/enums";
import { elementDisplayLabel } from "./adapter";

export interface ElementLabelProps {
  readonly className?: string;
  readonly kind: ElementKind;

  readonly label: string;

  readonly maxChars?: number;
}

export function ElementLabel({
  className,
  kind,
  label,
  maxChars,
}: ElementLabelProps) {
  const text = elementDisplayLabel(kind, label, maxChars);
  if (kind === "PACKAGE" && text === t.graph.defaultPackage) {
    return <span className={className}>{text}</span>;
  }
  return (
    <bdi className={className} lang="" translate="no">
      {text}
    </bdi>
  );
}
