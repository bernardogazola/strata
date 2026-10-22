import { type HistoryState, useRouterState } from "@tanstack/react-router";
import { useEffect, useRef } from "react";

declare module "@tanstack/react-router" {
  interface HistoryState {
    readonly focusTitle?: number;
  }
}

export const GESTURE_STATE = {
  focusTitle: performance.timeOrigin,
} as const satisfies HistoryState;

const focusTitleFromBody = (): void => {
  if (document.activeElement === document.body) {
    document.querySelector<HTMLElement>("main h1")?.focus();
  }
};

export const useFocusTitleOnScreenChange = (
  screenKey: string | null,
  focusFirstScreen = false
): void => {
  const shownKey = useRef(screenKey);

  useEffect(() => {
    if (screenKey === null || shownKey.current === screenKey) {
      return;
    }
    const isFirstScreen = shownKey.current === null;
    shownKey.current = screenKey;
    if (isFirstScreen && !focusFirstScreen) {
      return;
    }
    focusTitleFromBody();
  }, [focusFirstScreen, screenKey]);
};

export const useFocusTitleAfterGesture = (titleShown: boolean): void => {
  const arrivedByGesture = useRouterState({
    select: (state) =>
      state.location.state.focusTitle === GESTURE_STATE.focusTitle,
  });

  useEffect(() => {
    if (arrivedByGesture && titleShown) {
      focusTitleFromBody();
    }
  }, [arrivedByGesture, titleShown]);
};
