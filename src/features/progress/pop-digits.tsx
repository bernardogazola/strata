import { useLayoutEffect, useRef, useState } from "react";

const STILL_BEFORE_POP_MS = 400;

const STAGGER_MS = 30;

interface Pop {
  readonly changed: readonly number[];
  readonly generation: number;
  readonly pops: boolean;
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

export interface PopDigitsProps {
  readonly value: string;
}

export function PopDigits({ value }: PopDigitsProps) {
  const [pop, setPop] = useState<Pop>({
    changed: [],
    generation: 0,
    pops: false,
    shown: value,
  });
  const lastChange = useRef(performance.now());

  useLayoutEffect(() => {
    if (value === pop.shown) {
      return;
    }
    const now = performance.now();
    const pops = now - lastChange.current >= STILL_BEFORE_POP_MS;
    lastChange.current = now;
    setPop((previous) => ({
      changed: changedFromRight(previous.shown, value),
      generation: previous.generation + 1,
      pops,
      shown: value,
    }));
  }, [value, pop.shown]);

  const order = pop.pops ? [...pop.changed].sort((a, b) => b - a) : [];
  if (order.length === 0) {
    return value;
  }

  return (
    <>
      {[...value].map((character, index) => {
        const position = value.length - 1 - index;
        const rank = order.indexOf(position);
        return rank < 0 ? (
          character
        ) : (
          <span
            className="relative animate-digit-pop motion-reduce:animate-none"
            key={`${position}:${pop.generation}`}
            style={{ animationDelay: `${rank * STAGGER_MS}ms` }}
          >
            {character}
          </span>
        );
      })}
    </>
  );
}
