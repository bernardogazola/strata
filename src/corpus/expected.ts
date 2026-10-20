import { z } from "zod";
import {
  buildSystemSchema,
  dependencyTypeSchema,
  resolutionQualitySchema,
  sourceRootKindSchema,
  typeKindSchema,
} from "../protocol/enums";
import { elementIdSchema } from "../protocol/primitives";

const count = z.number().int().nonnegative();

const positiveCount = z.number().int().positive();

const relativePath = z
  .string()
  .min(1)
  .max(1024)
  .regex(/^[^/\\][^\\]*$/, "root-relative POSIX path");

const sourceRootSchema = z.strictObject({
  javaFileCount: positiveCount,
  kind: sourceRootKindSchema,
  path: relativePath,
});

const completenessSchema = z.strictObject({
  parseFailures: count,
  totalFiles: positiveCount,
});

const typeRowSchema = z.strictObject({
  generated: z.literal(true).optional(),
  id: elementIdSchema,
  kind: typeKindSchema,
  root: relativePath.optional(),
  test: z.literal(true).optional(),
});

const dependencyRowSchema = z.strictObject({
  ambiguous: z.literal(true).optional(),
  external: z.literal(true).optional(),
  from: elementIdSchema,
  occurrences: positiveCount,
  resolution: resolutionQualitySchema,
  to: elementIdSchema,
  type: dependencyTypeSchema,
});

const edgeRowSchema = z.strictObject({
  distinctTypeCount: positiveCount,
  from: elementIdSchema,
  occurrenceTotal: positiveCount,
  quality: resolutionQualitySchema,
  to: elementIdSchema,
  types: z.partialRecord(dependencyTypeSchema, positiveCount),
  weight: positiveCount,
});

const evidenceRowSchema = z.strictObject({
  anchor: z.string().min(1).max(200),
  file: relativePath,
  from: elementIdSchema,
  fromMethod: elementIdSchema.optional(),
  line: positiveCount,
  to: elementIdSchema,
  toMethod: elementIdSchema.optional(),
  type: dependencyTypeSchema,
});

const cyclesSchema = z.strictObject({
  internalCohesion: z.partialRecord(z.string(), count),
  notInAnyCycle: z.array(elementIdSchema),
  packages: z.array(z.array(elementIdSchema).min(2)),
  types: z.array(z.array(elementIdSchema).min(2)),
});

const lineageRowSchema = z.strictObject({
  changes: positiveCount,
  first: relativePath,
  head: relativePath.nullable(),
  renames: count,
});

const coChangeRowSchema = z.strictObject({
  changesLeft: positiveCount,
  changesRight: positiveCount,
  left: relativePath,
  right: relativePath,
  support: positiveCount,
});

const bulkSchema = z.strictObject({
  bulkCommitCount: count,
  bulkFileTotal: count,
  bulkThreshold: positiveCount,
  excludedMergeCommits: count,
  forcedBulkCommits: count,
  iqr: count,
  mad: count,
  median: positiveCount,
  q1: positiveCount,
  q3: positiveCount,
});

const historySchema = z.strictObject({
  authors: positiveCount,
  authorsWithoutMailmap: positiveCount.optional(),
  bulk: bulkSchema.optional(),
  cochange: z.array(coChangeRowSchema).min(1).optional(),
  commits: positiveCount,
  lineages: z.array(lineageRowSchema).min(1).optional(),
  merges: count,
  qualifying: positiveCount,
  qualifyingCoChange: count,
});

export const CLAIMABLE_SECTIONS = [
  "buildSystem",
  "completeness",
  "cycles",
  "declaredModules",
  "dependencies",
  "edges",
  "evidence",
  "files",
  "history",
  "methods",
  "packages",
  "parseFailures",
  "sourceRoots",
  "types",
] as const;

const claimSchema = z.enum(CLAIMABLE_SECTIONS);

export const expectedSchema = z.strictObject({
  buildSystem: buildSystemSchema.optional(),
  claims: z.array(claimSchema).min(1),
  completeness: completenessSchema.optional(),
  cycles: cyclesSchema.optional(),
  declaredModules: z.array(z.string().min(1)).min(1).optional(),
  dependencies: z.array(dependencyRowSchema).min(1).optional(),
  edges: z.array(edgeRowSchema).min(1).optional(),
  evidence: z.array(evidenceRowSchema).min(1).optional(),
  files: z.array(elementIdSchema).min(1).optional(),
  fixture: z.string().regex(/^[a-z][a-z0-9-]*$/),
  history: historySchema.optional(),
  methods: z.array(elementIdSchema).min(1).optional(),
  packages: z.array(elementIdSchema).min(1).optional(),
  parseFailures: z.array(elementIdSchema).min(1).optional(),
  sourceRoots: z.array(sourceRootSchema).min(1).optional(),
  types: z.array(typeRowSchema).min(1).optional(),
  version: z.literal(1),
});

export type Expected = z.infer<typeof expectedSchema>;

export const CANONICAL_KEY_ORDER = [
  "version",
  "fixture",
  "claims",
  "buildSystem",
  "sourceRoots",
  "completeness",
  "packages",
  "types",
  "methods",
  "files",
  "dependencies",
  "edges",
  "evidence",
  "cycles",
  "parseFailures",
  "declaredModules",
  "history",
] as const satisfies readonly (keyof Expected)[];

export const DEPENDENCY_TYPE_ORDER = dependencyTypeSchema.options;

export const DEPENDENCY_TYPE_WEIGHT = {
  ANNOTATION_REFERENCE: 1,
  CALLS: 2,
  EXTENDS: 4,
  FIELD_TYPE: 3,
  IMPLEMENTS: 4,
  IMPORTS: 1,
  MODULE_DEPENDENCY: 1,
  PARAMETER_TYPE: 1,
  RETURN_TYPE: 2,
} as const satisfies Record<(typeof DEPENDENCY_TYPE_ORDER)[number], number>;

export const COCHANGE_MIN_JACCARD = 0.15;
export const COCHANGE_MIN_CONFIDENCE = 0.3;
export const MIN_SUPPORT_FLOOR = 3;
export const MIN_SUPPORT_CAP = 10;
export const MIN_SUPPORT_FRACTION = 0.01;

export const minSupport = (qualifyingCoChange: number): number =>
  Math.min(
    MIN_SUPPORT_CAP,
    Math.max(
      MIN_SUPPORT_FLOOR,
      Math.ceil(MIN_SUPPORT_FRACTION * qualifyingCoChange)
    )
  );

export const RESOLUTION_STRENGTH = {
  INFERRED: 1,
  RESOLVED: 2,
  UNRESOLVED_EXTERNAL: 0,
} as const;

const INLINE_WIDTH = 120;

const orderedEntries = (
  value: Record<string, unknown>
): [string, unknown][] => {
  const keys = Object.keys(value);
  const known = CANONICAL_KEY_ORDER.filter((key) => keys.includes(key));
  if (known.length === keys.length && known.length > 0) {
    return known.map((key) => [key, value[key]]);
  }
  return keys.map((key) => [key, value[key]]);
};

const isScalar = (value: unknown): boolean =>
  value === null || typeof value !== "object";

const renderScalar = (value: unknown): string =>
  JSON.stringify(value) ?? "null";

const renderInline = (value: unknown): string => {
  if (isScalar(value)) {
    return renderScalar(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(renderInline).join(", ")}]`;
  }
  const entries = orderedEntries(value as Record<string, unknown>);
  return `{${entries
    .map(([key, nested]) => `${JSON.stringify(key)}: ${renderInline(nested)}`)
    .join(", ")}}`;
};

const renderValue = (
  value: unknown,
  indent: number,
  prefix: number
): string => {
  if (isScalar(value)) {
    return renderScalar(value);
  }
  const pad = " ".repeat(indent);
  const inner = " ".repeat(indent + 2);
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return "[]";
    }
    if (value.every(isScalar)) {
      const inline = renderInline(value);
      if (prefix + inline.length <= INLINE_WIDTH) {
        return inline;
      }
    }
    const rows = value.map((item) =>
      isScalar(item) ? renderScalar(item) : renderInline(item)
    );
    return `[\n${rows.map((row) => inner + row).join(",\n")}\n${pad}]`;
  }
  const entries = orderedEntries(value as Record<string, unknown>);
  if (entries.length === 0) {
    return "{}";
  }
  const rows = entries.map(([key, nested]) => {
    const head = `${JSON.stringify(key)}: `;
    return (
      inner + head + renderValue(nested, indent + 2, indent + 2 + head.length)
    );
  });
  return `{\n${rows.join(",\n")}\n${pad}}`;
};

export const renderExpected = (value: unknown): string =>
  `${renderValue(value, 0, 0)}\n`;
