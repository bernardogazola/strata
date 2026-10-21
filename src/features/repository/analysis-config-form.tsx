import { CheckboxGroup } from "@base-ui/react/checkbox-group";
import { NumberField } from "@base-ui/react/number-field";
import { IconLoader2 } from "@tabler/icons-react";
import { useForm, useStore } from "@tanstack/react-form";
import { type FormEvent, type ReactNode, useCallback, useId } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldError,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { LOCALE } from "@/i18n/format";
import { historyScopeLabels, sourceRootKindLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import { historyScopeSchema, type SourceRootKind } from "@/protocol/enums";
import {
  type AnalysisConfiguration,
  analysisConfigurationSchema,
  type RepositoryDescription,
  type SourceRoot,
} from "@/protocol/repository";
import { PRIMARY_ACTION_CLASS } from "./action-styles";
import { countPhrase, filesInRoots } from "./count-phrase";
import { RepositoryText } from "./repository-text";

export const configurationFormSchema = analysisConfigurationSchema.refine(
  (config) =>
    config.historyScope !== "LAST_N_COMMITS" ||
    config.historyScopeCommits !== null,
  { error: t.config.history.invalid, path: ["historyScopeCommits"] }
);

const SOURCE_ROOT_GROUPS = [
  "MAIN",
  "GENERATED",
  "TEST",
] as const satisfies readonly SourceRootKind[];

const MIN_HISTORY_COMMITS = 1;

const byCodeUnit = (left: string, right: string): number => {
  if (left === right) {
    return 0;
  }
  return left < right ? -1 : 1;
};

export const defaultSourceRootIds = (
  roots: readonly SourceRoot[],
  includeTests: boolean
): string[] =>
  roots
    .filter((root) => root.kind !== "TEST" || includeTests)
    .map((root) => root.id);

export const toWireSourceRootIds = (
  roots: readonly SourceRoot[],
  checked: readonly string[],
  includeTests: boolean
): string[] | null => {
  const expected = new Set(defaultSourceRootIds(roots, includeTests));
  const actual = new Set(checked);
  const isDefault =
    expected.size === actual.size &&
    [...actual].every((id) => expected.has(id));
  return isDefault ? null : [...actual].sort(byCodeUnit);
};

const withKindChecked = (
  roots: readonly SourceRoot[],
  checked: readonly string[],
  kind: SourceRootKind,
  isChecked: boolean
): string[] => {
  const current = new Set(checked);
  return roots
    .filter((root) => (root.kind === kind ? isChecked : current.has(root.id)))
    .map((root) => root.id);
};

type DisabledReason = keyof typeof t.config.disabled;

const disabledReasonOf = (
  backendReady: boolean,
  runInFlight: boolean,
  selectedFiles: number
): DisabledReason | null => {
  if (!backendReady) {
    return "backend";
  }
  if (runInFlight) {
    return "runInFlight";
  }
  return selectedFiles === 0 ? "emptySelection" : null;
};

const selectValues = (state: {
  readonly values: AnalysisConfiguration;
}): AnalysisConfiguration => state.values;
const selectCanSubmit = (state: { readonly canSubmit: boolean }): boolean =>
  state.canSubmit;

export interface AnalysisConfigFormProps {
  readonly backendReady: boolean;
  readonly description: RepositoryDescription;
  readonly notice?: ReactNode;
  readonly onSubmit: (configuration: AnalysisConfiguration) => void;
  readonly pending: boolean;
  readonly runInFlight: boolean;
}

interface SourceRootRowProps {
  readonly baseId: string;
  readonly root: SourceRoot;
}

function SourceRootRow({ baseId, root }: SourceRootRowProps) {
  const inputId = `${baseId}-root-${root.id}`;
  const countId = `${inputId}-count`;
  return (
    <div className="flex min-h-9 items-center gap-2.5 px-3.5 py-1.5">
      <Checkbox aria-describedby={countId} id={inputId} value={root.id} />
      <label
        className="min-w-0 flex-1 text-muted-foreground text-sm [overflow-wrap:anywhere] peer-data-checked:text-foreground"
        htmlFor={inputId}
      >
        {root.label === "" ? (
          t.config.roots.repositoryRoot
        ) : (
          <RepositoryText value={root.label} />
        )}
      </label>
      <span
        className="w-[120px] shrink-0 text-right text-muted-foreground text-xs tabular-nums"
        id={countId}
      >
        {countPhrase(root.javaFileCount, t.config.roots.count)}
      </span>
    </div>
  );
}

interface SourceRootGroupProps {
  readonly baseId: string;
  readonly checked: ReadonlySet<string>;
  readonly isFirst: boolean;
  readonly kind: SourceRootKind;
  readonly onToggleKind: (kind: SourceRootKind, isChecked: boolean) => void;
  readonly roots: readonly SourceRoot[];
}

function SourceRootGroup({
  baseId,
  checked,
  isFirst,
  kind,
  onToggleKind,
  roots,
}: SourceRootGroupProps) {
  const headingId = `${baseId}-group-${kind}`;
  const isAllChecked = roots.every((root) => checked.has(root.id));
  const toggle = useCallback(
    () => onToggleKind(kind, !isAllChecked),
    [isAllChecked, kind, onToggleKind]
  );

  return (
    <fieldset
      aria-labelledby={headingId}
      className={cn("min-w-0", !isFirst && "border-t")}
    >
      <div
        className={cn(
          "flex min-h-8 items-center justify-between gap-3 px-3.5 pb-1",
          isFirst ? "pt-2" : "pt-3"
        )}
      >
        <p className="font-medium text-muted-foreground text-xs" id={headingId}>
          {sourceRootKindLabels[kind]}
          {kind === "TEST" ? (
            <>
              {" "}
              <span>{t.config.roots.followsTests}</span>
            </>
          ) : null}
        </p>
        {kind === "TEST" ? null : (
          <Button
            aria-describedby={headingId}
            className="-mr-2 text-accent-strong transition-[scale] active:scale-[0.98] motion-reduce:active:scale-100"
            onClick={toggle}
            size="xs"
            type="button"
            variant="link"
          >
            {isAllChecked ? t.config.roots.clearAll : t.config.roots.selectAll}
          </Button>
        )}
      </div>
      {roots.map((root) => (
        <SourceRootRow baseId={baseId} key={root.id} root={root} />
      ))}
    </fieldset>
  );
}

interface AnalyzeFooterProps {
  readonly isBlocked: boolean;
  readonly pending: boolean;
  readonly reason: DisabledReason | null;
  readonly reasonId: string;
  readonly selectedFiles: number;
  readonly selectedRoots: number;
}

function AnalyzeFooter({
  isBlocked,
  pending,
  reason,
  reasonId,
  selectedFiles,
  selectedRoots,
}: AnalyzeFooterProps) {
  return (
    <div className="flex flex-col gap-2 border-t pt-4 pb-8">
      <div className="flex items-center justify-between gap-6">
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="font-medium text-foreground text-sm tabular-nums">
            {filesInRoots(t.config.summary, selectedFiles, selectedRoots)}
          </p>
          <p className="text-muted-foreground text-xs">
            {t.config.summaryHint}
          </p>
        </div>
        <Button
          aria-describedby={reason === null ? undefined : reasonId}
          className={cn(PRIMARY_ACTION_CLASS, "px-[18px]")}
          disabled={isBlocked}
          focusableWhenDisabled
          type="submit"
        >
          {pending ? (
            <IconLoader2
              aria-hidden="true"
              className="animate-spin"
              stroke={1.5}
            />
          ) : null}
          {t.config.analyze}
        </Button>
      </div>
      {reason === null ? null : (
        <p
          className="self-end text-right text-muted-foreground text-xs"
          id={reasonId}
        >
          {t.config.disabled[reason]}
        </p>
      )}
    </div>
  );
}

export function AnalysisConfigForm({
  backendReady,
  description,
  notice,
  onSubmit,
  pending,
  runInFlight,
}: AnalysisConfigFormProps) {
  const roots = description.sourceRoots;
  const baseId = useId();
  const ids = {
    commits: `${baseId}-commits`,
    historyDescription: `${baseId}-history-description`,
    historyLabel: `${baseId}-history-label`,
    historyReason: `${baseId}-history-reason`,
    reason: `${baseId}-reason`,
    rebuild: `${baseId}-rebuild`,
    rebuildDescription: `${baseId}-rebuild-description`,
    rootsDescription: `${baseId}-roots-description`,
    tests: `${baseId}-tests`,
    testsDescription: `${baseId}-tests-description`,
    title: `${baseId}-title`,
  };

  const defaultValues: AnalysisConfiguration = {
    historyScope: "ALL",
    historyScopeCommits: null,
    includeTests: false,
    rebuildCache: false,
    sourceRootIds: defaultSourceRootIds(roots, false),
  };
  const form = useForm({
    defaultValues,
    onSubmit: ({ value }) =>
      onSubmit({
        historyScope: value.historyScope,
        historyScopeCommits:
          value.historyScope === "ALL" ? null : value.historyScopeCommits,
        includeTests: value.includeTests,
        rebuildCache: value.rebuildCache,
        sourceRootIds: toWireSourceRootIds(
          roots,
          value.sourceRootIds ?? [],
          value.includeTests
        ),
      }),
    validators: { onChange: configurationFormSchema },
  });

  const values = useStore(form.store, selectValues);
  const canSubmit = useStore(form.store, selectCanSubmit);
  const checked = new Set(values.sourceRootIds ?? []);
  const selected = roots.filter((root) => checked.has(root.id));
  const selectedFiles = selected.reduce(
    (total, root) => total + root.javaFileCount,
    0
  );
  const reason = disabledReasonOf(backendReady, runInFlight, selectedFiles);
  const isBlocked = reason !== null || pending || !canSubmit;

  const groups = SOURCE_ROOT_GROUPS.map((kind) => ({
    kind,
    roots: roots.filter((root) => root.kind === kind),
  })).filter((group) => group.roots.length > 0);

  const toggleTests = useCallback(
    (isChecked: boolean) => {
      form.setFieldValue("includeTests", isChecked);
      form.setFieldValue("sourceRootIds", (previous) =>
        withKindChecked(roots, previous ?? [], "TEST", isChecked)
      );
    },
    [form, roots]
  );

  const toggleKind = useCallback(
    (kind: SourceRootKind, isChecked: boolean) => {
      form.setFieldValue("sourceRootIds", (previous) =>
        withKindChecked(roots, previous ?? [], kind, isChecked)
      );
    },
    [form, roots]
  );

  const submit = useCallback(
    (event: FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      if (isBlocked) {
        return;
      }
      form.handleSubmit();
    },
    [form, isBlocked]
  );

  return (
    <form aria-labelledby={ids.title} noValidate onSubmit={submit}>
      <div className="flex flex-col gap-1.5 pb-5">
        <h2 className="font-medium text-foreground text-xl" id={ids.title}>
          {t.config.title}
        </h2>
        <p className="text-muted-foreground text-sm/5">
          {t.config.description}
        </p>
      </div>

      <Field
        className="items-start justify-between gap-6 py-3.5"
        orientation="horizontal"
      >
        <FieldContent>
          <FieldLabel className="text-base leading-5" htmlFor={ids.tests}>
            {t.config.includeTests.label}
          </FieldLabel>
          <FieldDescription id={ids.testsDescription}>
            {t.config.includeTests.description}
          </FieldDescription>
        </FieldContent>
        <Switch
          aria-describedby={ids.testsDescription}
          checked={values.includeTests}
          className="mt-0.5"
          id={ids.tests}
          name="includeTests"
          onCheckedChange={toggleTests}
        />
      </Field>

      <div className="py-3.5">
        <FieldSet aria-describedby={ids.rootsDescription} className="gap-3">
          <FieldLegend className="mb-1 leading-5" variant="legend">
            {t.config.roots.label}
          </FieldLegend>
          <p
            className="text-muted-foreground text-sm"
            id={ids.rootsDescription}
          >
            {t.config.roots.description}
          </p>
          <form.Field name="sourceRootIds">
            {(field) => (
              <CheckboxGroup
                className="flex flex-col rounded-lg border border-card-border bg-card py-1 shadow-card"
                data-slot="checkbox-group"
                onValueChange={field.handleChange}
                value={field.state.value ?? []}
              >
                {groups.map((group, position) => (
                  <SourceRootGroup
                    baseId={baseId}
                    checked={checked}
                    isFirst={position === 0}
                    key={group.kind}
                    kind={group.kind}
                    onToggleKind={toggleKind}
                    roots={group.roots}
                  />
                ))}
              </CheckboxGroup>
            )}
          </form.Field>
        </FieldSet>
      </div>

      <div className="py-3.5">
        <FieldSet className="gap-3">
          <FieldLegend
            className="mb-1 flex items-center gap-2 leading-5"
            variant="legend"
          >
            <span id={ids.historyLabel}>{t.config.history.label}</span>
            <Badge
              className="h-auto rounded-sm px-1.5 py-px font-normal text-muted-foreground"
              id={ids.historyReason}
              variant="secondary"
            >
              {t.config.history.unavailable}
            </Badge>
          </FieldLegend>
          <p
            className="text-muted-foreground text-sm"
            id={ids.historyDescription}
          >
            {t.config.history.description}
          </p>
          <div className="flex flex-wrap items-center gap-6 opacity-50">
            <form.Field name="historyScope">
              {(field) => (
                <RadioGroup
                  aria-describedby={`${ids.historyReason} ${ids.historyDescription}`}
                  aria-labelledby={ids.historyLabel}
                  className="flex w-auto flex-wrap items-center gap-6"
                  disabled
                  name={field.name}
                  onValueChange={field.handleChange}
                  value={field.state.value}
                >
                  {historyScopeSchema.options.map((scope) => (
                    <div className="flex items-center gap-2" key={scope}>
                      <RadioGroupItem
                        id={`${baseId}-history-${scope}`}
                        value={scope}
                      />
                      <label
                        className="text-foreground text-sm"
                        htmlFor={`${baseId}-history-${scope}`}
                      >
                        {historyScopeLabels[scope]}
                      </label>
                    </div>
                  ))}
                </RadioGroup>
              )}
            </form.Field>
            <form.Field name="historyScopeCommits">
              {(field) => {
                const isInvalid =
                  field.state.meta.isTouched && !field.state.meta.isValid;
                return (
                  <Field className="w-auto gap-1" data-invalid={isInvalid}>
                    <NumberField.Root
                      disabled
                      id={ids.commits}
                      locale={LOCALE}
                      min={MIN_HISTORY_COMMITS}
                      name={field.name}
                      onValueChange={field.handleChange}
                      value={field.state.value}
                    >
                      <NumberField.Group className="flex h-[30px] w-[132px] items-center gap-2 rounded-md border border-input px-2.5 shadow-control">
                        <NumberField.Input
                          aria-describedby={`${ids.historyReason} ${ids.historyDescription}`}
                          aria-invalid={isInvalid}
                          className="w-full min-w-0 bg-transparent text-foreground text-sm tabular-nums"
                          onBlur={field.handleBlur}
                        />
                        <label
                          className="shrink-0 text-muted-foreground text-xs"
                          htmlFor={ids.commits}
                        >
                          {t.config.history.commits}
                        </label>
                      </NumberField.Group>
                    </NumberField.Root>
                    {isInvalid ? (
                      <FieldError errors={field.state.meta.errors} />
                    ) : null}
                  </Field>
                );
              }}
            </form.Field>
          </div>
        </FieldSet>
      </div>

      <form.Field name="rebuildCache">
        {(field) => (
          <Field
            className="items-start gap-2.5 py-3.5"
            orientation="horizontal"
          >
            <Checkbox
              aria-describedby={ids.rebuildDescription}
              checked={field.state.value}
              className="mt-0.5"
              disabled
              id={ids.rebuild}
              name={field.name}
              onCheckedChange={field.handleChange}
            />
            <FieldContent>
              <FieldLabel className="text-base leading-5" htmlFor={ids.rebuild}>
                {t.config.rebuild.label}
              </FieldLabel>
              <FieldDescription id={ids.rebuildDescription}>
                {t.config.rebuild.description}
              </FieldDescription>
            </FieldContent>
          </Field>
        )}
      </form.Field>

      {notice === undefined ? null : <div className="pb-4">{notice}</div>}

      <AnalyzeFooter
        isBlocked={isBlocked}
        pending={pending}
        reason={reason}
        reasonId={ids.reason}
        selectedFiles={selectedFiles}
        selectedRoots={selected.length}
      />
    </form>
  );
}

const SKELETON_ROOTS = ["first", "second", "third"] as const;

export function AnalysisConfigFormSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-col gap-1.5">
      <Skeleton className="h-6 w-44" />
      <Skeleton className="h-4 w-96 max-w-full" />
      <div className="mt-8 flex flex-col rounded-lg border border-card-border py-1">
        {SKELETON_ROOTS.map((row) => (
          <div className="flex h-9 items-center gap-2.5 px-3.5" key={row}>
            <Skeleton className="size-4 rounded-sm" />
            <Skeleton className="h-3.5 w-48" />
            <Skeleton className="ml-auto h-3 w-20" />
          </div>
        ))}
      </div>
    </div>
  );
}
