package br.pucpr.tcc.strata.analyzer.protocol;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.UntrustedText;
import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import java.util.Map;

public final class ErrorCodes {

  public static final String PROTO_UNKNOWN_METHOD = "PROTO_UNKNOWN_METHOD";
  public static final String PROTO_INVALID_PAYLOAD = "PROTO_INVALID_PAYLOAD";
  public static final String PROTO_MESSAGE_TOO_LARGE = "PROTO_MESSAGE_TOO_LARGE";
  public static final String INTERNAL_UNEXPECTED = "INTERNAL_UNEXPECTED";
  public static final String CACHE_CORRUPT = "CACHE_CORRUPT";
  public static final String CACHE_MIGRATION_FAILED = "CACHE_MIGRATION_FAILED";
  public static final String CACHE_UNAVAILABLE = "CACHE_UNAVAILABLE";
  public static final String ANALYSIS_ALREADY_RUNNING = "ANALYSIS_ALREADY_RUNNING";
  public static final String ANALYSIS_NOT_FOUND = "ANALYSIS_NOT_FOUND";
  public static final String ANALYSIS_TOO_MANY_JAVA_SOURCES = "ANALYSIS_TOO_MANY_JAVA_SOURCES";
  public static final String ANALYSIS_CACHE_CORRUPTED = "ANALYSIS_CACHE_CORRUPTED";
  public static final String ELEMENT_NOT_FOUND = "ELEMENT_NOT_FOUND";

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

  public static ProtocolError cacheCorrupt() {
    return new ProtocolError(
        CACHE_CORRUPT,
        "o cache da análise não pôde ser lido e foi reconstruído vazio",
        ORIGIN_ANALYZER,
        Map.of());
  }

  public static ProtocolError cacheMigrationFailed(int fromVersion, int toVersion) {
    return new ProtocolError(
        CACHE_MIGRATION_FAILED,
        "o cache da análise não pôde ser migrado para esta versão do schema e foi reconstruído vazio",
        ORIGIN_ANALYZER,
        Map.of("fromVersion", fromVersion, "toVersion", toVersion));
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

  public static ProtocolError analysisAlreadyRunning() {
    return new ProtocolError(
        ANALYSIS_ALREADY_RUNNING,
        "já há uma análise rodando neste processo do analisador",
        ORIGIN_ANALYZER,
        Map.of());
  }

  public static ProtocolError analysisNotFound() {
    return new ProtocolError(
        ANALYSIS_NOT_FOUND,
        "nenhuma análise com este id está disponível neste processo do analisador",
        ORIGIN_ANALYZER,
        Map.of());
  }

  public static ProtocolError elementNotFound() {
    return new ProtocolError(
        ELEMENT_NOT_FOUND,
        "o elemento não é um elemento interno desta análise",
        ORIGIN_ANALYZER,
        Map.of());
  }

  public static ProtocolError tooManyJavaSources(int limit, int found) {
    return new ProtocolError(
        ANALYSIS_TOO_MANY_JAVA_SOURCES,
        "as raízes de código selecionadas têm mais arquivos Java que o teto de arquivos analisados",
        ORIGIN_ANALYZER,
        Map.of("limit", limit, "found", found));
  }

  public static ProtocolError analysisCacheCorrupted() {
    return new ProtocolError(
        ANALYSIS_CACHE_CORRUPTED,
        "o cache da análise foi danificado durante a análise e foi reconstruído; as linhas"
            + " desta análise se perderam com ele",
        ORIGIN_ANALYZER,
        Map.of());
  }

  public static ProtocolError cacheUnavailable(CacheUnavailableException.Kind kind) {
    final String detail =
        switch (kind) {
          case LOCKED ->
              "outro processo segura o lock do cache da análise além do tempo de espera pelo lock";
          case LOCATION_INSIDE_REPOSITORY ->
              "o diretório do cache fica dentro do repositório analisado, que nunca é escrito";
          case PRAGMA_REFUSED ->
              "a conexão com o cache da análise não aceitou um pragma obrigatório";
          case STORAGE -> "o armazenamento do cache da análise falhou: permissão, espaço ou E/S";
        };
    return new ProtocolError(
        CACHE_UNAVAILABLE, detail, ORIGIN_ANALYZER, Map.of("cause", kind.name()));
  }

  public static ProtocolError source(SourceIssue issue) {
    final String detail =
        switch (issue.code()) {
          case SOURCE_PARSE_FAILED ->
              "o arquivo não pôde ser modelado e ficou de fora da análise estrutural";
          case SOURCE_FILE_TOO_LARGE ->
              "o arquivo passa do teto de tamanho de arquivo-fonte e ficou de fora da análise";
          case SOURCE_DUPLICATE_ELEMENT ->
              "um elemento que outro arquivo já declarou ficou de fora, com tudo o que ele contém";
          case SOURCE_ELEMENT_ID_TOO_LONG ->
              "um elemento cujo id passa do teto de tamanho de id ficou de fora da análise";
        };
    return new ProtocolError(issue.code().name(), detail, ORIGIN_ANALYZER, issue.params());
  }

  private static String clamp(String value, int maxChars) {
    return UntrustedText.clamp(value, maxChars);
  }
}
