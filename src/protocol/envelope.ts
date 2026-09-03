import { z } from "zod";
import { protocolErrorSchema } from "./errors";
import {
  analysisIdSchema,
  filePathSchema,
  opaquePayloadSchema,
  requestIdSchema,
} from "./primitives";

export const PROTOCOL_VERSION = 1;
export const protocolVersionSchema = z.literal(PROTOCOL_VERSION);

export const protocolMethodSchema = z.enum([
  "system.ping",
  "system.shutdown",
  "repository.describe",
  "analysis.start",
  "analysis.cancel",
  "analysis.status",
  "graph.slice",
  "outline.children",
  "graph.search",
  "element.describe",
  "element.dependencies",
  "element.dependencyEvidence",
  "evolution.cochange",
  "evolution.changeMetrics",
  "archaeology.timeline",
  "hotspots.list",
  "hotspots.ids",
  "hotspot.profile",
  "tour",
  "diagnostics.report",
]);
export type ProtocolMethod = z.infer<typeof protocolMethodSchema>;

export const protocolEventSchema = z.enum([
  "protocol.ready",
  "analysis.started",
  "analysis.progress",
  "analysis.stage.completed",
  "analysis.warning",
  "analysis.completed",
  "analysis.failed",
  "analysis.cancelled",
  "cache.invalidated",
]);
export type ProtocolEventName = z.infer<typeof protocolEventSchema>;

export const requestEnvelopeSchema = z.strictObject({
  id: requestIdSchema,
  kind: z.literal("request"),
  method: protocolMethodSchema,
  payload: opaquePayloadSchema,
  protocol: protocolVersionSchema,
  repositoryPath: filePathSchema.optional(),
});
export type RequestEnvelope = z.infer<typeof requestEnvelopeSchema>;

export const okResponseEnvelopeSchema = z.strictObject({
  id: requestIdSchema,
  kind: z.literal("response"),
  ok: z.literal(true),
  payload: opaquePayloadSchema,
  protocol: protocolVersionSchema,
});

export const errorResponseEnvelopeSchema = z.strictObject({
  error: protocolErrorSchema,
  id: requestIdSchema,
  kind: z.literal("response"),
  ok: z.literal(false),
  protocol: protocolVersionSchema,
});

export const responseEnvelopeSchema = z.discriminatedUnion("ok", [
  okResponseEnvelopeSchema,
  errorResponseEnvelopeSchema,
]);
export type ResponseEnvelope = z.infer<typeof responseEnvelopeSchema>;

export const eventEnvelopeSchema = z.strictObject({
  event: protocolEventSchema,
  kind: z.literal("event"),
  payload: opaquePayloadSchema,
  protocol: protocolVersionSchema,
});
export type EventEnvelope = z.infer<typeof eventEnvelopeSchema>;

export const protocolMessageSchema = z.discriminatedUnion("kind", [
  requestEnvelopeSchema,
  responseEnvelopeSchema,
  eventEnvelopeSchema,
]);
export type ProtocolMessage = z.infer<typeof protocolMessageSchema>;

export const RUST_READ_KEYS = [
  "protocol",
  "kind",
  "id",
  "method",
  "event",
  "ok",
] as const;

export const analysisIdPayloadSchema = z.strictObject({
  analysisId: analysisIdSchema,
});
