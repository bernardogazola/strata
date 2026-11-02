import {
  type DependencyEntry,
  type DependencyPage,
  dependencyEntrySchema,
  dependencyPageSchema,
} from "@/protocol/graph";

export const SYNTHETIC_PACKAGE_ID = "pkg:br.pucpr.synthetic";

export const CURSORS: readonly string[] = [
  "Y3Vyc29yLTE",
  "Y3Vyc29yLTI",
  "Y3Vyc29yLTM",
];

export const NEXT_CURSOR: string = CURSORS[0] ?? "";

const edgeIdOf = (n: number): string => `e_${n.toString(16).padStart(16, "0")}`;

type EntryPatch = Partial<DependencyEntry>;

export const internalTypeRow = (
  n: number,
  patch: EntryPatch = {}
): DependencyEntry =>
  dependencyEntrySchema.parse({
    ambiguous: false,
    edgeId: edgeIdOf(n),
    elementId: `type:br.pucpr.synthetic.T${n}`,
    isExternal: false,
    kind: "TYPE",
    label: `T${n}`,
    packageId: SYNTHETIC_PACKAGE_ID,
    qualifiedName: `br.pucpr.synthetic.T${n}`,
    resolution: "RESOLVED",
    types: { IMPORTS: 1 },
    weight: 1,
    ...patch,
  });

export const externalTypeRow = (
  n: number,
  patch: EntryPatch = {}
): DependencyEntry =>
  dependencyEntrySchema.parse({
    ambiguous: false,
    edgeId: edgeIdOf(n),
    elementId: `type:com.thirdparty.X${n}`,
    isExternal: true,
    kind: "TYPE",
    label: `com.thirdparty.X${n}`,
    packageId: null,
    qualifiedName: `com.thirdparty.X${n}`,
    resolution: "UNRESOLVED_EXTERNAL",
    types: { FIELD_TYPE: 1 },
    weight: 1,
    ...patch,
  });

export const pageOf = (
  items: readonly DependencyEntry[],
  totals: DependencyPage["totals"],
  nextCursor: string | null = null
): DependencyPage => dependencyPageSchema.parse({ items, nextCursor, totals });
