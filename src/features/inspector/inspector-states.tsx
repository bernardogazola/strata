import { protocolErrorOf } from "@/bridge/analyzer";
import { SECONDARY_ACTION_CLASS } from "@/components/action-styles";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { NotOnMapReason } from "@/features/graph/node-summary";
import { formatProtocolError } from "@/i18n/errors";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import { InfoGlyph } from "./inspector-glyphs";
import { dependencyFailureOf } from "./inspector-model";

const SKELETON_ROWS = ["first", "second", "third", "fourth"] as const;

export function NotOnMapNote({ reason }: { readonly reason: NotOnMapReason }) {
  return (
    <div className="mb-4 flex gap-2 rounded-lg bg-accent px-3 py-2.5 text-muted-foreground text-xs">
      <InfoGlyph />
      <p>{t.inspector.notOnMap[reason]}</p>
    </div>
  );
}

export function LoadingDetail() {
  return (
    <div aria-busy="true" className="flex flex-col gap-3 pt-4">
      <div aria-hidden="true" className="flex flex-col gap-3">
        <Skeleton className="h-3 w-28 rounded-sm" />
        <Skeleton className="h-3 w-full rounded-sm" />
        <Skeleton className="h-3 w-full rounded-sm" />
        <Skeleton className="h-3 w-3/4 rounded-sm" />
      </div>
    </div>
  );
}

export function DescribeFailure({
  error,
  onRetry,
}: {
  readonly error: Error;
  readonly onRetry: () => void;
}) {
  const message = formatProtocolError(protocolErrorOf(error));
  return (
    <div className="flex flex-col items-start gap-3 pt-4">
      <div className="flex flex-col gap-1" role="alert">
        <p className="font-medium text-sm">{message.title}</p>
        {message.hint === undefined ? null : (
          <p className="text-muted-foreground text-sm">{message.hint}</p>
        )}
      </div>
      {message.retryable ? (
        <Button
          className={cn(SECONDARY_ACTION_CLASS, "shadow-control")}
          onClick={onRetry}
          size="sm"
          variant="outline"
        >
          {t.graph.retry}
        </Button>
      ) : null}
    </div>
  );
}

export function SummarySkeleton() {
  return (
    <Skeleton
      aria-hidden="true"
      className="my-[3px] h-2.5 w-[220px] max-w-full shrink-0 rounded-sm"
    />
  );
}

export function LoadingFirstPage() {
  return (
    <div aria-busy="true" className="border-t">
      <div aria-hidden="true">
        {SKELETON_ROWS.map((row) => (
          <div className="flex flex-col gap-2 border-b py-3" key={row}>
            <Skeleton className="h-2.5 w-36 rounded-sm" />
            <span className="flex justify-between">
              <Skeleton className="h-2 w-24 rounded-sm" />
              <Skeleton className="h-2 w-16 rounded-sm" />
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

export function DependenciesFailure({
  error,
  onRetry,
}: {
  readonly error: Error | null;
  readonly onRetry: () => void;
}) {
  const message = error === null ? null : dependencyFailureOf(error);
  return (
    <div className="flex flex-col items-start gap-2.5 border-t pt-3">
      <div className="flex flex-col gap-0.5" role="alert">
        <p className="font-medium text-foreground text-sm">
          {t.inspector.dependencies.failed}
        </p>
        {message === null ? null : (
          <p className="text-muted-foreground text-xs">{message.title}</p>
        )}
      </div>
      {message?.retryable ? (
        <Button
          className={cn(SECONDARY_ACTION_CLASS, "shadow-control")}
          onClick={onRetry}
          size="sm"
          variant="outline"
        >
          {t.graph.retry}
        </Button>
      ) : null}
    </div>
  );
}
