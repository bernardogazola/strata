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

const MS_PER_TENTH = 100;
const TENTHS_PER_SECOND = MS_PER_SECOND / MS_PER_TENTH;
const TENTHS_PER_MINUTE = SECONDS_PER_MINUTE * TENTHS_PER_SECOND;

const tenthFormatter = new Intl.NumberFormat(LOCALE, {
  maximumFractionDigits: 1,
});

export const formatPreciseDuration = (milliseconds: number): string => {
  const wholeMilliseconds = Math.round(milliseconds);
  if (wholeMilliseconds < MS_PER_SECOND) {
    return `${wholeMilliseconds} ms`;
  }

  const tenths = Math.round(milliseconds / MS_PER_TENTH);
  if (tenths >= TENTHS_PER_MINUTE) {
    return formatDuration(milliseconds);
  }

  return `${tenthFormatter.format(tenths / TENTHS_PER_SECOND)} s`;
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

export const sentenceCase = (label: string): string => {
  const first = label.codePointAt(0);
  if (first === undefined) {
    return label;
  }

  const head = String.fromCodePoint(first);
  return `${head.toLocaleUpperCase(LOCALE)}${label.slice(head.length)}`;
};

export const hasLocaleData = (): boolean =>
  new Intl.DateTimeFormat(LOCALE).resolvedOptions().locale.startsWith("pt");

const PLACEHOLDER = /\{(\w+)\}/g;

export const interpolate = (
  template: string,
  params: Readonly<Record<string, number | string>>
): string =>
  template.replace(PLACEHOLDER, (token, name: string) => {
    if (!Object.hasOwn(params, name)) {
      return token;
    }

    const value = params[name];
    return typeof value === "number" ? formatInteger(value) : value;
  });
