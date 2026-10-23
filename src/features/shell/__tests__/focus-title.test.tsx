import { type HistoryState, useNavigate } from "@tanstack/react-router";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { useCallback, useState } from "react";
import { describe, expect, test } from "vitest";
import {
  HANDLE,
  renderRoutes,
} from "@/features/repository/__tests__/route-harness";
import { GESTURE_STATE, useFocusTitleAfterGesture } from "../focus-title";

const TITLE = "Título da tela";

function Leave({ state }: { readonly state: HistoryState | undefined }) {
  const navigate = useNavigate();
  const onLeave = useCallback(() => {
    navigate({
      params: { handle: HANDLE },
      state,
      to: "/repository/$handle",
    }).catch(() => undefined);
  }, [navigate, state]);
  return (
    <main>
      <button onClick={onLeave} type="button">
        sair
      </button>
    </main>
  );
}

function Arrival() {
  const [loaded, setLoaded] = useState(false);
  const onLoad = useCallback(() => setLoaded(true), []);
  useFocusTitleAfterGesture(loaded);
  return (
    <main>
      {loaded ? <h1 tabIndex={-1}>{TITLE}</h1> : null}
      <button onClick={onLoad} type="button">
        carregar
      </button>
    </main>
  );
}

const renderFlow = (state: HistoryState | undefined) =>
  renderRoutes("/", {
    index: () => <Leave state={state} />,
    repository: () => <Arrival />,
  });

const leave = async (): Promise<HTMLElement> => {
  const button = await screen.findByRole("button", { name: "sair" });
  button.focus();
  fireEvent.click(button);
  return screen.findByRole("button", { name: "carregar" });
};

describe("useFocusTitleAfterGesture", () => {
  test("com a marca, o título recebe o foco quando passa a existir", async () => {
    renderFlow(GESTURE_STATE);
    const load = await leave();
    expect(document.activeElement).toBe(document.body);

    fireEvent.click(load);

    const title = await screen.findByRole("heading", { name: TITLE });
    await waitFor(() => expect(document.activeElement).toBe(title));
  });

  test("sem a marca, o título que chega não move o foco", async () => {
    renderFlow(undefined);
    const load = await leave();

    fireEvent.click(load);

    await screen.findByRole("heading", { name: TITLE });
    expect(document.activeElement).toBe(document.body);
  });

  test("com a marca, o foco que a pessoa já pôs em outro lugar fica onde está", async () => {
    renderFlow(GESTURE_STATE);
    const load = await leave();
    load.focus();

    fireEvent.click(load);

    await screen.findByRole("heading", { name: TITLE });
    expect(document.activeElement).toBe(load);
  });

  test("a marca de outro documento, que uma recarga mantém, não move o foco", async () => {
    renderFlow({ focusTitle: GESTURE_STATE.focusTitle - 1 });
    const load = await leave();

    fireEvent.click(load);

    await screen.findByRole("heading", { name: TITLE });
    expect(document.activeElement).toBe(document.body);
  });

  test("um link direto não tem marca: a montagem não move o foco", async () => {
    renderRoutes(`/repository/${HANDLE}`, {
      index: () => <p>index</p>,
      repository: () => <Arrival />,
    });

    fireEvent.click(await screen.findByRole("button", { name: "carregar" }));

    await screen.findByRole("heading", { name: TITLE });
    expect(document.activeElement).toBe(document.body);
  });
});
