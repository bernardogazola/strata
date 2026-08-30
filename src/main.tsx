import "@/global.css";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { z } from "zod";
import { revealWindow } from "@/bridge/window";
import { hasLocaleData } from "@/i18n/format";
import AppProvider from "./provider";
import { createAppRouter } from "./router";

z.config(z.locales.pt());

if (import.meta.env.DEV && !hasLocaleData()) {
  console.warn("Dados de locale pt-BR ausentes neste runtime.");
}

const { queryClient, router } = createAppRouter();

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Elemento raiz #root não encontrado");
}

createRoot(rootElement).render(
  <StrictMode>
    <AppProvider queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProvider>
  </StrictMode>
);

requestAnimationFrame(() => {
  requestAnimationFrame(() => {
    revealWindow();
  });
});
