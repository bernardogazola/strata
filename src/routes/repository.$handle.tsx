import { createFileRoute } from "@tanstack/react-router";
import { RepositoryScreen } from "@/features/shell/repository-screen";
import { repositorySearchSchema } from "@/features/shell/repository-search";

export const Route = createFileRoute("/repository/$handle")({
  component: RepositoryScreen,
  validateSearch: repositorySearchSchema,
});
