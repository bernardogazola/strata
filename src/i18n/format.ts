export const LOCALE = "pt-BR";

const SECONDS_PER_MINUTE = 60;
const SECONDS_PER_HOUR = 3600;
const MS_PER_SECOND = 1000;

const dateFormatter = new Intl.DateTimeFormat(LOCALE, {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

const dateTimeFormatter = new Intl.DateTimeFormat(LOCALE, {
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  month: "2-digit",
  year: "numeric",
});

const monthYearFormatter = new Intl.DateTimeFormat(LOCALE, {
  month: "long",
  year: "numeric",
});

const integerFormatter = new Intl.NumberFormat(LOCALE, {
  maximumFractionDigits: 0,
});

const percentFormatter = new Intl.NumberFormat(LOCALE, {
  maximumFractionDigits: 0,
  style: "percent",
});

const relativeTimeFormatter = new Intl.RelativeTimeFormat(LOCALE, {
  numeric: "auto",
});

const pluralRules = new Intl.PluralRules(LOCALE);

export const formatDate = (value: Date): string => dateFormatter.format(value);

export const formatDateTime = (value: Date): string =>
  dateTimeFormatter.format(value);

export const formatMonthYear = (value: Date): string =>
  monthYearFormatter.format(value);

export const formatInteger = (value: number): string =>
  integerFormatter.format(value);

export const formatPercent = (ratio: number): string =>
  percentFormatter.format(ratio);

const RELATIVE_THRESHOLDS: ReadonlyArray<{
  divisor: number;
  unit: Intl.RelativeTimeFormatUnit;
}> = [
  { divisor: 1, unit: "second" },
  { divisor: SECONDS_PER_MINUTE, unit: "minute" },
  { divisor: SECONDS_PER_HOUR, unit: "hour" },
  { divisor: SECONDS_PER_HOUR * 24, unit: "day" },
  { divisor: SECONDS_PER_HOUR * 24 * 30, unit: "month" },
  { divisor: SECONDS_PER_HOUR * 24 * 365, unit: "year" },
];

export const formatRelativeTime = (value: Date, now: Date): string => {
  const elapsedSeconds = (value.getTime() - now.getTime()) / MS_PER_SECOND;
  const magnitude = Math.abs(elapsedSeconds);

  const [smallest] = RELATIVE_THRESHOLDS;
  let chosen = smallest;
  for (const candidate of RELATIVE_THRESHOLDS) {
    const { divisor } = candidate;
    if (magnitude >= divisor) {
      chosen = candidate;
    }
  }

  const { divisor, unit } = chosen;
  return relativeTimeFormatter.format(
    Math.round(elapsedSeconds / divisor),
    unit
  );
};

export const formatDuration = (milliseconds: number): string => {
  const totalSeconds = Math.round(milliseconds / MS_PER_SECOND);

  if (totalSeconds < SECONDS_PER_MINUTE) {
    return `${totalSeconds} s`;
  }

  const hours = Math.floor(totalSeconds / SECONDS_PER_HOUR);
  const minutes = Math.floor(
    (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
  );
  const seconds = totalSeconds % SECONDS_PER_MINUTE;

  if (hours > 0) {
    return `${hours} h ${minutes} min`;
  }

  return `${minutes} min ${seconds} s`;
};

export const plural = (
  count: number,
  forms: { one: string; other: string; zero?: string }
): string => {
  if (count === 0 && forms.zero !== undefined) {
    return forms.zero;
  }

  return pluralRules.select(count) === "one" ? forms.one : forms.other;
};

export const hasLocaleData = (): boolean =>
  new Intl.DateTimeFormat(LOCALE).resolvedOptions().locale.startsWith("pt");
