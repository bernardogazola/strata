import { createFileRoute } from "@tanstack/react-router";
import { StartScreen } from "@/features/repository/start-screen";

export const Route = createFileRoute("/")({
  component: StartScreen,
});
