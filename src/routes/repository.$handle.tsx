import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import {
  RepositoryScreen,
  UnknownRepositoryScreen,
} from "@/features/shell/repository-screen";
import {
  parseRepositoryParams,
  REPOSITORY_SEARCH_DEFAULTS,
  repositorySearchSchema,
} from "@/features/shell/repository-search";

export const Route = createFileRoute("/repository/$handle")({
  component: RepositoryScreen,
  notFoundComponent: UnknownRepositoryScreen,
  params: { parse: parseRepositoryParams },
  search: { middlewares: [stripSearchParams(REPOSITORY_SEARCH_DEFAULTS)] },
  validateSearch: repositorySearchSchema,
});
