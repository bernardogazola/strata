import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { type ProtocolError, protocolErrorSchema } from "@/protocol/errors";
import {
  type GraphNode,
  type GraphSlice,
  graphSliceSchema,
  type OutlineNode,
  type OutlinePage,
  outlinePageSchema,
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
  readonly kind?: unknown;
  readonly ok?: unknown;
  readonly payload?: unknown;
}

interface Transcript {
  readonly request: Readonly<Record<string, unknown>>;
  readonly result: unknown;
}

const readTranscript = (stem: string): Transcript | undefined => {
  const frames = readFileSync(
    path.join(FIXTURES_DIR, `${stem}${JSONL_SUFFIX}`),
    "utf8"
  )
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line) as Frame);
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

const worldTranscripts = (topic: RegExp): Transcript[] =>
  readdirSync(FIXTURES_DIR)
    .filter((name) => name.endsWith(JSONL_SUFFIX))
    .map((name) => name.slice(0, -JSONL_SUFFIX.length))
    .filter((stem) => topic.test(stem))
    .sort()
    .flatMap((stem) => {
      const transcript = readTranscript(stem);
      return transcript?.request.analysisId === WORLD_ANALYSIS_ID
        ? [transcript]
        : [];
    });

const byParent = <T>(
  transcripts: readonly Transcript[],
  parse: (result: unknown) => T
): ReadonlyMap<string | null, T> => {
  const map = new Map<string | null, T>();
  for (const { request, result } of transcripts) {
    const parentId = (request.parentId ?? null) as string | null;
    if (map.has(parentId)) {
      throw new Error(`duas fixtures do mundo respondem parentId ${parentId}`);
    }
    map.set(parentId, parse(result));
  }
  return map;
};

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

const unknownParentFixture = (): UnknownParent => {
  const stem = "outline.children.unknown-parent";
  const frames = readFileSync(
    path.join(FIXTURES_DIR, `${stem}${JSONL_SUFFIX}`),
    "utf8"
  )
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line) as Frame & { readonly error?: unknown });
  const request = frames.find((frame) => frame.kind === "request");
  const response = frames.find(
    (frame) => frame.kind === "response" && frame.ok === false
  );
  const { parentId } = (request?.payload ?? {}) as { parentId?: unknown };
  if (typeof parentId !== "string" || response === undefined) {
    throw new Error(
      `${stem}${JSONL_SUFFIX} não é uma página recusada da estrutura`
    );
  }
  return { error: protocolErrorSchema.parse(response.error), parentId };
};

export const UNKNOWN_PARENT: UnknownParent = unknownParentFixture();
