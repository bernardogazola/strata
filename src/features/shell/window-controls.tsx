import {
  type MouseEvent,
  type ReactNode,
  useEffect,
  useState,
  useSyncExternalStore,
} from "react";
import {
  closeWindow,
  minimizeWindow,
  toggleMaximizeWindow,
  type WindowFrame,
  watchWindowFrame,
} from "@/bridge/window";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";

const RESTING_FRAME: WindowFrame = { fullscreen: false, maximized: false };

const sameFrame = (left: WindowFrame, right: WindowFrame): boolean =>
  left.fullscreen === right.fullscreen && left.maximized === right.maximized;

export function useWindowFrame(): WindowFrame {
  const [frame, setFrame] = useState(RESTING_FRAME);

  useEffect(
    () =>
      watchWindowFrame((next) => {
        setFrame((previous) => (sameFrame(previous, next) ? previous : next));
      }),
    []
  );

  return frame;
}

const subscribeToWindowFocus = (onChange: () => void): (() => void) => {
  window.addEventListener("focus", onChange);
  window.addEventListener("blur", onChange);
  return () => {
    window.removeEventListener("focus", onChange);
    window.removeEventListener("blur", onChange);
  };
};

const hasWindowFocus = (): boolean => document.hasFocus();

export const useWindowFocused = (): boolean =>
  useSyncExternalStore(subscribeToWindowFocus, hasWindowFocus);

export type DrawnControlsPlatform = "linux" | "windows";

type CaptionAction = keyof typeof t.header.window;

const GLYPHS = {
  linux: {
    close: <path d="M5 5L11 11M11 5L5 11" />,
    maximize: <rect height="6.5" rx="1" width="6.5" x="4.75" y="4.75" />,
    minimize: <path d="M4.5 11H11.5" />,
    restore: (
      <>
        <path d="M6.25 6.25V4.75C6.25 4.2 6.7 3.75 7.25 3.75H11.25C11.8 3.75 12.25 4.2 12.25 4.75V8.75C12.25 9.3 11.8 9.75 11.25 9.75H9.75" />
        <rect height="6" rx="1" width="6" x="3.75" y="6.25" />
      </>
    ),
  },
  windows: {
    close: <path d="M0.5 0.5L9.5 9.5M9.5 0.5L0.5 9.5" />,
    maximize: <rect height="9" rx="1" width="9" x="0.5" y="0.5" />,
    minimize: <path d="M0 5.5H10" />,
    restore: (
      <>
        <path d="M2.5 2.5V1.5C2.5 0.95 2.95 0.5 3.5 0.5H8.5C9.05 0.5 9.5 0.95 9.5 1.5V6.5C9.5 7.05 9.05 7.5 8.5 7.5H7.5" />
        <rect height="7" rx="1" width="7" x="0.5" y="2.5" />
      </>
    ),
  },
} as const satisfies Record<
  DrawnControlsPlatform,
  Record<CaptionAction, ReactNode>
>;

const GLYPH_BOX = {
  linux: { size: 16, strokeWidth: 1.5 },
  windows: { size: 10, strokeWidth: 1 },
} as const satisfies Record<
  DrawnControlsPlatform,
  { readonly size: number; readonly strokeWidth: number }
>;

const CONTROL_STYLES = {
  linux: {
    button:
      "relative flex size-6 items-center justify-center rounded-full bg-foreground/7 text-foreground transition-colors duration-100 after:absolute after:-inset-x-[5px] after:-inset-y-3 hover:bg-foreground/14 active:bg-foreground/20 group-data-inactive/titlebar:text-muted-foreground",
    close: "",
    group: "flex shrink-0 items-center gap-2.5 pl-3",
  },
  windows: {
    button:
      "flex w-[46px] items-center justify-center text-foreground transition-colors duration-100 hover:bg-foreground/6 active:bg-foreground/4 group-data-inactive/titlebar:text-muted-foreground",
    close:
      "hover:bg-window-close hover:text-white active:bg-window-close/90 group-data-inactive/titlebar:hover:text-white",
    group: "flex shrink-0 items-stretch self-stretch pl-3",
  },
} as const satisfies Record<
  DrawnControlsPlatform,
  {
    readonly button: string;
    readonly close: string;
    readonly group: string;
  }
>;

const keepFocusWhereItIs = (event: MouseEvent<HTMLButtonElement>): void => {
  event.preventDefault();
};

interface CaptionButtonProps {
  readonly action: CaptionAction;
  readonly onClick: () => void;
  readonly platform: DrawnControlsPlatform;
}

function CaptionButton({ action, onClick, platform }: CaptionButtonProps) {
  const label = t.header.window[action];
  const style = CONTROL_STYLES[platform];
  const box = GLYPH_BOX[platform];
  return (
    <button
      aria-label={label}
      className={cn(style.button, action === "close" && style.close)}
      onClick={onClick}
      onMouseDown={keepFocusWhereItIs}
      tabIndex={-1}
      title={label}
      type="button"
    >
      <svg
        aria-hidden="true"
        fill="none"
        height={box.size}
        stroke="currentColor"
        strokeLinecap={platform === "linux" ? "round" : "butt"}
        strokeWidth={box.strokeWidth}
        viewBox={`0 0 ${box.size} ${box.size}`}
        width={box.size}
      >
        {GLYPHS[platform][action]}
      </svg>
    </button>
  );
}

export interface WindowControlsProps {
  readonly maximized: boolean;
  readonly platform: DrawnControlsPlatform;
}

export function WindowControls({ maximized, platform }: WindowControlsProps) {
  return (
    <div className={CONTROL_STYLES[platform].group}>
      <CaptionButton
        action="minimize"
        onClick={minimizeWindow}
        platform={platform}
      />
      <CaptionButton
        action={maximized ? "restore" : "maximize"}
        onClick={toggleMaximizeWindow}
        platform={platform}
      />
      <CaptionButton action="close" onClick={closeWindow} platform={platform} />
    </div>
  );
}
