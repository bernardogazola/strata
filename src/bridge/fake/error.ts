import type { ProtocolError } from "@/protocol/errors";

export class FakeBridgeError extends Error {
  readonly protocolError: ProtocolError;

  constructor(protocolError: ProtocolError) {
    super(protocolError.detail ?? protocolError.code);
    this.name = "FakeBridgeError";
    this.protocolError = protocolError;
  }
}
