import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import { RepositoryScreen } from "@/features/shell/repository-screen";
import {
  REPOSITORY_SEARCH_DEFAULTS,
  repositorySearchSchema,
} from "@/features/shell/repository-search";

export const Route = createFileRoute("/repository/$handle")({
  component: RepositoryScreen,
  search: { middlewares: [stripSearchParams(REPOSITORY_SEARCH_DEFAULTS)] },
  validateSearch: repositorySearchSchema,
});
