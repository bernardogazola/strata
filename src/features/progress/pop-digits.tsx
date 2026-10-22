import {
  type AnimationEvent,
  useCallback,
  useLayoutEffect,
  useState,
} from "react";

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

export interface PopDigitsProps {
  readonly value: string;
}

export function PopDigits({ value }: PopDigitsProps) {
  const [pop, setPop] = useState<Pop>({
    generation: 0,
    rising: new Map(),
    shown: value,
  });

  useLayoutEffect(() => {
    if (value !== pop.shown) {
      setPop((previous) => nextPop(previous, value));
    }
  }, [value, pop.shown]);

  const settle = useCallback((event: AnimationEvent<HTMLSpanElement>): void => {
    const position = Number(event.currentTarget.dataset.position);
    setPop((previous) => settled(previous, position));
  }, []);

  if (pop.rising.size === 0) {
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
            className="relative animate-digit-pop motion-reduce:animate-none"
            data-position={position}
            key={`${position}:${rise.generation}`}
            onAnimationEnd={settle}
            style={{ animationDelay: `${rise.rank * STAGGER_MS}ms` }}
          >
            {character}
          </span>
        );
      })}
    </>
  );
}
