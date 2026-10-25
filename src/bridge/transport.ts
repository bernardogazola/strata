import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type { ProtocolMethod } from "@/protocol/envelope";
import { PROTOCOL_VERSION } from "@/protocol/envelope";
import type { BackendState } from "@/state/app-store";
import {
  ANALYZER_STATE,
  type DiagnosticsOptions,
  FORWARDED_CHANNELS,
} from "./contracts";
import { isTauri } from "./environment";

export type RawFrame =
  | { readonly kind: "state"; readonly value: unknown }
  | { readonly kind: "forwarded"; readonly value: unknown };

export type FrameListener = (frame: RawFrame) => void;

export interface BridgeTransport {
  readonly analyzerCancel: (analysisId: string) => Promise<unknown>;
  readonly analyzerRestart: () => Promise<unknown>;
  readonly analyzerState: () => Promise<unknown>;
  readonly analyzerStderrTail: () => Promise<unknown>;
  readonly closeRepository: (handle: string) => Promise<void>;
  readonly exportDiagnostics: (options: DiagnosticsOptions) => Promise<unknown>;
  readonly openRepository: () => Promise<unknown>;
  readonly request: (
    method: ProtocolMethod,
    payload: unknown,
    handle?: string
  ) => Promise<unknown>;
  readonly subscribe: (listener: FrameListener) => Promise<() => void>;
}

const tauriTransport: BridgeTransport = {
  analyzerCancel: (analysisId) => invoke("analyzer_cancel", { analysisId }),
  analyzerRestart: () => invoke("analyzer_restart"),
  analyzerState: () => invoke("analyzer_state"),
  analyzerStderrTail: () => invoke("analyzer_stderr_tail"),
  closeRepository: (handle) => invoke("close_repository", { handle }),
  exportDiagnostics: (options) => invoke("export_diagnostics", { ...options }),
  openRepository: () => invoke("open_repository"),
  request: (method, payload, handle) =>
    invoke("analyzer_request", { handle, method, payload }),
  subscribe: async (listener) => {
    const unlisteners = await Promise.all([
      listen(ANALYZER_STATE, (event) =>
        listener({ kind: "state", value: event.payload })
      ),
      ...FORWARDED_CHANNELS.map((channel) =>
        listen(channel, (event) =>
          listener({ kind: "forwarded", value: event.payload })
        )
      ),
    ]);
    return () => {
      for (const unlisten of unlisteners) {
        unlisten();
      }
    };
  },
};

const BROWSER_HANDLE = "repo_AAAAAAAAAAAAAAAAAAAAAQ";
const BROWSER_READY: BackendState = {
  phase: "READY",
  protocolVersion: PROTOCOL_VERSION,
};

const loadFakeBridge = () => import("./fake");

const browserTransport: BridgeTransport = {
  analyzerCancel: () => Promise.resolve({ cancelled: true }),
  analyzerRestart: () => Promise.resolve(BROWSER_READY),
  analyzerState: () => Promise.resolve(BROWSER_READY),
  analyzerStderrTail: () => Promise.resolve({ lines: [], truncated: false }),
  closeRepository: () => Promise.resolve(),
  exportDiagnostics: () => Promise.resolve({ written: false }),
  openRepository: () =>
    Promise.resolve({
      displayName: `orders${String.fromCodePoint(0x20_2e)}ecivres`,
      handle: BROWSER_HANDLE,
    }),
  request: async (method, payload) => {
    const { fakeRequest } = await loadFakeBridge();
    return fakeRequest(method, payload);
  },
  subscribe: async (listener) => {
    const { fakeSubscribe } = await loadFakeBridge();
    return fakeSubscribe((event) =>
      listener({
        kind: "forwarded",
        value: { event: event.event, payload: event.payload },
      })
    );
  },
};

export const transport: BridgeTransport = isTauri()
  ? tauriTransport
  : browserTransport;
