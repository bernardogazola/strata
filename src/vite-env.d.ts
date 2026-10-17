/// <reference types="vite/client" />

interface FakeTranscriptOverride {
  readonly method: string;

  readonly request: Record<string, unknown>;

  readonly result: unknown;
}

interface StrataGraphHandle {
  readonly edgeIds: () => string[];

  readonly fit: () => void;

  readonly nodeIds: () => string[];

  readonly renderedPosition: (id: string) => { x: number; y: number } | null;

  readonly setZoom: (level: number) => void;

  readonly style: (id: string, property: string) => string | null;

  readonly zoom: () => number;
}

interface Window {
  __STRATA_FAKE_TRANSCRIPTS__?: readonly FakeTranscriptOverride[];
  __STRATA_GRAPH__?: StrataGraphHandle;
}
