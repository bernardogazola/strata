import { createFileRoute } from "@tanstack/react-router";
import { ModeToggle } from "@/components/mode-toggle";
import { AnalyzerStatus } from "@/features/backend/analyzer-status";
import { RepositoryProbe } from "@/features/backend/repository-probe";

export const Route = createFileRoute("/")({
  component: Index,
});

function Index() {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-6">
      <AnalyzerStatus />
      <RepositoryProbe />
      <ModeToggle />
    </div>
  );
}
