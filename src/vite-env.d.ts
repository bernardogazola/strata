/// <reference types="vite/client" />

interface FakeTranscriptOverride {
  readonly method: string;

  readonly request: Record<string, unknown>;

  readonly result: unknown;
}

interface StrataGraphHandle {
  readonly edgeIds: () => string[];

  readonly fit: () => void;

  readonly fitElements: (ids: readonly string[]) => void;

  readonly nodeIds: () => string[];

  readonly renderedPosition: (id: string) => { x: number; y: number } | null;

  readonly setZoom: (level: number) => void;

  readonly style: (id: string, property: string) => string | null;

  readonly zoom: () => number;
}

interface FakeRequestLogEntry {
  readonly method: string;
  readonly payload: unknown;
}

interface Window {
  __STRATA_FAKE_EMIT__?: (event: string, payload: unknown) => number;

  __STRATA_FAKE_LOG__?: readonly FakeRequestLogEntry[];
  __STRATA_FAKE_TRANSCRIPTS__?: readonly FakeTranscriptOverride[];
  __STRATA_GRAPH__?: StrataGraphHandle;
}
