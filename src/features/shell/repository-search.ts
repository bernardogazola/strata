import { z } from "zod";
import { graphLevelSchema } from "@/protocol/enums";
import { analysisIdSchema, elementIdSchema } from "@/protocol/primitives";
import type { GraphViewParams } from "@/queries/options";

export const repositorySearchSchema = z.object({
  analysis: analysisIdSchema.optional().catch(undefined),

  focus: elementIdSchema.optional().catch(undefined),

  level: graphLevelSchema.default("PACKAGE").catch("PACKAGE"),

  parent: elementIdSchema.optional().catch(undefined),
});

export type RepositorySearch = z.infer<typeof repositorySearchSchema>;

export const resolveGraphView = ({
  level,
  parent,
}: RepositorySearch): GraphViewParams => {
  if (level !== "TYPE" || parent === undefined) {
    return { level: "PACKAGE" };
  }
  return { level, parentId: parent };
};
