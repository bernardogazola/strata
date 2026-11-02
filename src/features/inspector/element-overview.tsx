import { type ReactNode, useCallback, useId } from "react";
import { countPhrase } from "@/features/repository/count-phrase";
import { formatDecimal, formatInteger, sentenceCase } from "@/i18n/format";
import { resolutionQualityLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import {
  type DependencyDirection,
  resolutionQualitySchema,
} from "@/protocol/enums";
import type { ElementDetail } from "@/protocol/graph";
import { ChevronGlyph, ResolutionGlyph } from "./inspector-glyphs";
import type { InspectedKind } from "./inspector-model";
import { MethodList } from "./method-list";

function OverviewSection({
  caption,
  children,
  title,
}: {
  readonly caption?: string;
  readonly children: ReactNode;
  readonly title: string;
}) {
  const titleId = useId();
  return (
    <section aria-labelledby={titleId} className="flex flex-col">
      <div className="flex flex-col gap-0.5 pb-1 text-muted-foreground text-xs">
        <h3 className="font-medium" id={titleId}>
          {title}
        </h3>
        {caption === undefined ? null : <p>{caption}</p>}
      </div>
      {children}
    </section>
  );
}

const ROW_CLASS = "flex items-start justify-between gap-3 border-b py-2";
const TERM_CLASS = "text-muted-foreground text-sm";
const VALUE_CLASS =
  "shrink-0 whitespace-nowrap font-medium text-foreground text-sm tabular-nums";

function Metric({
  term,
  value,
}: {
  readonly term: string;
  readonly value: string;
}) {
  return (
    <div className={ROW_CLASS}>
      <dt className={TERM_CLASS}>{term}</dt>
      <dd className={VALUE_CLASS}>{value}</dd>
    </div>
  );
}

const FACT_ROW_CLASS =
  "flex flex-col items-start gap-0.5 border-b py-2 @min-[240px]:flex-row @min-[240px]:items-center @min-[240px]:justify-between @min-[240px]:gap-3";

function DirectionFact({
  describedBy,
  direction,
  onOpen,
  term,
  value,
}: {
  readonly describedBy: string;
  readonly direction: DependencyDirection;
  readonly onOpen: (direction: DependencyDirection) => void;
  readonly term: string;
  readonly value: string;
}) {
  const termId = useId();
  const valueId = useId();
  const open = useCallback(() => onOpen(direction), [direction, onOpen]);
  return (
    <div className={FACT_ROW_CLASS}>
      <dt className={TERM_CLASS} id={termId}>
        {term}
      </dt>
      <dd className="flex">
        <button
          aria-describedby={describedBy}
          aria-labelledby={`${termId} ${valueId}`}
          className="-mx-1 flex items-center gap-1 rounded-sm px-1 text-left hover:bg-accent"
          onClick={open}
          type="button"
        >
          <span className={VALUE_CLASS} id={valueId}>
            {value}
          </span>
          <ChevronGlyph />
        </button>
      </dd>
    </div>
  );
}

export interface ElementOverviewProps {
  readonly detail: ElementDetail;
  readonly kind: InspectedKind;

  readonly onOpenDependencies: (direction: DependencyDirection) => void;

  readonly typeCount: number | null;
}

export function ElementOverview({
  detail,
  kind,
  onOpenDependencies,
  typeCount,
}: ElementOverviewProps) {
  const openDescriptionId = useId();
  const { graph, metrics } = detail;
  const mix = graph.resolutionMix;
  const references = mix.RESOLVED + mix.INFERRED + mix.UNRESOLVED_EXTERNAL;
  const { graphFacts } = t.inspector;

  return (
    <div className="flex flex-col gap-6 pt-4">
      <p hidden id={openDescriptionId}>
        {graphFacts.openDependencies}
      </p>

      <OverviewSection title={t.inspector.metrics.title}>
        <dl className="flex flex-col">
          {kind === "PACKAGE" && typeCount !== null ? (
            <div className={ROW_CLASS}>
              <dt className={`${TERM_CLASS} first-letter:uppercase`}>
                {t.inspector.typeCount}
              </dt>
              <dd className={VALUE_CLASS}>{formatInteger(typeCount)}</dd>
            </div>
          ) : null}
          <Metric
            term={t.inspector.metrics.loc}
            value={formatInteger(metrics.loc)}
          />
          <Metric
            term={t.inspector.metrics.ccSum}
            value={formatInteger(metrics.ccSum)}
          />
          <Metric
            term={t.inspector.metrics.ccMax}
            value={formatInteger(metrics.ccMax)}
          />
          <Metric
            term={t.inspector.metrics.ccMean}
            value={formatDecimal(metrics.ccMean)}
          />
        </dl>
      </OverviewSection>

      <OverviewSection title={graphFacts.title}>
        <dl className="flex flex-col">
          <DirectionFact
            describedBy={openDescriptionId}
            direction="OUT"
            onOpen={onOpenDependencies}
            term={t.inspector.dependsOn}
            value={countPhrase(graph.fanOut, graphFacts.dependsOnValue[kind])}
          />
          <DirectionFact
            describedBy={openDescriptionId}
            direction="IN"
            onOpen={onOpenDependencies}
            term={t.inspector.usedBy}
            value={countPhrase(graph.fanIn, graphFacts.usedByValue[kind])}
          />
          <div className={FACT_ROW_CLASS}>
            <dt className={TERM_CLASS}>{graphFacts.cycle}</dt>
            <dd className="@min-[240px]:pr-[18px] font-medium text-foreground text-sm">
              {graph.cycle === null
                ? graphFacts.cycleNone
                : countPhrase(graph.cycle.size, graphFacts.cycleSize[kind])}
            </dd>
          </div>
        </dl>
      </OverviewSection>

      <OverviewSection
        caption={countPhrase(references, t.inspector.resolution.caption[kind])}
        title={t.inspector.resolution.title}
      >
        <dl className="flex flex-col">
          {resolutionQualitySchema.options.map((quality) => (
            <div
              className="flex items-start gap-2.5 border-b py-2"
              key={quality}
            >
              <dt className={`flex flex-1 items-start gap-2.5 ${TERM_CLASS}`}>
                <span className="flex h-5 items-center">
                  <ResolutionGlyph
                    className="stroke-muted-foreground"
                    quality={quality}
                    width={18}
                  />
                </span>
                {sentenceCase(resolutionQualityLabels[quality])}
              </dt>
              <dd className={VALUE_CLASS}>
                {mix[quality] === 0
                  ? t.inspector.resolution.none
                  : formatInteger(mix[quality])}
              </dd>
            </div>
          ))}
        </dl>
      </OverviewSection>

      {kind === "TYPE" ? (
        <MethodList methods={detail.methods} methodTotal={detail.methodTotal} />
      ) : null}
    </div>
  );
}
