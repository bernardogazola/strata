package br.pucpr.tcc.strata.analyzer.protocol;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.UntrustedText;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
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

  public static ProtocolError repository(RepositoryIssue issue) {
    final String detail =
        switch (issue.code()) {
          case REPO_PATH_CHANGED -> "o caminho do repositório não leva mais à pasta que foi aberta";
          case REPO_ROOT_UNREADABLE -> "a pasta do repositório não pode ser listada";
          case REPO_NOT_A_GIT_REPOSITORY ->
              "a pasta não é a raiz de uma árvore de trabalho Git utilizável";
          case REPO_IS_BARE -> "o repositório é bare e não tem árvore de trabalho para analisar";
          case REPO_TOO_MANY_FILES ->
              "a árvore de trabalho passa do teto de entradas enumeradas; a varredura parou ali";
          case REPO_NO_JAVA_SOURCES ->
              "nenhuma raiz de código foi encontrada sob a raiz do repositório";
          case REPO_PATH_ESCAPES_ROOT ->
              "um link simbólico aponta para fora da raiz do repositório e foi ignorado";
          case REPO_UNREADABLE -> "uma entrada sob a raiz do repositório foi ignorada sem ser lida";
          case REPO_NO_HISTORY ->
              "o HEAD ainda não aponta para nenhum commit; a análise estrutural roda e os estágios de histórico são pulados";
        };
    return new ProtocolError(issue.code().name(), detail, ORIGIN_ANALYZER, issue.params());
  }

  private static String clamp(String value, int maxChars) {
    return UntrustedText.clamp(value, maxChars);
  }
}
