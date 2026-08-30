import { createRouter, RouterProvider } from "@tanstack/react-router";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import AppProvider from "./provider";
import { routeTree } from "./routeTree.gen";

const router = createRouter({ routeTree });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Elemento raiz #root não encontrado");
}

createRoot(rootElement).render(
  <StrictMode>
    <AppProvider>
      <RouterProvider router={router} />
    </AppProvider>
  </StrictMode>
);

requestAnimationFrame(() => {
  requestAnimationFrame(() => {
    try {
      const win = getCurrentWindow();
      win
        .show()
        .then(() => win.setFocus())
        .catch(() => undefined);
    } catch {}
  });
});
