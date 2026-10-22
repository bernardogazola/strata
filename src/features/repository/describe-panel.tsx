import { Fragment, type ReactNode } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { buildSystemLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import type { RepositoryDescription } from "@/protocol/repository";
import { countPhrase, filesInRoots } from "./count-phrase";
import { RepositoryText } from "./repository-text";

const SHORT_COMMIT_LENGTH = 7;

const SKELETON_ROWS = [
  "buildSystem",
  "modules",
  "branch",
  "commit",
  "files",
  "java",
] as const;

export interface DescribePanelProps {
  readonly description: RepositoryDescription;
}

interface FactProps {
  readonly children: ReactNode;
  readonly label: string;
}

function Fact({ children, label }: FactProps) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5 border-b py-2 text-sm lg:flex-row lg:items-baseline lg:gap-0 lg:py-[11px]">
      <dt className="text-muted-foreground lg:w-[148px] lg:shrink-0">
        {label}
      </dt>
      <dd className="min-w-0 select-text text-foreground [overflow-wrap:anywhere]">
        {children}
      </dd>
    </div>
  );
}

const keyedByOccurrence = (values: readonly string[]) => {
  const seen = new Map<string, number>();
  return values.map((value) => {
    const occurrence = seen.get(value) ?? 0;
    seen.set(value, occurrence + 1);
    return { key: `${value}#${occurrence}`, value };
  });
};

interface DeclaredModulesProps {
  readonly modules: readonly string[];
}

function DeclaredModules({ modules }: DeclaredModulesProps) {
  if (modules.length === 0) {
    return t.describe.facts.noModules;
  }
  return keyedByOccurrence(modules).map(({ key, value }, position) => (
    <Fragment key={key}>
      {position === 0 ? null : ", "}
      <RepositoryText value={value} />
    </Fragment>
  ));
}

export function DescribePanel({ description }: DescribePanelProps) {
  const { defaultBranch, headCommitId, sourceRoots } = description;
  const javaFileCount = sourceRoots.reduce(
    (total, root) => total + root.javaFileCount,
    0
  );

  return (
    <div className="flex flex-col gap-7">
      <h1
        className="select-text font-medium text-2xl text-foreground [overflow-wrap:anywhere]"
        tabIndex={-1}
      >
        <RepositoryText value={description.displayName} />
      </h1>
      <div className="flex flex-col gap-3">
        <dl className="grid grid-cols-2 gap-x-8 border-t lg:flex lg:flex-col">
          <Fact label={t.describe.facts.buildSystem}>
            {buildSystemLabels[description.buildSystem]}
          </Fact>
          <Fact label={t.describe.facts.modules}>
            <DeclaredModules modules={description.declaredModules} />
          </Fact>
          <Fact label={t.describe.facts.branch}>
            {defaultBranch === null ? (
              t.describe.facts.detached
            ) : (
              <RepositoryText value={defaultBranch} />
            )}
          </Fact>
          <Fact label={t.describe.facts.commit}>
            {headCommitId === null ? (
              t.describe.facts.unborn
            ) : (
              <RepositoryText
                className="tabular-nums"
                value={headCommitId.slice(0, SHORT_COMMIT_LENGTH)}
              />
            )}
          </Fact>
          <Fact label={t.describe.facts.files}>
            <span className="tabular-nums">
              {countPhrase(
                description.totalFileCount,
                t.describe.facts.filesValue
              )}
            </span>
          </Fact>
          <Fact label={t.describe.facts.java}>
            <span className="tabular-nums">
              {filesInRoots(
                t.describe.facts.javaValue,
                javaFileCount,
                sourceRoots.length
              )}
            </span>
          </Fact>
        </dl>
        {description.isShallow ? (
          <p className="text-muted-foreground text-xs">
            {t.analysis.partial.shallowClone}
          </p>
        ) : null}
      </div>
    </div>
  );
}

export function DescribePanelSkeleton() {
  return (
    <div className="flex flex-col gap-7">
      <p className="sr-only" role="status">
        {t.describe.loading}
      </p>
      <Skeleton className="h-[30px] w-48" />
      <div
        aria-hidden="true"
        className="grid grid-cols-2 gap-x-8 border-t lg:flex lg:flex-col"
      >
        {SKELETON_ROWS.map((row) => (
          <div
            className="flex flex-col gap-2 border-b py-[11px] lg:flex-row lg:items-center lg:gap-0"
            key={row}
          >
            <Skeleton className="h-3.5 w-24" />
            <Skeleton className="h-3.5 w-28 lg:ml-[52px]" />
          </div>
        ))}
      </div>
    </div>
  );
}
