import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { DependencyDirection } from "@/protocol/enums";
import { type ProtocolError, protocolErrorSchema } from "@/protocol/errors";
import {
  type DependencyPage,
  dependencyEvidenceResultSchema,
  dependencyPageSchema,
  type ElementDetail,
  elementDetailSchema,
  type GraphNode,
  type GraphSlice,
  graphSliceSchema,
  type OutlineNode,
  type OutlinePage,
  outlinePageSchema,
  type RelationshipEvidence,
  type SearchHit,
  searchResultSchema,
} from "@/protocol/graph";
import { analysisStatusReportSchema } from "@/protocol/repository";

const FIXTURES_DIR = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../protocol/fixtures"
);

const JSONL_SUFFIX = ".jsonl";

interface Frame {
  readonly error?: unknown;
  readonly kind?: unknown;
  readonly method?: unknown;
  readonly ok?: unknown;
  readonly payload?: unknown;
}

interface Transcript {
  readonly request: Readonly<Record<string, unknown>>;
  readonly result: unknown;
}

const readFrames = (stem: string): Frame[] =>
  readFileSync(path.join(FIXTURES_DIR, `${stem}${JSONL_SUFFIX}`), "utf8")
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line) as Frame);

const readTranscript = (stem: string): Transcript | undefined => {
  const frames = readFrames(stem);
  const request = frames.find((frame) => frame.kind === "request");
  const response = frames.find(
    (frame) => frame.kind === "response" && frame.ok === true
  );
  if (request === undefined || response === undefined) {
    return undefined;
  }
  return {
    request: (request.payload ?? {}) as Record<string, unknown>,
    result: response.payload,
  };
};

const fixtureResult = (stem: string): unknown => {
  const transcript = readTranscript(stem);
  if (transcript === undefined) {
    throw new Error(`${stem}${JSONL_SUFFIX} não tem resposta ok`);
  }
  return transcript.result;
};

const WORLD_ANALYSIS_ID: string = analysisStatusReportSchema.parse(
  fixtureResult("analysis.status.completed")
).analysisId;

export const PACKAGE_SLICE: GraphSlice = graphSliceSchema.parse(
  fixtureResult("graph.slice.package")
);

export const TYPE_SLICE: GraphSlice = graphSliceSchema.parse(
  fixtureResult("graph.slice.type")
);

export const packageNodeOf = (id: string): GraphNode => {
  const node = PACKAGE_SLICE.nodes.find((candidate) => candidate.id === id);
  if (node === undefined) {
    throw new Error(`${id} não é um nó de graph.slice.package`);
  }
  return node;
};

const drilledPackageId = (): string => {
  if (TYPE_SLICE.parentId === null) {
    throw new Error("graph.slice.type não tem parentId");
  }
  return TYPE_SLICE.parentId;
};

export const DRILLED_PACKAGE_ID: string = drilledPackageId();

export const packageIdByLabel = (label: string): string => {
  const matches = PACKAGE_SLICE.nodes.filter((node) => node.label === label);
  if (matches.length !== 1) {
    throw new Error(
      `${matches.length} nós de nível 1 têm o rótulo ${JSON.stringify(label)}`
    );
  }
  return matches[0]?.id ?? "";
};

export const PACKAGE_IDS: readonly string[] = PACKAGE_SLICE.nodes.map(
  (node) => node.id
);

export const TYPE_IDS: readonly string[] = TYPE_SLICE.nodes.map(
  (node) => node.id
);

const SLICE_STEM = /^graph\.slice\./;
const OUTLINE_STEM = /^outline\.children\./;
const DESCRIBE_STEM = /^element\.describe\./;
const DEPENDENCIES_STEM = /^element\.dependencies\./;
const EVIDENCE_STEM = /^element\.dependencyEvidence\./;
const ELEMENT_STEM = /^element\./;

const stemsOf = (topic: RegExp): string[] =>
  readdirSync(FIXTURES_DIR)
    .filter((name) => name.endsWith(JSONL_SUFFIX))
    .map((name) => name.slice(0, -JSONL_SUFFIX.length))
    .filter((stem) => topic.test(stem))
    .sort();

const worldTranscripts = (topic: RegExp): Transcript[] =>
  stemsOf(topic).flatMap((stem) => {
    const transcript = readTranscript(stem);
    return transcript?.request.analysisId === WORLD_ANALYSIS_ID
      ? [transcript]
      : [];
  });

const byRequest = <K, T>(
  transcripts: readonly Transcript[],
  keyOf: (request: Transcript["request"]) => K,
  parse: (result: unknown) => T
): ReadonlyMap<K, T> => {
  const map = new Map<K, T>();
  for (const { request, result } of transcripts) {
    const key = keyOf(request);
    if (map.has(key)) {
      throw new Error(
        `duas fixtures do mundo respondem ${JSON.stringify(key)}`
      );
    }
    map.set(key, parse(result));
  }
  return map;
};

const byParent = <T>(
  transcripts: readonly Transcript[],
  parse: (result: unknown) => T
): ReadonlyMap<string | null, T> =>
  byRequest(
    transcripts,
    (request) => (request.parentId ?? null) as string | null,
    parse
  );

const TYPE_SLICES = byParent(
  worldTranscripts(SLICE_STEM).filter(
    ({ request }) => request.level === "TYPE"
  ),
  (result) => graphSliceSchema.parse(result)
);

const OUTLINE_PAGES = byParent(worldTranscripts(OUTLINE_STEM), (result) =>
  outlinePageSchema.parse(result)
);

export const typeSliceOf = (packageId: string): GraphSlice => {
  const slice = TYPE_SLICES.get(packageId);
  if (slice === undefined) {
    throw new Error(`nenhuma fixture de graph.slice abre ${packageId}`);
  }
  return slice;
};

export const outlinePageOf = (parentId: string | null): OutlinePage => {
  const page = OUTLINE_PAGES.get(parentId);
  if (page === undefined) {
    throw new Error(`nenhuma fixture de outline.children responde ${parentId}`);
  }
  return page;
};

export const ROOT_ITEMS: readonly OutlineNode[] = outlinePageOf(null).items;

export const DRILLED_CHILDREN: readonly OutlineNode[] =
  outlinePageOf(DRILLED_PACKAGE_ID).items;

const searchFixture = (): {
  readonly hits: readonly SearchHit[];
  readonly query: string;
} => {
  const transcript = readTranscript("graph.search.hit");
  if (transcript === undefined) {
    throw new Error("graph.search.hit.jsonl não tem resposta ok");
  }
  return {
    hits: searchResultSchema.parse(transcript.result).results,
    query: String(transcript.request.query),
  };
};

const SEARCH = searchFixture();

export const SEARCH_QUERY: string = SEARCH.query;

export const SEARCH_HITS: readonly SearchHit[] = SEARCH.hits;

export const labelOf = (id: string): string => {
  const nodes = [PACKAGE_SLICE, ...TYPE_SLICES.values()].flatMap(
    (slice) => slice.nodes
  );
  const node = nodes.find((candidate) => candidate.id === id);
  if (node === undefined) {
    throw new Error(`${id} não é nó de nenhuma fatia do mundo`);
  }
  return node.label;
};

export interface UnknownParent {
  readonly error: ProtocolError;
  readonly parentId: string;
}

export interface Refusal {
  readonly error: ProtocolError;
  readonly method: string;
  readonly request: Readonly<Record<string, unknown>>;

  readonly stem: string;
}

const readRefusal = (stem: string): Refusal | undefined => {
  const frames = readFrames(stem);
  const request = frames.find((frame) => frame.kind === "request");
  const response = frames.find(
    (frame) => frame.kind === "response" && frame.ok === false
  );
  if (request === undefined || response === undefined) {
    return undefined;
  }
  return {
    error: protocolErrorSchema.parse(response.error),
    method: String(request.method),
    request: (request.payload ?? {}) as Record<string, unknown>,
    stem,
  };
};

const unknownParentFixture = (): UnknownParent => {
  const stem = "outline.children.unknown-parent";
  const refusal = readRefusal(stem);
  const parentId = refusal?.request.parentId;
  if (refusal === undefined || typeof parentId !== "string") {
    throw new Error(
      `${stem}${JSONL_SUFFIX} não é uma página recusada da estrutura`
    );
  }
  return { error: refusal.error, parentId };
};

export const UNKNOWN_PARENT: UnknownParent = unknownParentFixture();

const DETAILS = byRequest(
  worldTranscripts(DESCRIBE_STEM),
  (request) => String(request.elementId),
  (result) => elementDetailSchema.parse(result)
);

const pageKey = (elementId: unknown, direction: unknown): string =>
  JSON.stringify([elementId, direction]);

const PAGES = byRequest(
  worldTranscripts(DEPENDENCIES_STEM),
  (request) => pageKey(request.elementId, request.direction),
  (result) => dependencyPageSchema.parse(result)
);

export interface EvidencePair {
  readonly source: string;
  readonly target: string;
}

const pairKey = (source: unknown, target: unknown): string =>
  JSON.stringify([source, target]);

const EVIDENCE_TRANSCRIPTS = worldTranscripts(EVIDENCE_STEM);

const EVIDENCE = byRequest(
  EVIDENCE_TRANSCRIPTS,
  (request) => pairKey(request.source, request.target),
  (result) => dependencyEvidenceResultSchema.parse(result).evidence
);

export const DESCRIBED_IDS: readonly string[] = [...DETAILS.keys()];

export const detailOf = (elementId: string): ElementDetail => {
  const detail = DETAILS.get(elementId);
  if (detail === undefined) {
    throw new Error(
      `nenhuma fixture de element.describe descreve ${elementId}`
    );
  }
  return detail;
};

export const dependencyPageOf = (
  elementId: string,
  direction: DependencyDirection
): DependencyPage => {
  const page = PAGES.get(pageKey(elementId, direction));
  if (page === undefined) {
    throw new Error(
      `nenhuma fixture de element.dependencies lista ${direction} de ${elementId}`
    );
  }
  return page;
};

export const EVIDENCE_PAIRS: readonly EvidencePair[] = EVIDENCE_TRANSCRIPTS.map(
  ({ request }) => ({
    source: String(request.source),
    target: String(request.target),
  })
);

export const evidenceOf = (
  source: string,
  target: string
): readonly RelationshipEvidence[] => {
  const evidence = EVIDENCE.get(pairKey(source, target));
  if (evidence === undefined) {
    throw new Error(
      `nenhuma fixture de element.dependencyEvidence responde ${source} -> ${target}`
    );
  }
  return evidence;
};

export const ELEMENT_REFUSALS: readonly Refusal[] = stemsOf(
  ELEMENT_STEM
).flatMap((stem) => {
  const refusal = readRefusal(stem);
  return refusal?.request.analysisId === WORLD_ANALYSIS_ID ? [refusal] : [];
});
