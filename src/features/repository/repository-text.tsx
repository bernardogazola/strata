import { clampText, sanitizeUntrusted } from "@/lib/untrusted";

export interface RepositoryTextProps {
  readonly className?: string;
  readonly value: string;
}

export function RepositoryText({ className, value }: RepositoryTextProps) {
  return (
    <bdi className={className} lang="" translate="no">
      {clampText(sanitizeUntrusted(value))}
    </bdi>
  );
}
