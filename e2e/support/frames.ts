import type { Page } from "@playwright/test";

export const IDLE_BASELINE_MS = 3000;

const P50 = 0.5;
const P95 = 0.95;

export interface FrameSummary {
  readonly baselineMs: number;

  readonly frameRatio: number;
  readonly frames: number;
  readonly max: number | null;

  readonly p50: number | null;
  readonly p95: number | null;

  readonly spanMs: number;
}

interface FrameRecorder {
  running: boolean;
  readonly stamps: number[];
}

type RecorderWindow = Window & { __STRATA_FRAMES__?: FrameRecorder };

const intervalsOf = (stamps: readonly number[]): number[] =>
  stamps.slice(1).map((stamp, index) => stamp - stamps[index]);

const nearestRank = (
  values: readonly number[],
  fraction: number
): number | null => {
  if (values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((a, b) => a - b);
  const rank = Math.ceil(fraction * sorted.length) - 1;
  return sorted[Math.min(sorted.length - 1, Math.max(0, rank))];
};

const median = (values: readonly number[]): number | null => {
  if (values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
};

export const measureIdleBaseline = async (
  page: Page,
  durationMs: number = IDLE_BASELINE_MS
): Promise<number> => {
  const stamps = await page.evaluate(
    (windowMs) =>
      new Promise<number[]>((resolve) => {
        const collected: number[] = [];
        const tick = (stamp: number) => {
          collected.push(stamp);
          if (stamp - collected[0] >= windowMs) {
            resolve(collected);
            return;
          }
          requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
      }),
    durationMs
  );
  const baseline = median(intervalsOf(stamps));
  if (baseline === null || baseline <= 0) {
    throw new RangeError(
      `a linha de base ociosa precisa de dois frames de animação; vieram ${stamps.length} em ${durationMs} ms`
    );
  }
  return baseline;
};

export const startFrameRecording = (page: Page): Promise<void> =>
  page.evaluate(() => {
    const host = window as RecorderWindow;
    const previous = host.__STRATA_FRAMES__;
    if (previous !== undefined) {
      previous.running = false;
    }
    const recorder: FrameRecorder = { running: true, stamps: [] };
    host.__STRATA_FRAMES__ = recorder;
    const tick = (stamp: number) => {
      if (!recorder.running) {
        return;
      }
      recorder.stamps.push(stamp);
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });

export const stopFrameRecording = (page: Page): Promise<number[]> =>
  page.evaluate(() => {
    const host = window as RecorderWindow;
    const recorder = host.__STRATA_FRAMES__;
    if (recorder === undefined) {
      throw new Error("nenhum gravador de frames está rodando nesta página");
    }
    recorder.running = false;
    host.__STRATA_FRAMES__ = undefined;
    return [...recorder.stamps];
  });

export const recordGesture = async (
  page: Page,
  gesture: () => Promise<void>
): Promise<number[]> => {
  await startFrameRecording(page);
  let stamps: number[] = [];
  try {
    await gesture();
  } finally {
    stamps = await stopFrameRecording(page);
  }
  return stamps;
};

export const summarizeFrames = (
  stamps: readonly number[],
  baselineMs: number
): FrameSummary => {
  if (!(Number.isFinite(baselineMs) && baselineMs > 0)) {
    throw new RangeError(
      `baselineMs precisa ser uma duração positiva, e não ${baselineMs}`
    );
  }
  const intervals = intervalsOf(stamps);
  const last = stamps.at(-1);
  const spanMs = last === undefined ? 0 : last - stamps[0];
  return {
    baselineMs,
    frameRatio: spanMs > 0 ? intervals.length / (spanMs / baselineMs) : 0,
    frames: intervals.length,
    max: intervals.length > 0 ? Math.max(...intervals) : null,
    p50: nearestRank(intervals, P50),
    p95: nearestRank(intervals, P95),
    spanMs,
  };
};
