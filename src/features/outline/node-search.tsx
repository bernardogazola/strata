import { useQuery } from "@tanstack/react-query";
import {
  type ChangeEvent,
  type FocusEvent,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
  type RefObject,
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
} from "react";
import { BridgeError } from "@/bridge/analyzer";
import { Button } from "@/components/ui/button";
import { ElementLabel } from "@/features/graph/element-label";
import { isRevealable } from "@/features/shell/repository-search";
import { formatProtocolError } from "@/i18n/errors";
import { interpolate, plural } from "@/i18n/format";
import { elementKindLabels } from "@/i18n/labels";
import { t } from "@/i18n/messages";
import { clampText, sanitizeUntrusted } from "@/lib/untrusted";
import type { SearchHit } from "@/protocol/graph";
import { SEARCH_QUERY_MAX_CHARS } from "@/protocol/primitives";
import { graphSearchQueryOptions } from "@/queries/options";
import { useAppStore } from "@/state/app-store";

export const SEARCH_DEBOUNCE_MS = 250;

const SEARCH_RESULTS_LIMIT = 50;

const NAVIGATION_KEYS = new Set(["ArrowDown", "ArrowUp", "End", "Home"]);

const INPUT_CLASS =
  "h-8 w-full min-w-0 rounded-md border border-input bg-transparent px-2.5 text-sm outline-none transition-colors placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30";

const OPTION_CLASS =
  "flex cursor-pointer flex-col gap-0.5 rounded-md px-2 py-1 text-sm outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 aria-disabled:cursor-default aria-disabled:opacity-60 aria-selected:bg-primary/10";

export interface NodeSearchProps {
  readonly analysisId: string;

  readonly focus: string | null;

  readonly onReveal: (hit: SearchHit) => void;
}

function useDebouncedValue(value: string, delayMs: number): string {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

const optionOf = (target: EventTarget | null): HTMLElement | null =>
  target instanceof Element
    ? target.closest<HTMLElement>('[role="option"]')
    : null;

const crossedOptionId = (
  event: MouseEvent<HTMLElement>
): string | undefined => {
  const option = optionOf(event.target);
  const other = event.relatedTarget;
  if (option === null || (other instanceof Node && option.contains(other))) {
    return;
  }
  return option.dataset.elementId;
};

const targetFor = (
  key: string,
  option: HTMLElement,
  list: HTMLElement
): Element | null => {
  if (key === "ArrowDown") {
    return option.nextElementSibling;
  }
  if (key === "ArrowUp") {
    return option.previousElementSibling;
  }
  return key === "Home" ? list.firstElementChild : list.lastElementChild;
};

const focusElement = (element: Element | null | undefined): void => {
  if (element instanceof HTMLElement) {
    element.focus();
  }
};

const announcementOf = (count: number): string => {
  const phrase = interpolate(plural(count, t.search.count), { count });
  return count < SEARCH_RESULTS_LIMIT
    ? phrase
    : interpolate(t.search.capped, {
        count: phrase,
        limit: SEARCH_RESULTS_LIMIT,
      });
};

export function NodeSearch({
  analysisId,
  focus,
  onReveal,
}: NodeSearchProps): ReactNode {
  const inputId = useId();
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [input, setInput] = useState("");
  const debounced = useDebouncedValue(input, SEARCH_DEBOUNCE_MS).trim();
  const query = input.trim() === "" ? "" : debounced;
  const { data, error, isFetching, isPlaceholderData, refetch } = useQuery(
    graphSearchQueryOptions(analysisId, query)
  );

  const searching = query !== "";
  const failure = searching ? error : null;
  const hits = searching && failure === null ? (data?.results ?? []) : [];
  const settled =
    searching && failure === null && data !== undefined && !isPlaceholderData;

  const handleChange = useCallback((event: ChangeEvent<HTMLInputElement>) => {
    setInput(event.target.value);
  }, []);

  const handleInputKeyDown = useCallback(
    (event: KeyboardEvent<HTMLInputElement>) => {
      const first = listRef.current?.firstElementChild;
      if (event.key === "ArrowDown" && first instanceof HTMLElement) {
        event.preventDefault();
        first.focus();
      }
    },
    []
  );

  const returnToInput = useCallback(() => {
    inputRef.current?.focus();
  }, []);

  const onRetry = useCallback(() => {
    refetch();
  }, [refetch]);

  return (
    <search className="flex shrink-0 flex-col gap-2">
      <label className="sr-only" htmlFor={inputId}>
        {t.search.label}
      </label>
      <input
        aria-controls={hits.length > 0 ? listId : undefined}
        autoComplete="off"
        className={INPUT_CLASS}
        id={inputId}
        maxLength={SEARCH_QUERY_MAX_CHARS}
        onChange={handleChange}
        onKeyDown={handleInputKeyDown}
        placeholder={t.search.placeholder}
        ref={inputRef}
        spellCheck={false}
        type="search"
        value={input}
      />
      <p
        aria-live="polite"
        className="text-muted-foreground text-xs"
        role="status"
      >
        {settled ? announcementOf(hits.length) : ""}
      </p>
      {failure === null ? null : (
        <SearchFailure error={failure} onRetry={onRetry} />
      )}
      {hits.length === 0 ? null : (
        <SearchResults
          busy={isFetching}
          focus={focus}
          hits={hits}
          id={listId}
          listRef={listRef}
          onReturn={returnToInput}
          onReveal={onReveal}
        />
      )}
    </search>
  );
}

interface SearchResultsProps {
  readonly busy: boolean;
  readonly focus: string | null;

  readonly hits: readonly SearchHit[];
  readonly id: string;
  readonly listRef: RefObject<HTMLDivElement | null>;

  readonly onReturn: () => void;
  readonly onReveal: (hit: SearchHit) => void;
}

function SearchResults({
  busy,
  focus,
  hits,
  id,
  listRef,
  onReturn,
  onReveal,
}: SearchResultsProps) {
  const [rovingId, setRovingId] = useState<string | null>(null);
  const tabbableId = hits.some((hit) => hit.elementId === rovingId)
    ? rovingId
    : (hits[0]?.elementId ?? null);

  const writtenRef = useRef<string | null>(null);

  const write = useCallback((elementId: string) => {
    writtenRef.current = elementId;
    useAppStore.getState().setActiveElementId(elementId);
  }, []);

  const release = useCallback((elementId: string) => {
    useAppStore.getState().releaseActiveElementId(elementId);
    if (writtenRef.current === elementId) {
      writtenRef.current = null;
    }
  }, []);

  useEffect(() => {
    const written = writtenRef.current;
    if (written !== null && !hits.some((hit) => hit.elementId === written)) {
      release(written);
    }
  }, [hits, release]);

  useEffect(
    () => () => {
      const written = writtenRef.current;
      if (written !== null) {
        useAppStore.getState().releaseActiveElementId(written);
      }
    },
    []
  );

  const activate = useCallback(
    (option: HTMLElement) => {
      const hit = hits.find(
        (candidate) => candidate.elementId === option.dataset.elementId
      );
      if (hit !== undefined && isRevealable(hit)) {
        onReveal(hit);
      }
    },
    [hits, onReveal]
  );

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      const option = optionOf(event.target);
      if (option === null) {
        return;
      }
      const leaves =
        event.key === "Escape" ||
        (event.key === "ArrowUp" && option.previousElementSibling === null);
      if (leaves) {
        event.preventDefault();
        onReturn();
      } else if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        activate(option);
      } else if (NAVIGATION_KEYS.has(event.key)) {
        event.preventDefault();
        focusElement(targetFor(event.key, option, event.currentTarget));
      }
    },
    [activate, onReturn]
  );

  const handleClick = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      const option = optionOf(event.target);
      if (option === null) {
        return;
      }
      option.focus();
      activate(option);
    },
    [activate]
  );

  const handleFocus = useCallback(
    (event: FocusEvent<HTMLDivElement>) => {
      const elementId = optionOf(event.target)?.dataset.elementId;
      if (elementId !== undefined) {
        setRovingId(elementId);
        write(elementId);
      }
    },
    [write]
  );

  const handleBlur = useCallback(
    (event: FocusEvent<HTMLDivElement>) => {
      const next = event.relatedTarget;
      if (next instanceof Node && event.currentTarget.contains(next)) {
        return;
      }
      const elementId = optionOf(event.target)?.dataset.elementId;
      if (elementId !== undefined) {
        release(elementId);
      }
    },
    [release]
  );

  const handleMouseOver = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      const elementId = crossedOptionId(event);
      if (elementId !== undefined) {
        write(elementId);
      }
    },
    [write]
  );

  const handleMouseOut = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      const elementId = crossedOptionId(event);
      if (elementId !== undefined) {
        release(elementId);
      }
    },
    [release]
  );

  return (
    <div
      aria-busy={busy}
      aria-label={t.search.results}
      className="flex max-h-64 flex-col gap-0.5 overflow-y-auto"
      id={id}
      onBlur={handleBlur}
      onClick={handleClick}
      onFocus={handleFocus}
      onKeyDown={handleKeyDown}
      onMouseOut={handleMouseOut}
      onMouseOver={handleMouseOver}
      ref={listRef}
      role="listbox"
    >
      {hits.map((hit) => (
        <SearchOption
          hit={hit}
          key={hit.elementId}
          selected={hit.elementId === focus}
          tabbable={hit.elementId === tabbableId}
        />
      ))}
    </div>
  );
}

function SearchOption({
  hit,
  selected,
  tabbable,
}: {
  readonly hit: SearchHit;
  readonly selected: boolean;
  readonly tabbable: boolean;
}) {
  const revealable = isRevealable(hit);
  return (
    <div
      aria-disabled={revealable ? undefined : true}
      aria-selected={selected}
      className={OPTION_CLASS}
      data-element-id={hit.elementId}
      role="option"
      tabIndex={tabbable ? 0 : -1}
    >
      <span className="flex min-w-0 items-baseline gap-2">
        <ElementLabel
          className="truncate font-medium"
          kind={hit.kind}
          label={hit.label}
        />
        <span className="shrink-0 text-muted-foreground text-xs">
          {elementKindLabels[hit.kind]}
        </span>
      </span>
      <bdi
        className="truncate text-muted-foreground text-xs"
        lang=""
        translate="no"
      >
        {clampText(sanitizeUntrusted(hit.qualifiedName))}
      </bdi>
    </div>
  );
}

function SearchFailure({
  error,
  onRetry,
}: {
  readonly error: Error;
  readonly onRetry: () => void;
}) {
  const message = formatProtocolError(
    error instanceof BridgeError
      ? error.protocolError
      : { code: "INTERNAL_UNEXPECTED", origin: "frontend" }
  );
  return (
    <div className="flex flex-col items-start gap-2">
      <div className="flex flex-col gap-1" role="alert">
        <p className="font-medium text-sm">{message.title}</p>
        {message.hint === undefined ? null : (
          <p className="text-muted-foreground text-xs">{message.hint}</p>
        )}
      </div>
      {message.retryable ? (
        <Button onClick={onRetry} size="sm" variant="outline">
          {t.graph.retry}
        </Button>
      ) : null}
    </div>
  );
}
