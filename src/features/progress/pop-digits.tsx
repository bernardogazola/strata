import {
  type AnimationEvent,
  useCallback,
  useLayoutEffect,
  useState,
  useSyncExternalStore,
} from "react";

const REDUCED_MOTION = "(prefers-reduced-motion: reduce)";

const subscribeToReducedMotion = (onChange: () => void): (() => void) => {
  const query = window.matchMedia(REDUCED_MOTION);
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
};

const prefersReducedMotion = (): boolean =>
  window.matchMedia(REDUCED_MOTION).matches;

const STAGGER_MS = 30;

interface Rise {
  readonly generation: number;
  readonly rank: number;
}

interface Pop {
  readonly generation: number;
  readonly rising: ReadonlyMap<number, Rise>;
  readonly shown: string;
}

const changedFromRight = (previous: string, next: string): number[] => {
  const changed: number[] = [];
  for (let position = 0; position < next.length; position += 1) {
    if (previous.at(-1 - position) !== next.at(-1 - position)) {
      changed.push(position);
    }
  }
  return changed;
};

const nextPop = (previous: Pop, value: string): Pop => {
  const generation = previous.generation + 1;
  const starting = changedFromRight(previous.shown, value)
    .filter((position) => !previous.rising.has(position))
    .sort((a, b) => b - a);
  const rising = new Map(
    [...previous.rising].filter(([position]) => position < value.length)
  );
  for (const [rank, position] of starting.entries()) {
    rising.set(position, { generation, rank });
  }
  return { generation, rising, shown: value };
};

const settled = (previous: Pop, position: number): Pop => {
  const rising = new Map(previous.rising);
  rising.delete(position);
  return { ...previous, rising };
};

const still = (previous: Pop, value: string): Pop => ({
  ...previous,
  rising: new Map(),
  shown: value,
});

export interface PopDigitsProps {
  readonly value: string;
}

export function PopDigits({ value }: PopDigitsProps) {
  const [pop, setPop] = useState<Pop>({
    generation: 0,
    rising: new Map(),
    shown: value,
  });
  const reducedMotion = useSyncExternalStore(
    subscribeToReducedMotion,
    prefersReducedMotion
  );

  useLayoutEffect(() => {
    if (value !== pop.shown) {
      setPop((previous) =>
        reducedMotion ? still(previous, value) : nextPop(previous, value)
      );
    }
  }, [value, pop.shown, reducedMotion]);

  const settleAt = useCallback((position: number): void => {
    setPop((previous) => settled(previous, position));
  }, []);
  const settle = useCallback(
    (event: AnimationEvent<HTMLSpanElement>): void =>
      settleAt(Number(event.currentTarget.dataset.position)),
    [settleAt]
  );
  // O React não expõe animationcancel, daí o listener nativo.
  const settleOnCancel = useCallback(
    (span: HTMLSpanElement): (() => void) => {
      const cancel = (): void => settleAt(Number(span.dataset.position));
      span.addEventListener("animationcancel", cancel);
      return () => span.removeEventListener("animationcancel", cancel);
    },
    [settleAt]
  );

  if (reducedMotion || pop.rising.size === 0) {
    return value;
  }

  return (
    <>
      {[...value].map((character, index) => {
        const position = value.length - 1 - index;
        const rise = pop.rising.get(position);
        return rise === undefined ? (
          character
        ) : (
          <span
            className="relative animate-digit-pop"
            data-position={position}
            key={`${position}:${rise.generation}`}
            onAnimationEnd={settle}
            ref={settleOnCancel}
            style={{ animationDelay: `${rise.rank * STAGGER_MS}ms` }}
          >
            {character}
          </span>
        );
      })}
    </>
  );
}
