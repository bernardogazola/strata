package br.pucpr.tcc.strata.analyzer.protocol;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.util.Map;

public final class ErrorCodes {

  public static final String PROTO_UNKNOWN_METHOD = "PROTO_UNKNOWN_METHOD";
  public static final String PROTO_INVALID_PAYLOAD = "PROTO_INVALID_PAYLOAD";
  public static final String PROTO_MESSAGE_TOO_LARGE = "PROTO_MESSAGE_TOO_LARGE";
  public static final String INTERNAL_UNEXPECTED = "INTERNAL_UNEXPECTED";

  public static final String ORIGIN_ANALYZER = "analyzer";

  private static final String NO_HANDLER = "nenhum handler está registrado para o método pedido";

  private ErrorCodes() {}

  public static ProtocolError unknownMethod(String method) {
    return new ProtocolError(
        PROTO_UNKNOWN_METHOD,
        NO_HANDLER,
        ORIGIN_ANALYZER,
        Map.of("method", clamp(method, AnalysisConstants.MAX_METHOD_NAME_CHARS)));
  }

  public static ProtocolError invalidPayload(String method, String detail) {
    return new ProtocolError(
        PROTO_INVALID_PAYLOAD,
        clamp(detail, AnalysisConstants.MAX_ERROR_DETAIL_CHARS),
        ORIGIN_ANALYZER,
        Map.of("method", clamp(method, AnalysisConstants.MAX_METHOD_NAME_CHARS)));
  }

  public static ProtocolError messageTooLarge(int limitBytes, long receivedBytes) {
    return new ProtocolError(
        PROTO_MESSAGE_TOO_LARGE,
        "a resposta passou do tamanho máximo de frame e não foi emitida",
        ORIGIN_ANALYZER,
        Map.of("limitBytes", limitBytes, "receivedBytes", receivedBytes));
  }

  public static ProtocolError internalUnexpected(String detail) {
    return new ProtocolError(
        INTERNAL_UNEXPECTED,
        clamp(detail, AnalysisConstants.MAX_ERROR_DETAIL_CHARS),
        ORIGIN_ANALYZER,
        Map.of());
  }

  private static String clamp(String value, int maxChars) {
    final StringBuilder clean = new StringBuilder(Math.min(value.length(), maxChars));
    value
        .codePoints()
        .filter(cp -> cp >= 0x20 || cp == '\t')
        .limit(maxChars)
        .forEach(clean::appendCodePoint);
    return clean.toString();
  }
}
